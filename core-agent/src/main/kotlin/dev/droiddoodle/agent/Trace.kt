package dev.droiddoodle.agent

/**
 * The research instrument. Satisfies intent criterion L1.
 *
 * Prompts are stored **verbatim and in full**. Storing a hash or a summary would
 * make the single most common question -- "what exactly was the model looking at
 * when it got this wrong?" -- unanswerable after the fact. Prompts are ~1200
 * tokens; storage is not the binding constraint.
 *
 * See docs/24-trace.md.
 *
 * Deviation from the spec worth noting: `diff` holds rendered summary strings
 * rather than `CellDelta` values. The trace is an export/display artefact, and
 * keeping it free of serialisation annotations lets `:core-model` stay a plain
 * Kotlin module with no serialization plugin. `TurnResult.diff` still carries
 * the structured deltas for callers that need them.
 */
public data class TraceRecord(
    public val turnId: String,
    public val startedAtMillis: Long,
    public val strategyId: String,
    public val modelId: String,
    public val settingsSnapshot: Map<String, String>,
    public val userMessage: String,
    public val rounds: List<InferenceRound>,
    public val plan: List<String>,
    public val validation: ValidationOutcome,
    public val confirmation: ConfirmationOutcome? = null,
    public val steps: List<StepOutcome>,
    public val diff: List<String>,
    public val outcome: Outcome,
    public val timings: Timings,
) {
    public val retrievalRounds: Int
        get() = rounds.count { it.role == RoundRole.RETRIEVAL_REPLAN }

    public val repairRounds: Int
        get() = rounds.count { it.role == RoundRole.REPAIR }
}

public data class InferenceRound(
    public val role: RoundRole,
    public val prompt: String,
    public val promptTokens: Int,
    /**
     * Per-block token counts and shed decisions matter specifically because a
     * turn that failed due to context budget degradation must be
     * distinguishable from one that failed due to reasoning. Without them the
     * two look identical.
     */
    public val blockTokens: Map<String, Int>,
    public val shedBlocks: List<String>,
    public val grammarHash: String,
    public val rawOutput: String,
    public val outputTokens: Int,
    public val prefillMillis: Long,
    public val decodeMillis: Long,
) {
    public val tokensPerSecond: Double
        get() = if (decodeMillis <= 0) 0.0 else outputTokens * 1000.0 / decodeMillis
}

public data class ValidationOutcome(
    public val passed: Boolean,
    public val error: String? = null,
) {
    public companion object {
        public val PASSED: ValidationOutcome = ValidationOutcome(true)
        public fun failed(message: String): ValidationOutcome = ValidationOutcome(false, message)
    }
}

public data class ConfirmationOutcome(
    public val required: Boolean,
    public val granted: Boolean,
    public val affectedNodes: List<String>,
)

public data class Timings(
    public val totalMillis: Long,
    public val assembleMillis: Long,
    public val grammarMillis: Long,
    public val inferenceMillis: Long,
    public val executeMillis: Long,
)
