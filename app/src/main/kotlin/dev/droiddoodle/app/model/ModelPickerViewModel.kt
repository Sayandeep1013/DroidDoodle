package dev.droiddoodle.app.model

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How a candidate's estimated resident size compares with free memory. */
internal enum class Fit { COMFORTABLE, TIGHT, EXCEEDS }

internal data class CandidateRow(
    val candidate: ModelCandidate,
    val installed: Boolean,
    val partialBytes: Long,
    val fit: Fit,
)

internal data class PickerState(
    val rows: List<CandidateRow> = emptyList(),
    val availableMemoryBytes: Long = 0,
    val freeDiskBytes: Long = 0,
    /** Everything the app is holding in models, complete and partial. */
    val modelBytesOnDisk: Long = 0,
    /** Abandoned part files and files no manifest entry claims. */
    val strandedBytes: Long = 0,
    val justFreedBytes: Long? = null,
    /** Non-null while a download is in flight. */
    val downloading: DownloadProgress? = null,
    val error: String? = null,
    /** Set when a model is installed and the app may proceed. */
    val chosen: ModelCandidate? = null,
    val loadFailed: String? = null,
)

internal data class DownloadProgress(
    val id: String,
    val done: Long,
    val total: Long,
    val verifying: Boolean = false,
) {
    val fraction: Float get() = if (total > 0) (done.toDouble() / total).toFloat() else 0f
}

/**
 * Drives the first-run model picker.
 *
 * The picker is not dismissible: without a model the app cannot do the one
 * thing it exists to do (docs/25-inference.md §6). A candidate that will not fit
 * in memory is shown with a warning rather than hidden -- on a ≤6GB device,
 * finding that boundary is part of the point of the project.
 */
internal class ModelPickerViewModel(application: Application) : AndroidViewModel(application) {

    private val store = ModelStore(application)
    private val prefs =
        application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(PickerState())
    val state: StateFlow<PickerState> = _state.asStateFlow()

    private var downloadJob: Job? = null
    private var candidates: List<ModelCandidate> = emptyList()

    init {
        refresh()
        // Skip the picker when the previously chosen model is still on disk, so
        // a normal launch goes straight to the canvas.
        val remembered = prefs.getString(KEY_CHOSEN, null)
        val ready = candidates.firstOrNull { it.id == remembered && store.isInstalled(it.id) }
            ?: candidates.firstOrNull { store.isInstalled(it.id) }
        if (ready != null) _state.update { it.copy(chosen = ready) }
    }

    fun refresh() {
        candidates = try {
            ModelCatalog.load(getApplication<Application>())
        } catch (e: Exception) {
            _state.update { it.copy(error = "Could not read the bundled model list: ${e.message}") }
            emptyList()
        }
        val availableMemory = availableMemoryBytes()
        _state.update { current ->
            current.copy(
                rows = candidates.map { candidate ->
                    CandidateRow(
                        candidate = candidate,
                        installed = store.isInstalled(candidate.id),
                        partialBytes = store.partialBytes(candidate.id),
                        fit = fitOf(candidate.entry.estimatedResidentBytes, availableMemory),
                    )
                },
                availableMemoryBytes = availableMemory,
                freeDiskBytes = store.usableSpaceBytes(),
                modelBytesOnDisk = store.modelBytesOnDisk(),
                strandedBytes = store.strandedFiles(candidates.map { it.id }.toSet())
                    .sumOf { it.length() },
            )
        }
    }

    fun download(candidate: ModelCandidate) {
        if (downloadJob?.isActive == true) return
        _state.update {
            it.copy(
                error = null,
                downloading = DownloadProgress(
                    id = candidate.id,
                    done = store.partialBytes(candidate.id),
                    total = candidate.entry.fileBytes,
                ),
            )
        }
        downloadJob = viewModelScope.launch {
            val result = store.download(candidate) { done, total ->
                _state.update { current ->
                    // Report verification once the bytes are all in: hashing
                    // ~700MB takes long enough that a frozen 100% bar reads as
                    // a hang.
                    current.copy(
                        downloading = DownloadProgress(
                            id = candidate.id,
                            done = done,
                            total = total,
                            verifying = done >= total,
                        ),
                    )
                }
            }
            when (result) {
                is DownloadResult.Success -> {
                    prefs.edit().putString(KEY_CHOSEN, candidate.id).apply()
                    _state.update { it.copy(downloading = null, chosen = candidate) }
                }
                is DownloadResult.Failure ->
                    _state.update { it.copy(downloading = null, error = result.message) }
            }
            refresh()
        }
    }

    fun cancelDownload() {
        // The part file is kept, so pressing download again resumes.
        downloadJob?.cancel()
        downloadJob = null
        _state.update { it.copy(downloading = null) }
        refresh()
    }

    fun use(candidate: ModelCandidate) {
        prefs.edit().putString(KEY_CHOSEN, candidate.id).apply()
        _state.update { it.copy(chosen = candidate) }
    }

    fun delete(candidate: ModelCandidate) {
        store.delete(candidate.id)
        if (prefs.getString(KEY_CHOSEN, null) == candidate.id) prefs.edit().remove(KEY_CHOSEN).apply()
        _state.update { it.copy(chosen = null) }
        refresh()
    }

    fun modelPath(candidate: ModelCandidate): String = store.fileFor(candidate.id).absolutePath

    /**
     * Deletes abandoned part files and anything the manifest no longer claims.
     *
     * Installed models are never touched: those are deleted per-model, on
     * purpose, and silently removing one because a cleanup button was pressed
     * would mean a surprise 687MB download later.
     */
    fun cleanUp() {
        val freed = store.deleteAll(store.strandedFiles(candidates.map { it.id }.toSet()))
        refresh()
        _state.update { it.copy(justFreedBytes = freed) }
    }

    /** Sends the user back to the picker after a load failure, with the reason. */
    fun reportLoadFailure(message: String) {
        _state.update { it.copy(chosen = null, loadFailed = message) }
        refresh()
    }

    fun clearError() = _state.update { it.copy(error = null, loadFailed = null) }

    private fun availableMemoryBytes(): Long {
        val activityManager =
            getApplication<Application>().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.availMem
    }

    private fun fitOf(estimatedResident: Long, available: Long): Fit = when {
        available <= 0 -> Fit.TIGHT // unknown, so do not claim comfort
        estimatedResident > available -> Fit.EXCEEDS
        estimatedResident > available * TIGHT_FRACTION -> Fit.TIGHT
        else -> Fit.COMFORTABLE
    }

    private companion object {
        const val PREFS = "droiddoodle.model"
        const val KEY_CHOSEN = "chosenModelId"

        // Leaving under a third of free memory headroom means the system will be
        // reclaiming aggressively while decoding, which shows up as latency
        // rather than as an obvious failure.
        const val TIGHT_FRACTION = 0.7
    }
}
