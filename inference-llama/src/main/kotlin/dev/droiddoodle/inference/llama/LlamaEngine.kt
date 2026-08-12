package dev.droiddoodle.inference.llama

import dev.droiddoodle.inference.GenerationResult
import dev.droiddoodle.inference.LlmEngine
import dev.droiddoodle.inference.PromptTemplate
import dev.droiddoodle.inference.SamplingParams
import dev.droiddoodle.inference.StopReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Raw JNI surface. Five functions, no logic.
 *
 * Declared on a Kotlin `object`, so these are instance methods and the native
 * symbols take a `jobject` -- matching the signatures in llama_jni.cpp.
 */
internal object LlamaNative {

    init {
        System.loadLibrary("droiddoodle_llama")
    }

    external fun backendInit()

    /** Returns 0 on failure. */
    external fun loadModel(path: String, contextTokens: Int, threads: Int): Long

    external fun freeModel(handle: Long)

    external fun tokenCount(handle: Long, text: String): Int

    /** Fills [statsOut]; see [LlamaEngine.STAT_COUNT] for the layout. */
    external fun generate(
        handle: Long,
        prompt: String,
        grammar: String,
        temperature: Float,
        topP: Float,
        maxTokens: Int,
        seed: Long,
        statsOut: LongArray,
    ): String
}

public class LlamaLoadException(message: String) : IllegalStateException(message)

/**
 * The real engine. Implements the same [LlmEngine] interface `MockEngine` does,
 * which is what makes intent criterion L3 hold: nothing in `:core-agent`
 * changes when the model does.
 */
public class LlamaEngine private constructor(
    private val handle: Long,
    override val modelId: String,
    private val windowTokens: Int,
    public val promptTemplate: PromptTemplate,
    private val envelopeTokens: Int,
) : LlmEngine {

    /**
     * The window the agent may actually fill, which is the model's context minus
     * the template delimiters the engine will add. Reporting the raw window
     * instead would let the context budget overspend by exactly the amount the
     * agent cannot see.
     */
    override val contextTokens: Int = windowTokens - envelopeTokens

    @Volatile
    private var closed = false

    override fun tokenCount(text: String): Int {
        check(!closed) { "engine is closed" }
        return LlamaNative.tokenCount(handle, text)
    }

    override suspend fun generate(
        prompt: String,
        grammar: String,
        params: SamplingParams,
    ): GenerationResult = withContext(Dispatchers.Default) {
        check(!closed) { "engine is closed" }
        val stats = LongArray(STAT_COUNT)
        val text = LlamaNative.generate(
            handle = handle,
            // The agent hands over a plain assembled context and never learns
            // which delimiters the model wants -- intent criterion L3.
            prompt = promptTemplate.wrap(prompt),
            grammar = grammar,
            temperature = params.temperature,
            topP = params.topP,
            maxTokens = params.maxTokens,
            // A fixed default seed keeps runs reproducible unless a caller asks
            // otherwise, which matters when the Prompt Suite is a measurement.
            seed = params.seed ?: DEFAULT_SEED,
            statsOut = stats,
        )
        GenerationResult(
            text = text,
            promptTokens = stats[STAT_PROMPT_TOKENS].toInt(),
            outputTokens = stats[STAT_OUTPUT_TOKENS].toInt(),
            prefillMillis = stats[STAT_PREFILL_MILLIS],
            decodeMillis = stats[STAT_DECODE_MILLIS],
            stopReason = when (stats[STAT_STOP_REASON].toInt()) {
                0 -> StopReason.COMPLETE
                1 -> StopReason.MAX_TOKENS
                2 -> StopReason.CANCELLED
                else -> StopReason.ERROR
            },
            cachedPrefixTokens = stats[STAT_CACHED_PREFIX].toInt(),
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        LlamaNative.freeModel(handle)
    }

    public companion object {
        // Layout mirrored by StatIndex in llama_jni.cpp -- keep the two in step.
        internal const val STAT_PROMPT_TOKENS = 0
        internal const val STAT_OUTPUT_TOKENS = 1
        internal const val STAT_PREFILL_MILLIS = 2
        internal const val STAT_DECODE_MILLIS = 3
        internal const val STAT_CACHED_PREFIX = 4
        internal const val STAT_STOP_REASON = 5
        internal const val STAT_COUNT = 6

        private const val DEFAULT_SEED = 1234L

        /**
         * Threads default to `min(4, processors - 2)`, leaving headroom so the
         * UI thread is not starved during decode (docs/25-inference.md §5).
         */
        public fun defaultThreads(): Int =
            minOf(4, (Runtime.getRuntime().availableProcessors() - 2)).coerceAtLeast(1)

        /**
         * @param onStage called before each real step of loading. The stages are
         *   the actual ones, not a decorative progress animation -- mapping a
         *   700MB file off flash is genuinely the slow part and the user
         *   deserves to be told that is what is happening.
         */
        public fun load(
            modelPath: String,
            modelId: String,
            contextTokens: Int,
            promptTemplate: PromptTemplate,
            threads: Int = defaultThreads(),
            onStage: (String) -> Unit = {},
        ): LlamaEngine {
            onStage("Starting the inference backend")
            LlamaNative.backendInit()

            onStage("Mapping the model and allocating the context")
            val handle = LlamaNative.loadModel(modelPath, contextTokens, threads)
            if (handle == 0L) {
                throw LlamaLoadException("llama.cpp could not load the model at $modelPath")
            }
            onStage("Measuring the prompt template")
            // Measured with the real tokeniser rather than estimated, for the
            // same reason tokenCount is on the interface at all.
            val envelopeTokens = LlamaNative.tokenCount(handle, promptTemplate.envelope())
            return LlamaEngine(
                handle = handle,
                modelId = modelId,
                windowTokens = contextTokens,
                promptTemplate = promptTemplate,
                envelopeTokens = envelopeTokens.coerceAtLeast(0),
            )
        }
    }
}
