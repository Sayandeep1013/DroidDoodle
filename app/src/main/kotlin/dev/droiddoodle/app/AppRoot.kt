package dev.droiddoodle.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.droiddoodle.app.model.ModelPickerScreen
import dev.droiddoodle.app.model.ModelPickerViewModel
import dev.droiddoodle.inference.LlmEngine
import dev.droiddoodle.inference.llama.LlamaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Three states, in order: no model, loading the model, running.
 *
 * There is no fourth state where the app is usable without a model. That is
 * deliberate -- the picker is not a dismissible prompt (docs/25-inference.md
 * §6, first-run flow step 1).
 */
@Composable
internal fun AppRoot() {
    val picker: ModelPickerViewModel = viewModel()
    val pickerState by picker.state.collectAsStateWithLifecycle()
    var scriptedEngine by remember { mutableStateOf(false) }

    if (scriptedEngine) {
        val board: BoardViewModel = viewModel(
            key = "board-scripted",
            factory = viewModelFactory { initializer { BoardViewModel(DemoEngine()) } },
        )
        AppScreen(board)
        return
    }

    val chosen = pickerState.chosen
    if (chosen == null) {
        ModelPickerScreen(
            vm = picker,
            onUseScriptedEngine = if (BuildConfig.DEBUG) ({ scriptedEngine = true }) else null,
        )
        return
    }

    var engine by remember(chosen.id) { mutableStateOf<LlmEngine?>(null) }

    LaunchedEffect(chosen.id) {
        // Loading mmaps hundreds of megabytes and builds the context; it does
        // not belong on the main thread.
        runCatching {
            withContext(Dispatchers.Default) {
                LlamaEngine.load(
                    modelPath = picker.modelPath(chosen),
                    modelId = chosen.id,
                    contextTokens = chosen.entry.contextTokens,
                    promptTemplate = chosen.promptTemplate,
                )
            }
        }.onSuccess { engine = it }
            .onFailure { picker.reportLoadFailure(it.message ?: it::class.java.simpleName) }
    }

    val loaded = engine
    if (loaded == null) {
        Loading(chosen.displayName)
        return
    }

    // Frees the llama.cpp context and model. Native memory is not the JVM
    // heap's problem, so nothing else would ever release it.
    DisposableEffect(loaded) {
        onDispose { loaded.close() }
    }

    val board: BoardViewModel = viewModel(
        // Keyed by model id so switching models builds a fresh ViewModel rather
        // than leaving the old engine wired into the old instance.
        key = "board-${chosen.id}",
        factory = viewModelFactory { initializer { BoardViewModel(loaded) } },
    )
    AppScreen(board)
}

@Composable
private fun Loading(displayName: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            "Loading $displayName…",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}
