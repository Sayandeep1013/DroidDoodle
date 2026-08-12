package dev.droiddoodle.app.model

import android.content.Context
import android.os.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * On-disk model storage and the download that fills it.
 *
 * Files live in app-internal storage, not external: a model removed by a
 * cleaner app mid-session would surface as a crash rather than as a missing
 * file (docs/25-inference.md §6).
 *
 * This is the only class in the app that opens a socket. Everything downstream
 * of a completed download is offline, which is what intent criterion T3 asserts.
 */
internal class ModelStore(context: Context) {

    private val root: File = File(context.filesDir, "models").apply { mkdirs() }
    private val storageManager =
        context.getSystemService(Context.STORAGE_SERVICE) as StorageManager

    fun fileFor(id: String): File = File(root, "$id.gguf")

    private fun partFor(id: String): File = File(root, "$id.gguf.part")

    fun isInstalled(id: String): Boolean = fileFor(id).let { it.isFile && it.length() > 0 }

    fun installedIds(): List<String> =
        root.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".gguf") }
            .map { it.name.removeSuffix(".gguf") }

    /** Bytes already fetched into the resumable part file, 0 if none. */
    fun partialBytes(id: String): Long = partFor(id).let { if (it.isFile) it.length() else 0L }

    /**
     * Space the app could actually claim, not merely what is free right now.
     *
     * `File.usableSpace` under-reports: Android will evict other apps' cached
     * data to satisfy an allocation, and `getAllocatableBytes` accounts for
     * that. On a phone with a nearly full disk the difference is easily a
     * gigabyte, which for a 700MB model is the difference between refusing a
     * download and completing one. Falls back to `usableSpace` if the storage
     * service declines to answer.
     */
    fun usableSpaceBytes(): Long = runCatching {
        val uuid = storageManager.getUuidForPath(root)
        storageManager.getAllocatableBytes(uuid)
    }.getOrElse { root.usableSpace }

    fun delete(id: String) {
        fileFor(id).delete()
        partFor(id).delete()
    }

    /** Total bytes the app is holding in models, complete and partial alike. */
    fun modelBytesOnDisk(): Long =
        root.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }

    /**
     * Part files with no matching manifest entry, and part files for a model
     * that is already installed.
     *
     * A `.part` is kept after a cancelled download so the next attempt resumes,
     * which is right -- but nothing ever removed one the user had abandoned, so
     * a browsed-then-cancelled model left hundreds of megabytes behind with no
     * way to see it, let alone delete it.
     */
    fun strandedFiles(knownIds: Set<String>): List<File> =
        root.listFiles().orEmpty().filter { file ->
            if (!file.isFile) return@filter false
            when {
                file.name.endsWith(".gguf.part") -> {
                    val id = file.name.removeSuffix(".gguf.part")
                    id !in knownIds || isInstalled(id)
                }
                file.name.endsWith(".gguf") -> file.name.removeSuffix(".gguf") !in knownIds
                else -> true
            }
        }

    fun deleteAll(files: List<File>): Long {
        var freed = 0L
        for (file in files) {
            val size = file.length()
            if (file.delete()) freed += size
        }
        return freed
    }

    /**
     * Downloads [candidate], resuming an interrupted attempt when possible.
     *
     * Progress is reported through [onProgress] as (done, total). Cancelling the
     * calling coroutine leaves the part file in place, so the next attempt
     * resumes rather than restarting.
     *
     * On a checksum mismatch the part file is deleted. Keeping a corrupt model
     * would produce baffling output that looks like a model quality problem.
     */
    suspend fun download(
        candidate: ModelCandidate,
        onProgress: (done: Long, total: Long) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val entry = candidate.entry
        val target = fileFor(entry.id)
        if (target.isFile && target.length() == entry.fileBytes) return@withContext DownloadResult.Success

        val part = partFor(entry.id)

        // Check space against what is still missing, plus a margin for the
        // filesystem. Running out at 95% wastes the whole download.
        val remaining = entry.fileBytes - part.length()
        if (root.usableSpace < remaining + SPACE_MARGIN_BYTES) {
            return@withContext DownloadResult.Failure(
                "Not enough free space: needs ${formatBytes(remaining)}, " +
                    "${formatBytes(root.usableSpace)} available",
            )
        }

        try {
            fetch(entry, part, onProgress)
        } catch (e: Exception) {
            // The part file survives deliberately, so retrying resumes.
            return@withContext DownloadResult.Failure(e.message ?: e::class.java.simpleName)
        }

        if (part.length() != entry.fileBytes) {
            part.delete()
            return@withContext DownloadResult.Failure(
                "Size mismatch: got ${part.length()} bytes, manifest says ${entry.fileBytes}",
            )
        }

        // The digest is computed by reading the finished file back rather than
        // being accumulated during transfer: a resumed download has no digest
        // state from the previous process, and a re-read of ~700MB costs
        // seconds against a download that costs minutes.
        val actual = sha256Of(part)
        if (!actual.equals(entry.sha256, ignoreCase = true)) {
            part.delete()
            return@withContext DownloadResult.Failure(
                "Checksum mismatch -- the file was corrupt and has been deleted",
            )
        }

        target.delete()
        if (!part.renameTo(target)) {
            return@withContext DownloadResult.Failure("Could not move the verified file into place")
        }
        DownloadResult.Success
    }

    private suspend fun fetch(
        entry: ModelEntry,
        part: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        var offset = part.length()
        if (offset > entry.fileBytes) {
            // A part file longer than the manifest says the whole file is means
            // the manifest changed under us. Start over.
            part.delete()
            offset = 0
        }
        if (offset == entry.fileBytes) return

        val connection = openFollowingRedirects(entry.url, offset)
        try {
            val code = connection.responseCode
            val resuming = when (code) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> false
                else -> error("Server returned HTTP $code")
            }
            // A server that ignores Range answers 200 with the whole file. Honour
            // that by truncating, rather than appending a second copy.
            if (!resuming && offset > 0) {
                part.delete()
                offset = 0
            }

            RandomAccessFile(part, "rw").use { out ->
                out.seek(offset)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var done = offset
                    var sinceReport = 0L
                    onProgress(done, entry.fileBytes)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        sinceReport += read
                        if (sinceReport >= PROGRESS_INTERVAL_BYTES) {
                            sinceReport = 0
                            onProgress(done, entry.fileBytes)
                        }
                    }
                    onProgress(done, entry.fileBytes)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Follows redirects by hand.
     *
     * HttpURLConnection's automatic redirect handling is not specified to carry
     * request headers across hops, and losing the `Range` header silently
     * restarts a resumed download from zero. Doing it explicitly also lets the
     * https-only rule be enforced at every hop instead of only the first.
     */
    private fun openFollowingRedirects(startUrl: String, offset: Long): HttpURLConnection {
        var url = startUrl
        repeat(MAX_REDIRECTS) {
            require(url.startsWith("https://")) { "Refusing a non-HTTPS hop to $url" }
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                setRequestProperty("User-Agent", USER_AGENT)
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            val code = connection.responseCode
            val redirect = code in 300..399
            if (!redirect) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            requireNotNull(location) { "Redirect $code with no Location header" }
            url = location
        }
        error("Too many redirects")
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val BUFFER_BYTES = 1 shl 16
        const val PROGRESS_INTERVAL_BYTES = 1L shl 21 // ~2 MB, ~350 updates for a 700MB model
        const val SPACE_MARGIN_BYTES = 64L shl 20
        const val CONNECT_TIMEOUT_MILLIS = 20_000
        const val READ_TIMEOUT_MILLIS = 60_000
        const val MAX_REDIRECTS = 5
        const val USER_AGENT = "DroidDoodle/0.1 (+https://github.com/Sayandeep1013/DroidDoodle)"
    }
}

internal sealed interface DownloadResult {
    data object Success : DownloadResult
    data class Failure(val message: String) : DownloadResult
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes.toDouble() / (1L shl 20))
    else -> "$bytes B"
}
