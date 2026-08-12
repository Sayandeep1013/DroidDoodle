package dev.droiddoodle.app.suite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.droiddoodle.agent.TraceJson
import dev.droiddoodle.agent.TraceRecord
import dev.droiddoodle.inference.LlmEngine
import dev.droiddoodle.model.Clock
import dev.droiddoodle.suite.PromptSuite
import dev.droiddoodle.suite.SuiteRunner
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What happened to one case under a real model.
 *
 * `grammarViolation` is separate from `passed` on purpose. A grammar violation
 * is a defect in *our* grammar -- the sampler could not have produced the
 * output otherwise -- while an assertion failure is the model reasoning badly.
 * Reporting them as one number would let a grammar bug masquerade as a weak
 * model, which is the single most expensive mistake this measurement could make.
 */
internal data class CaseResult(
    val id: String,
    val category: String,
    val passed: Boolean,
    val grammarViolation: Boolean,
    val failures: List<String>,
    val failureCode: String?,
    val totalMillis: Long,
    val promptTokens: Int,
    val outputTokens: Int,
    val tokensPerSecond: Double,
    /** Null when the case threw before a turn completed. */
    val trace: TraceRecord?,
)

internal data class SuiteRunState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = PromptSuite.ALL.size,
    val currentId: String? = null,
    val results: List<CaseResult> = emptyList(),
    val crashed: String? = null,
) {
    val passed: Int get() = results.count { it.passed }
    val grammarViolations: Int get() = results.count { it.grammarViolation }

    val passRate: Double
        get() = if (results.isEmpty()) 0.0 else passed * 100.0 / results.size

    /** Per-category pass rate, which is what P10 publishes. */
    val byCategory: Map<String, Pair<Int, Int>>
        get() = results.groupBy { it.category }
            .mapValues { (_, rows) -> rows.count { it.passed } to rows.size }

    val medianMillis: Long get() = percentile(50)
    val p90Millis: Long get() = percentile(90)

    private fun percentile(p: Int): Long {
        if (results.isEmpty()) return 0
        val sorted = results.map { it.totalMillis }.sorted()
        val index = ((p / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
}

/**
 * Runs the Prompt Suite in MODEL mode on the device.
 *
 * This is the missing half of the measurement: `:prompt-suite`'s own test runs
 * the same cases against `MockEngine`, which validates the runtime and says
 * nothing at all about a model. P10 needs these numbers.
 */
internal class SuiteRunnerViewModel(private val engine: LlmEngine) : ViewModel() {

    private val _state = MutableStateFlow(SuiteRunState())
    val state: StateFlow<SuiteRunState> = _state.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        _state.value = SuiteRunState(running = true)
        job = viewModelScope.launch {
            for (case in PromptSuite.ALL) {
                _state.update { it.copy(currentId = case.id) }
                val row = runCatching {
                    val outcome = SuiteRunner.runWith(case, engine, Clock { System.currentTimeMillis() })
                    val failures = SuiteRunner.failures(case, outcome)
                    val result = outcome.result
                    val rounds = result.trace.rounds
                    CaseResult(
                        id = case.id,
                        category = PromptSuite.categoryOf(case.id),
                        passed = failures.isEmpty(),
                        grammarViolation = result.failure?.code == "GRAMMAR_VIOLATION",
                        failures = failures,
                        failureCode = result.failure?.code,
                        totalMillis = result.trace.timings.totalMillis,
                        promptTokens = rounds.sumOf { it.promptTokens },
                        outputTokens = rounds.sumOf { it.outputTokens },
                        tokensPerSecond = rounds.sumOf { it.outputTokens } * 1000.0 /
                            rounds.sumOf { it.decodeMillis }.coerceAtLeast(1),
                        trace = result.trace,
                    )
                }.getOrElse { error ->
                    // A thrown case is recorded as a failed case, not dropped.
                    // Dropping it shrinks the denominator, which quietly
                    // inflates the pass rate -- multi-03 vanished from the
                    // first device run exactly this way, leaving "33 of 35"
                    // and a percentage computed over the survivors.
                    val reason = error.message ?: error::class.java.simpleName
                    _state.update { it.copy(crashed = "${case.id}: $reason") }
                    CaseResult(
                        id = case.id,
                        category = PromptSuite.categoryOf(case.id),
                        passed = false,
                        grammarViolation = false,
                        failures = listOf("threw: $reason"),
                        failureCode = "THREW",
                        totalMillis = 0,
                        promptTokens = 0,
                        outputTokens = 0,
                        tokensPerSecond = 0.0,
                        trace = null,
                    )
                }
                _state.update { it.copy(results = it.results + row, done = it.done + 1) }
            }
            _state.update { it.copy(running = false, currentId = null) }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.update { it.copy(running = false, currentId = null) }
    }

    /** Every trace from the run, as one document, for `results/`. */
    fun exportTraces(): String = TraceJson.encodeAll(_state.value.results.mapNotNull { it.trace })

    /** The summary table P10 commits alongside the traces. */
    fun exportSummary(modelId: String): String = buildString {
        val state = _state.value
        appendLine("# Prompt Suite — MODEL mode")
        appendLine()
        appendLine("model: `$modelId`")
        appendLine("cases: ${state.results.size} of ${state.total}")
        appendLine("passed: ${state.passed} (%.1f%%)".format(state.passRate))
        appendLine("grammar violations: ${state.grammarViolations}")
        appendLine("latency: median ${state.medianMillis}ms · p90 ${state.p90Millis}ms")
        appendLine(
            "mean tokens: prompt %.0f · output %.0f".format(
                state.results.map { it.promptTokens }.average().takeIf { !it.isNaN() } ?: 0.0,
                state.results.map { it.outputTokens }.average().takeIf { !it.isNaN() } ?: 0.0,
            ),
        )
        appendLine()
        appendLine("| category | passed | of |")
        appendLine("|---|---:|---:|")
        state.byCategory.toSortedMap().forEach { (category, counts) ->
            appendLine("| $category | ${counts.first} | ${counts.second} |")
        }
        appendLine()
        appendLine("| case | result | ms | tok/s | detail |")
        appendLine("|---|---|---:|---:|---|")
        state.results.forEach { row ->
            val verdict = when {
                row.grammarViolation -> "GRAMMAR DEFECT"
                row.passed -> "pass"
                else -> "fail"
            }
            appendLine(
                "| ${row.id} | $verdict | ${row.totalMillis} | %.1f | %s |".format(
                    row.tokensPerSecond,
                    row.failures.joinToString("; ").replace("|", "\\|").take(160),
                ),
            )
        }
        state.crashed?.let {
            appendLine()
            appendLine("A case threw: $it")
        }
    }
}
