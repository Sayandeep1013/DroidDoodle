package dev.droiddoodle.app.suite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The device-side Prompt Suite runner.
 *
 * Slow by nature: every case is a full turn through a real model, so a run is
 * minutes rather than seconds. That is the point — these are the numbers P10
 * publishes, and there is no faster honest way to get them.
 */
@Composable
internal fun SuiteRunnerScreen(
    vm: SuiteRunnerViewModel,
    modelId: String,
    onExportTraces: (String) -> Unit,
    onExportSummary: (String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.running) {
                    OutlinedButton(onClick = vm::cancel) { Text("Stop") }
                } else {
                    Button(onClick = vm::start) {
                        Text(if (state.results.isEmpty()) "Run all cases" else "Run again")
                    }
                }
                if (state.results.isNotEmpty() && !state.running) {
                    OutlinedButton(onClick = { onExportSummary(vm.exportSummary(modelId)) }) {
                        Text("Summary")
                    }
                    OutlinedButton(onClick = { onExportTraces(vm.exportTraces()) }) {
                        Text("Traces")
                    }
                }
            }

            if (state.running) {
                LinearProgressIndicator(
                    progress = { state.done.toFloat() / state.total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${state.done} of ${state.total} · ${state.currentId ?: ""}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            if (state.results.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            "%.0f%% passed".format(state.passRate),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            "${state.passed} of ${state.results.size} · " +
                                "median ${state.medianMillis}ms · p90 ${state.p90Millis}ms",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        // Called out on its own line, in error colour, because a
                        // grammar violation is a defect in our grammar rather
                        // than the model reasoning badly. The two must never be
                        // read as the same number.
                        Text(
                            "${state.grammarViolations} grammar violations" +
                                if (state.grammarViolations == 0) " — the grammar held" else " — GRAMMAR DEFECT",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.grammarViolations == 0) {
                                Color(0xFF2E7D32)
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        Text(
                            state.byCategory.toSortedMap().entries.joinToString("  ") {
                                "${it.key} ${it.value.first}/${it.value.second}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            state.crashed?.let {
                Text(
                    "A case threw: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (state.results.isEmpty() && !state.running) {
                Text(
                    "Runs all ${state.total} cases against the loaded model. Every case " +
                        "is a full turn, so expect this to take minutes and to warm the " +
                        "phone up. Check the Resources page for thermal throttling before " +
                        "trusting the latency numbers.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        HorizontalDivider()

        LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
            items(state.results, key = { it.id }) { row ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            row.id,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            when {
                                row.grammarViolation -> "GRAMMAR"
                                row.passed -> "pass"
                                else -> "fail"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = when {
                                row.grammarViolation -> MaterialTheme.colorScheme.error
                                row.passed -> Color(0xFF2E7D32)
                                else -> Color(0xFFEF6C00)
                            },
                        )
                    }
                    Text(
                        "${row.totalMillis}ms · %.1f tok/s · %d prompt / %d out".format(
                            row.tokensPerSecond,
                            row.promptTokens,
                            row.outputTokens,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    row.failures.forEach {
                        Text(
                            "· $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
