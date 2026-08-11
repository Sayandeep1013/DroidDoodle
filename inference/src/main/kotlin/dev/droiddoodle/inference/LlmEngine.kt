package dev.droiddoodle.inference

/**
 * The entire surface `:core-agent` knows about models.
 *
 * This interface is intent criterion L3: swapping the model must not require
 * touching any agent code.
 *
 * Generation returns a whole result rather than a token stream. Streaming buys a
 * progress indicator, but under grammar-constrained decoding a partial plan is
 * never useful -- it cannot be executed or even validated. Token-level progress,
 * if wanted, belongs as a separate optional callback rather than as the shape of
 * this interface. See docs/25-inference.md §1.
 */
public interface LlmEngine {
    public val modelId: String
    public val contextTokens: Int

    /**
     * Must use the model's real tokenizer. The context budget is enforced
     * against this number, and an estimate would make the budget a guess.
     */
    public fun tokenCount(text: String): Int

    public suspend fun generate(
        prompt: String,
        grammar: String,
        params: SamplingParams,
    ): GenerationResult

    public fun close()
}

public data class SamplingParams(
    public val temperature: Float = 0.3f,
    public val topP: Float = 0.9f,
    public val maxTokens: Int = 384,
    public val seed: Long? = null,
)

public enum class StopReason { COMPLETE, MAX_TOKENS, CANCELLED, ERROR }

public data class GenerationResult(
    public val text: String,
    public val promptTokens: Int,
    public val outputTokens: Int,
    public val prefillMillis: Long,
    public val decodeMillis: Long,
    public val stopReason: StopReason = StopReason.COMPLETE,
    /**
     * How many prompt tokens were served from a retained KV cache prefix.
     * Reported so the optimisation is measurable rather than assumed; always 0
     * for engines that do not cache.
     */
    public val cachedPrefixTokens: Int = 0,
) {
    public val tokensPerSecond: Double
        get() = if (decodeMillis <= 0) 0.0 else outputTokens * 1000.0 / decodeMillis
}
