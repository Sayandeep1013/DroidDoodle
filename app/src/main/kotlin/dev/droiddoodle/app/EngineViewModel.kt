package dev.droiddoodle.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.droiddoodle.app.model.ModelCandidate
import dev.droiddoodle.inference.LlmEngine
import dev.droiddoodle.inference.llama.LlamaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface EngineState {
    data object Idle : EngineState

    data class Loading(
        val displayName: String,
        val stage: String,
        val startedAtMillis: Long,
    ) : EngineState

    data class Ready(val engine: LlmEngine, val modelId: String) : EngineState

    data class Failed(val message: String) : EngineState
}

/**
 * Owns the loaded engine.
 *
 * A ViewModel rather than a `LaunchedEffect` because loading is expensive and
 * must survive both recomposition and a rotation. Under the old arrangement,
 * navigating to another screen and back could start a second load of a
 * several-hundred-megabyte model while the first was still running.
 */
internal class EngineViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private var loadingId: String? = null

    /** Idempotent: a second call for a model already loaded or loading is ignored. */
    fun ensureLoaded(candidate: ModelCandidate, modelPath: String) {
        if (loadingId == candidate.id) return
        val current = _state.value
        if (current is EngineState.Ready && current.modelId == candidate.id) return

        loadingId = candidate.id
        (current as? EngineState.Ready)?.engine?.close()

        _state.value = EngineState.Loading(
            displayName = candidate.displayName,
            stage = "Preparing",
            startedAtMillis = System.currentTimeMillis(),
        )

        viewModelScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.Default) {
                    LlamaEngine.load(
                        modelPath = modelPath,
                        modelId = candidate.id,
                        contextTokens = candidate.entry.contextTokens,
                        promptTemplate = candidate.promptTemplate,
                        onStage = { stage ->
                            _state.update { previous ->
                                // Only a Loading state takes stage updates; if the
                                // user has already navigated away and unloaded,
                                // this must not resurrect it.
                                (previous as? EngineState.Loading)?.copy(stage = stage) ?: previous
                            }
                        },
                    )
                }
            }
            outcome
                .onSuccess { _state.value = EngineState.Ready(it, candidate.id) }
                .onFailure {
                    _state.value = EngineState.Failed(it.message ?: it::class.java.simpleName)
                }
            loadingId = null
        }
    }

    fun unload() {
        (_state.value as? EngineState.Ready)?.engine?.close()
        _state.value = EngineState.Idle
        loadingId = null
    }

    override fun onCleared() {
        // Native memory is not the JVM heap's problem; without this the model
        // stays mapped for the life of the process.
        (_state.value as? EngineState.Ready)?.engine?.close()
        super.onCleared()
    }
}
