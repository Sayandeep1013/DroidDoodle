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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DroidDoodleTheme {
                Surface(Modifier.fillMaxSize()) { AppScreen() }
            }
        }
    }
}

@Composable
private fun AppScreen(vm: BoardViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var scale by remember { mutableFloatStateOf(1f) }

    Column(Modifier.fillMaxSize()) {

        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("DroidDoodle", style = MaterialTheme.typography.titleMedium)
            Text(
                "${state.board.size} nodes",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { scale = (scale - 0.2f).coerceAtLeast(0.4f) }) { Text("−") }
            TextButton(onClick = { scale = (scale + 0.2f).coerceAtMost(2.0f) }) { Text("+") }
            TextButton(onClick = vm::undo, enabled = state.canUndo) { Text("Undo") }
            TextButton(onClick = vm::redo, enabled = state.canRedo) { Text("Redo") }
        }

        Box(Modifier.weight(1f)) {
            BoardCanvas(
                board = state.board,
                selected = state.selected,
                scale = scale,
                onSelect = vm::select,
                onDrag = vm::dragTo,
                modifier = Modifier.fillMaxSize(),
            )

            if (state.board.isEmpty && !state.thinking) {
                Text(
                    "Tell it what to make.\nTry \"create a village\" or \"make a dungeon\".",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }

            if (state.thinking) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.TopEnd).padding(16.dp),
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
