package dev.droiddoodle.app.trace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.droiddoodle.agent.InferenceRound
import dev.droiddoodle.agent.Outcome
import dev.droiddoodle.agent.StepResult
import dev.droiddoodle.agent.TraceRecord

/**
 * The trace screen.
 *
 * Reachable directly from the canvas, not buried behind a developer setting
 * (docs/24-trace.md §4). The trace is the research output; hiding it would make
 * the app a toy that happens to keep logs.
 */
@Composable
internal fun TraceScreen(traces: List<TraceRecord>) {
    var openTurnId by remember { mutableStateOf<String?>(null) }
    val open = traces.firstOrNull { it.turnId == openTurnId }

    Column(Modifier.fillMaxSize()) {
        if (open != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { openTurnId = null }) { Text("← All turns") }
                Text(
                    open.userMessage.lineSequence().first(),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider()
            TurnDetail(open)
        } else if (traces.isEmpty()) {
            Text(
                "No turns yet.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                // Newest first: the turn you want is almost always the last one.
                items(traces.asReversed(), key = { it.turnId }) { record ->
                    TurnRow(record) { openTurnId = record.turnId }
                }
            }
        }
    }
}

@Composable
private fun TurnRow(record: TraceRecord, onClick: () -> Unit) {
    val tokensPerSecond = record.rounds.sumOf { it.outputTokens }.toDouble() /
        (record.rounds.sumOf { it.decodeMillis }.coerceAtLeast(1) / 1000.0)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutcomeBadge(record.outcome)
            Text(
                record.userMessage.lineSequence().first(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
        }
        Text(
            "${record.steps.size} steps · ${record.timings.totalMillis}ms · " +
                "%.1f tok/s".format(tokensPerSecond),
            style = MaterialTheme.typography.labelSmall,
        )
    }
    HorizontalDivider()
}

@Composable
private fun OutcomeBadge(outcome: Outcome) {
    val color = when (outcome) {
        Outcome.OK -> statusSuccess()
        Outcome.PARTIAL -> statusWarning()
        Outcome.REJECTED, Outcome.ABORTED -> MaterialTheme.colorScheme.error
        Outcome.AWAITING_CONFIRMATION -> MaterialTheme.colorScheme.outline
    }
    Surface(color = color.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small) {
        Text(
            outcome.name,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Rendered as the phase pipeline of `23-agent-runtime.md` §2, so what is on
 * screen matches the specified lifecycle rather than a convenient regrouping of
 * it. Every section expands to the verbatim prompt or raw output.
 */
@Composable
private fun TurnDetail(record: TraceRecord) {
    // One item per section rather than one item holding all of them: a turn
    // with three rounds carries three verbatim prompts, and composing all of
    // them to show the OUTCOME line defeats the point of a lazy list.
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item(key = "user") { Section("USER") { Mono(record.userMessage) } }

        itemsIndexed(record.rounds, key = { i, _ -> "round-$i" }) { index, round ->
            RoundSections(round, index, record.rounds.size)
        }

        item(key = "plan") {
            Section("PLAN") {
                if (record.plan.isEmpty()) Mono("(no plan)")
                else record.plan.forEachIndexed { i, line -> Mono("${i + 1}  $line") }
            }
        }

        item(key = "validate") {
            Section("VALIDATE") {
                Mono(
                    if (record.validation.passed) "passed"
                    else "FAILED · ${record.validation.error}",
                )
            }
        }

        record.confirmation?.let { confirmation ->
            item(key = "confirm") {
                Section("CONFIRM") {
                    Mono(
                        "required=${confirmation.required} granted=${confirmation.granted} " +
                            "affects ${confirmation.affectedNodes.joinToString(",")}",
                    )
                }
            }
        }

        item(key = "exec") {
            Section("EXEC") {
                if (record.steps.isEmpty()) Mono("(nothing executed)")
                else record.steps.forEach { step ->
                    val mark = when (step.result) {
                        StepResult.OK -> "✓"
                        StepResult.FAILED -> "✗"
                        StepResult.SKIPPED -> "–"
                    }
                    Mono("${step.index}  $mark ${step.tool} ${step.args}  ${step.durationMillis}ms")
                    if (step.resolvedRefs.isNotEmpty()) {
                        Mono("     refs " + step.resolvedRefs.entries.joinToString(" ") { "${it.key}→${it.value}" })
                    }
                    step.error?.let { Mono("     $it") }
                }
            }
        }

        item(key = "diff") {
            Section("DIFF") {
                if (record.diff.isEmpty()) Mono("(board unchanged)")
                else record.diff.forEach { Mono(it) }
            }
        }

        item(key = "outcome") {
            Section("OUTCOME") {
                Mono(
                    "${record.outcome} · ${record.timings.totalMillis}ms total " +
                        "(assemble ${record.timings.assembleMillis} · " +
                        "grammar ${record.timings.grammarMillis} · " +
                        "inference ${record.timings.inferenceMillis} · " +
                        "execute ${record.timings.executeMillis})",
                )
            }
        }

        item(key = "settings") {
            Section("SETTINGS") {
                // The snapshot the turn actually ran under. A trace read weeks
                // later is worthless if the settings have moved since.
                record.settingsSnapshot.toSortedMap().forEach { (k, v) -> Mono("$k = $v") }
            }
        }

        item(key = "ids") {
            Section("IDS") {
                Mono("turn ${record.turnId}")
                Mono("strategy ${record.strategyId}")
                Mono("model ${record.modelId}")
                Mono("startedAt ${record.startedAtMillis}")
                Mono("retrieval rounds ${record.retrievalRounds} · repair rounds ${record.repairRounds}")
            }
        }
    }
}

@Composable
private fun RoundSections(round: InferenceRound, index: Int, total: Int) {
    val label = if (total > 1) " ${index + 1}/$total (${round.role})" else " (${round.role})"

    Section("CONTEXT$label") {
        Mono("${round.promptTokens} tok")
        Mono(round.blockTokens.entries.joinToString(" · ") { "${it.key} ${it.value}" })
        Mono(
            if (round.shedBlocks.isEmpty()) "no blocks shed"
            else "SHED: ${round.shedBlocks.joinToString(", ")}",
        )
        Expandable("verbatim prompt", round.prompt)
    }

    Section("MODEL$label") {
        Mono("grammar #${round.grammarHash}")
        Mono(
            "prefill ${round.prefillMillis}ms · decode ${round.decodeMillis}ms · " +
                "%.1f tok/s".format(round.tokensPerSecond),
        )
        Mono("stop ${round.stopReason} · cached prefix ${round.cachedPrefixTokens} tok")
        Expandable("raw output", round.rawOutput.ifBlank { "(empty)" })
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 14.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
private fun Mono(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun Expandable(label: String, body: String) {
    var open by remember { mutableStateOf(false) }
    TextButton(
        onClick = { open = !open },
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
    ) {
        Text(if (open) "▾ $label" else "▸ $label", style = MaterialTheme.typography.labelSmall)
    }
    if (open) {
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Prompts contain long grammar lines that must not be wrapped into
            // illegibility, so the block scrolls sideways rather than reflowing.
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}
