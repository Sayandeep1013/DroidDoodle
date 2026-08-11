package dev.droiddoodle.inference

import dev.droiddoodle.model.Res

/**
 * Verifies that scripted output is something the real grammar-constrained engine
 * could have produced.
 *
 * Injected rather than built into [MockEngine] because `:inference` must not
 * depend on `:core-grammar` (docs/10-architecture.md §2). `:core-agent` supplies
 * the real implementation, backed by `PlanEnvelopeChecker`.
 */
public fun interface OutputCheck {
    public fun verify(output: String, grammar: String): Res<Unit, String>

    public companion object {
        /**
         * Accepts anything. Reserved for cases that deliberately exercise the
         * executor's defence-in-depth against output the grammar could never
         * produce; such cases must say so explicitly.
         */
        public val None: OutputCheck = OutputCheck { _, _ -> Res.Ok(Unit) }
    }
}

public data class MockResponse(
    public val text: String,
    public val promptTokens: Int = 0,
    public val outputTokens: Int = 0,
    public val prefillMillis: Long = 10,
    public val decodeMillis: Long = 40,
    public val stopReason: StopReason = StopReason.COMPLETE,
)

public class MockScriptExhausted(message: String) : IllegalStateException(message)

public class MockOutputRejected(message: String) : IllegalStateException(message)

/**
 * The primary development surface under constraint C2: the whole agent runtime
 * is exercised against this, on a JVM, with no device and no model file.
 */
public class MockEngine(
    script: List<MockResponse>,
    override val modelId: String = "mock",
    override val contextTokens: Int = 4096,
    private val tokenizer: (String) -> Int = { it.length / 4 },
    private val outputCheck: OutputCheck = OutputCheck.None,
) : LlmEngine {

    public constructor(vararg outputs: String) : this(outputs.map { MockResponse(it) })

    private val remaining = ArrayDeque(script)

    /** Every prompt the agent has sent, in order. Snapshot-tested by the suite. */
    public val prompts: MutableList<String> = mutableListOf()

    public val grammars: MutableList<String> = mutableListOf()

    public var callCount: Int = 0
        private set

    override fun tokenCount(text: String): Int = tokenizer(text)

    override suspend fun generate(
        prompt: String,
        grammar: String,
        params: SamplingParams,
    ): GenerationResult {
        prompts += prompt
        grammars += grammar
        callCount++

        // Exhausting the script is a test authoring error. Returning an empty
        // response instead would let a test silently assert on a turn that never
        // really happened.
        val response = remaining.removeFirstOrNull()
            ?: throw MockScriptExhausted(
                "MockEngine script exhausted after $callCount call(s); " +
                    "the agent asked for another generation",
            )

        when (val verdict = outputCheck.verify(response.text, grammar)) {
            is Res.Ok -> Unit
            is Res.Err -> throw MockOutputRejected(
                "scripted output is not grammar-representable: ${verdict.error}\n" +
                    "output was: ${response.text}",
            )
        }

        return GenerationResult(
            text = response.text,
            promptTokens = if (response.promptTokens > 0) response.promptTokens else tokenizer(prompt),
            outputTokens = if (response.outputTokens > 0) {
                response.outputTokens
            } else {
                tokenizer(response.text)
            },
            prefillMillis = response.prefillMillis,
            decodeMillis = response.decodeMillis,
            stopReason = response.stopReason,
        )
    }

    override fun close(): Unit = Unit
}
