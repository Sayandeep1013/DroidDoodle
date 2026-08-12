package dev.droiddoodle.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.droiddoodle.app.model.ModelPickerScreen
import dev.droiddoodle.app.model.ModelPickerViewModel
import dev.droiddoodle.app.settings.SettingsStore
import dev.droiddoodle.model.SettingKeys
import kotlinx.coroutines.delay

/**
 * Four states, in order: no model, loading the model, running, or the load
 * failed.
 *
 * There is no fifth state where the app is usable without a model. That is
 * deliberate -- the picker is not a dismissible prompt (docs/25-inference.md
 * §6, first-run flow step 1).
 */
@Composable
internal fun AppRoot() {
    val context = LocalContext.current
    val settingsStore = remember(context) { SettingsStore(context.applicationContext) }
    val settings by settingsStore.snapshot.collectAsStateWithLifecycle()

    // The theme is applied here rather than in MainActivity so that `ui.theme`
    // is a live setting. It is agent-writable, which makes "make it dark" a
    // visible self-modification rather than a logged intention.
    val dark = when (settings.string(SettingKeys.UI_THEME)) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    DroidDoodleTheme(dark = dark) {
        Surface(Modifier.fillMaxSize()) {
            AppContent(settingsStore)
        }
    }
}

@Composable
private fun AppContent(settingsStore: SettingsStore) {
    val picker: ModelPickerViewModel = viewModel()
    val engineVm: EngineViewModel = viewModel()
    val pickerState by picker.state.collectAsStateWithLifecycle()
    val engineState by engineVm.state.collectAsStateWithLifecycle()
    var scriptedEngine by remember { mutableStateOf(false) }

    if (scriptedEngine) {
        val board: BoardViewModel = viewModel(
            key = "board-scripted",
            factory = viewModelFactory { initializer { BoardViewModel(DemoEngine(), settingsStore) } },
        )
        AppScreen(board, modelLabel = "scripted engine")
        return
    }

    val chosen = pickerState.chosen

    // Loading starts the moment a model is known to be installed, without
    // waiting for anything to be drawn. On a relaunch with a model already
    // chosen this means the mmap is under way before the first frame.
    LaunchedEffect(chosen?.id) {
        if (chosen != null) engineVm.ensureLoaded(chosen, picker.modelPath(chosen))
    }

    if (chosen == null) {
        ModelPickerScreen(
            vm = picker,
            onUseScriptedEngine = if (BuildConfig.DEBUG) ({ scriptedEngine = true }) else null,
        )
        return
    }

    when (val state = engineState) {
        is EngineState.Ready -> {
            val board: BoardViewModel = viewModel(
                // Keyed by model id so switching models builds a fresh ViewModel
                // rather than leaving the old engine wired into the old instance.
                key = "board-${state.modelId}",
                factory = viewModelFactory {
                    initializer { BoardViewModel(state.engine, settingsStore) }
                },
            )
            AppScreen(board, modelLabel = chosen.displayName)
        }

        is EngineState.Failed -> LoadFailed(state.message) {
            engineVm.unload()
            picker.reportLoadFailure(state.message)
        }

        is EngineState.Loading -> Loading(state)

        EngineState.Idle -> Loading(
            EngineState.Loading(chosen.displayName, "Preparing", System.currentTimeMillis()),
        )
    }
}

@Composable
private fun Loading(state: EngineState.Loading) {
    // A determinate bar would be a lie -- llama.cpp reports no progress during
    // the mmap. Elapsed seconds is a real number and tells the user the same
    // thing a fake bar would pretend to.
    var elapsed by remember(state.startedAtMillis) { mutableIntStateOf(0) }
    LaunchedEffect(state.startedAtMillis) {
        while (true) {
            delay(1000)
            elapsed = ((System.currentTimeMillis() - state.startedAtMillis) / 1000).toInt()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(state.displayName, style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            state.stage,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            if (elapsed > 0) "${elapsed}s" else "",
            style = MaterialTheme.typography.labelMedium,
        )
        if (elapsed >= SLOW_LOAD_SECONDS) {
            Text(
                "First load reads the whole model off storage. Later launches " +
                    "are faster while the file stays in the page cache.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LoadFailed(message: String, onBackToPicker: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("The model would not load", style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        Button(onClick = onBackToPicker) { Text("Choose another model") }
    }
}

private const val SLOW_LOAD_SECONDS = 8
