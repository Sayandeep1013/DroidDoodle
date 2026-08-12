package dev.droiddoodle.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Shares generated text as a file.
 *
 * Not as an intent extra: a trace holds every prompt verbatim, so a session's
 * export is comfortably into the megabytes, and an extra that large throws
 * TransactionTooLargeException. Writing to cache and handing over a content URI
 * has no such ceiling.
 */
internal fun shareText(context: Context, content: String, fileName: String) {
    val dir = File(context.cacheDir, "export").apply { mkdirs() }

    // Every export used to leave a file behind for good. A suite trace is
    // ~170KB and a session's traces can be larger, so repeated exports quietly
    // accumulated in the cache with nothing ever clearing them. Keep only the
    // most recent few; the receiving app has already copied what it needs.
    dir.listFiles().orEmpty()
        .filter { it.isFile }
        .sortedByDescending { it.lastModified() }
        .drop(KEEP_EXPORTS - 1)
        .forEach { it.delete() }

    val file = File(dir, fileName)
    file.writeText(content)

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(intent, "Export trace")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** Enough to re-share a recent export, few enough to stay negligible. */
private const val KEEP_EXPORTS = 3
