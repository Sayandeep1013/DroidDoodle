package dev.droiddoodle.app.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.droiddoodle.app.statusSuccess
import dev.droiddoodle.app.statusWarning
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The first-run model picker.
 *
 * Deliberately plain. This screen is shown once and its job is to make the
 * memory trade-off legible before a several-hundred-megabyte download, not to
 * look good.
 */
@Composable
internal fun ModelPickerScreen(
    vm: ModelPickerViewModel,
    /**
     * Debug builds only. Lets P7's on-device criteria -- canvas renders, drag
     * snaps, a turn visibly changes the board -- be checked without waiting on
     * a several-hundred-megabyte download. Null in release, where the picker
     * must be the only way through.
     */
    onUseScriptedEngine: (() -> Unit)? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf<ModelCandidate?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Choose a model", style = MaterialTheme.typography.headlineSmall)
        Text(
            "DroidDoodle runs the model on this device. It downloads once, then " +
                "never touches the network again.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "${formatBytes(state.availableMemoryBytes)} memory free · " +
                "${formatBytes(state.freeDiskBytes)} storage free",
            style = MaterialTheme.typography.labelMedium,
        )

        state.loadFailed?.let { message ->
            Warning("The model would not load: $message")
        }
        state.error?.let { message ->
            Warning(message)
        }

        val active = state.downloading
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.rows, key = { it.candidate.id }) { row ->
                CandidateCard(
                    row = row,
                    progress = active?.takeIf { it.id == row.candidate.id },
                    anyDownloadActive = active != null,
                    onDownload = {
                        if (row.fit == Fit.EXCEEDS) confirming = row.candidate
                        else vm.download(row.candidate)
                    },
                    onUse = { vm.use(row.candidate) },
                    onDelete = { vm.delete(row.candidate) },
                    onCancel = vm::cancelDownload,
                )
            }

            if (onUseScriptedEngine != null) {
                item {
                    TextButton(onClick = onUseScriptedEngine) {
                        Text("Debug: skip and use the scripted engine")
                    }
                }
            }
        }
    }

    // A candidate that will not fit is not hidden, but it does cost a second
    // deliberate confirmation. docs/25-inference.md §6, first-run flow step 3.
    confirming?.let { candidate ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("This model probably will not fit") },
            text = {
                Text(
                    "${candidate.displayName} needs about " +
                        "${formatBytes(candidate.entry.estimatedResidentBytes)} resident, and " +
                        "${formatBytes(state.availableMemoryBytes)} is free. Android will most " +
                        "likely kill the app while it loads. Download it anyway?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    vm.download(candidate)
                }) { Text("Download anyway") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Warning(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun CandidateCard(
    row: CandidateRow,
    progress: DownloadProgress?,
    anyDownloadActive: Boolean,
    onDownload: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(row.candidate.displayName, style = MaterialTheme.typography.titleMedium)

            Text(
                "${formatBytes(row.candidate.entry.fileBytes)} download · " +
                    "~${formatBytes(row.candidate.entry.estimatedResidentBytes)} resident · " +
                    "${row.candidate.entry.contextTokens} token context",
                style = MaterialTheme.typography.labelMedium,
            )

            Text(
                text = when (row.fit) {
                    Fit.COMFORTABLE -> "Should fit comfortably"
                    Fit.TIGHT -> "Tight fit -- expect memory pressure"
                    Fit.EXCEEDS -> "Larger than the memory free right now"
                },
                style = MaterialTheme.typography.labelMedium,
                color = when (row.fit) {
                    Fit.COMFORTABLE -> statusSuccess()
                    Fit.TIGHT -> statusWarning()
                    Fit.EXCEEDS -> MaterialTheme.colorScheme.error
                },
            )

            if (row.candidate.entry.note.isNotBlank()) {
                Text(row.candidate.entry.note, style = MaterialTheme.typography.bodySmall)
            }

            when {
                progress != null -> {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (progress.verifying) {
                                "Checking the file is intact…"
                            } else {
                                "${formatBytes(progress.done)} of ${formatBytes(progress.total)}"
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                }

                row.installed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onUse) { Text("Use this model") }
                    OutlinedButton(onClick = onDelete) { Text("Delete") }
                }

                else -> Button(onClick = onDownload, enabled = !anyDownloadActive) {
                    Icon(
                        painterResource(dev.droiddoodle.app.R.drawable.ic_download),
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(
                        if (row.partialBytes > 0) {
                            "Resume (${formatBytes(row.partialBytes)} done)"
                        } else {
                            "Download"
                        },
                    )
                }
            }
        }
    }
}
