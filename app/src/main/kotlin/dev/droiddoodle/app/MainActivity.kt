package dev.droiddoodle.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import dev.droiddoodle.app.resources.ResourceScreen
import dev.droiddoodle.app.settings.SettingsScreen
import dev.droiddoodle.app.suite.SuiteRunnerScreen
import dev.droiddoodle.app.suite.SuiteRunnerViewModel
import dev.droiddoodle.app.trace.TraceScreen
import dev.droiddoodle.model.SettingKeys

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The theme lives inside AppRoot, which reads it from settings.
        setContent { AppRoot() }
    }
}

private enum class Screen { CANVAS, TRACE, SETTINGS, RESOURCES, SUITE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppScreen(vm: BoardViewModel, modelLabel: String) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var screen by remember { mutableStateOf(Screen.CANVAS) }
    var menuOpen by remember { mutableStateOf(false) }

    // ui.cell_size sets the base zoom; the +/- controls adjust from there, so a
    // settings change is visible without discarding the user's current zoom.
    val baseScale = when (settings.string(SettingKeys.UI_CELL_SIZE)) {
        "small" -> 0.7f
        "large" -> 1.4f
        else -> 1.0f
    }
    var zoom by remember { mutableFloatStateOf(1f) }

    when (screen) {
        Screen.TRACE -> {
            SubScreen(
                title = "Trace · ${state.traces.size} turns",
                onBack = { screen = Screen.CANVAS },
                actions = {
                    TextButton(
                        onClick = {
                            shareText(context, vm.exportTraces(), "droiddoodle-trace.json")
                        },
                        enabled = state.traces.isNotEmpty(),
                    ) { Text("Export") }
                },
            ) { TraceScreen(state.traces) }
            return
        }
        Screen.SETTINGS -> {
            SubScreen(
                title = "Settings",
                onBack = { screen = Screen.CANVAS },
                actions = { TextButton(onClick = vm::resetSettings) { Text("Reset") } },
            ) { SettingsScreen(snapshot = settings, onChange = vm::setSetting) }
            return
        }
        Screen.RESOURCES -> {
            SubScreen(title = "Resources", onBack = { screen = Screen.CANVAS }) {
                ResourceScreen()
            }
            return
        }
        Screen.SUITE -> {
            val suiteVm: SuiteRunnerViewModel = viewModel(
                key = "suite-${vm.engine.modelId}",
                factory = viewModelFactory { initializer { SuiteRunnerViewModel(vm.engine) } },
            )
            SubScreen(title = "Prompt Suite", onBack = { screen = Screen.CANVAS }) {
                SuiteRunnerScreen(
                    vm = suiteVm,
                    modelId = vm.engine.modelId,
                    onExportTraces = {
                        shareText(context, it, "suite-${vm.engine.modelId}-traces.json")
                    },
                    onExportSummary = {
                        shareText(context, it, "suite-${vm.engine.modelId}.md")
                    },
                )
            }
            return
        }
        Screen.CANVAS -> Unit
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("DroidDoodle", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "$modelLabel · ${state.board.size} nodes",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = vm::undo, enabled = state.canUndo) {
                        Icon(painterResource(R.drawable.ic_undo), contentDescription = "Undo")
                    }
                    IconButton(onClick = vm::redo, enabled = state.canRedo) {
                        Icon(painterResource(R.drawable.ic_redo), contentDescription = "Redo")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Zoom in") },
                            leadingIcon = { MenuIcon(R.drawable.ic_zoom_in) },
                            onClick = { zoom = (zoom + 0.2f).coerceAtMost(2.0f) },
                        )
                        DropdownMenuItem(
                            text = { Text("Zoom out") },
                            leadingIcon = { MenuIcon(R.drawable.ic_zoom_out) },
                            onClick = { zoom = (zoom - 0.2f).coerceAtLeast(0.4f) },
                        )
                        DropdownMenuItem(
                            text = { Text("Trace") },
                            leadingIcon = { MenuIcon(R.drawable.ic_trace) },
                            onClick = { menuOpen = false; screen = Screen.TRACE },
                        )
                        DropdownMenuItem(
                            text = { Text("Resources") },
                            leadingIcon = { MenuIcon(R.drawable.ic_resources) },
                            onClick = { menuOpen = false; screen = Screen.RESOURCES },
                        )
                        DropdownMenuItem(
                            text = { Text("Prompt Suite") },
                            leadingIcon = { MenuIcon(R.drawable.ic_suite) },
                            onClick = { menuOpen = false; screen = Screen.SUITE },
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            leadingIcon = { Icon(Icons.Filled.Settings, null) },
                            onClick = { menuOpen = false; screen = Screen.SETTINGS },
                        )
                    }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        vm.clearMessage()
                    },
                    placeholder = { Text("say what should happen") },
                    singleLine = true,
                    enabled = !state.thinking,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        vm.send(input)
                        input = ""
                    },
                    enabled = !state.thinking && input.isNotBlank(),
                ) {
                    Text("Go")
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            BoardCanvas(
                board = state.board,
                selected = state.selected,
                scale = baseScale * zoom,
                onSelect = vm::select,
                onDrag = vm::dragTo,
                modifier = Modifier.fillMaxSize(),
                gridVisible = settings.bool(SettingKeys.UI_GRID_VISIBLE),
            )

            if (state.board.isEmpty && !state.thinking) {
                Text(
                    "Tell it what to make.\nTry \"create a village\" or \"make a dungeon\".",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                )
            }

            if (state.thinking) {
                CircularProgressIndicator(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                )
            }

            state.message?.let { message ->
                Surface(
                    tonalElevation = 3.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp),
                ) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    // The confirmation gate is part of the turn result, not a UI callback, which
    // is what lets it be tested headlessly. Here it simply becomes a dialog.
    state.pendingConfirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = vm::dismissPending,
            title = { Text("Delete ${pending.doomed.size} thing(s)?") },
            text = {
                Text(
                    pending.doomed.mapNotNull { state.board.node(it)?.label }
                        .joinToString(", ")
                        .ifBlank { "This will remove everything inside as well." },
                )
            },
            confirmButton = { TextButton(onClick = vm::confirmPending) { Text("Delete") } },
            dismissButton = { TextButton(onClick = vm::dismissPending) { Text("Cancel") } },
        )
    }
}

/** A vendored drawable at menu-icon size. See docs/THIRD-PARTY.md. */
@Composable
private fun MenuIcon(resId: Int) {
    Icon(painterResource(resId), contentDescription = null)
}
