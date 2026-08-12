package dev.droiddoodle.agent

import dev.droiddoodle.inference.StopReason
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.reflect.full.memberProperties

class TraceJsonTest {

    /**
     * The maximal record: every optional field populated, every collection
     * non-empty, every string containing something that naive JSON handling
     * would mangle. A round trip over a sparse record proves much less.
     */
    private fun maximalRecord() = TraceRecord(
        turnId = "turn-abc123",
        startedAtMillis = 1_700_000_000_000,
        strategyId = "plan_then_execute",
        modelId = "gemma-3-1b-it-qat-q4_0",
        settingsSnapshot = mapOf(
            "model.temperature" to "0.3",
            "agent.max_steps" to "8",
        ),
        userMessage = "put a \"castle\" north of the village\nand connect them",
        rounds = listOf(
            InferenceRound(
                role = RoundRole.INITIAL,
                prompt = "system rules…\ttab and \"quotes\" and \\backslash",
                promptTokens = 1180,
                blockTokens = mapOf("system" to 150, "tools" to 350, "board" to 310),
                shedBlocks = listOf("history"),
                grammarHash = "a3f1",
                rawOutput = """{"steps":[{"tool":"create_node"}]}""",
                outputTokens = 46,
                prefillMillis = 780,
                decodeMillis = 3210,
                cachedPrefixTokens = 512,
                stopReason = StopReason.COMPLETE,
            ),
            InferenceRound(
                role = RoundRole.REPAIR,
                prompt = "…",
                promptTokens = 1240,
                blockTokens = emptyMap(),
                shedBlocks = emptyList(),
                grammarHash = "b7c2",
                rawOutput = "",
                outputTokens = 384,
                prefillMillis = 12,
                decodeMillis = 9000,
                cachedPrefixTokens = 0,
                stopReason = StopReason.MAX_TOKENS,
            ),
        ),
        plan = listOf("1 create_node type=PLACE", "2 connect \$1 n1"),
        validation = ValidationOutcome.failed("UNRESOLVED_STEP_REF at step 2"),
        confirmation = ConfirmationOutcome(
            required = true,
            granted = false,
            affectedNodes = listOf("n1", "n7"),
        ),
        steps = listOf(
            StepOutcome(
                index = 1,
                tool = "create_node",
                args = """{"label":"Castle"}""",
                resolvedRefs = mapOf("\$1" to "n7"),
                result = StepResult.OK,
                error = null,
                durationMillis = 41,
            ),
            StepOutcome(
                index = 2,
                tool = "connect",
                args = "{}",
                resolvedRefs = emptyMap(),
                result = StepResult.FAILED,
                error = "UNKNOWN_NODE: n99",
                durationMillis = 3,
            ),
        ),
        diff = listOf("+ n7 place \"Castle\" @-1,0"),
        outcome = Outcome.PARTIAL,
        timings = Timings(
            totalMillis = 4031,
            assembleMillis = 20,
            grammarMillis = 11,
            inferenceMillis = 3990,
            executeMillis = 44,
        ),
    )

    @Test
    fun `a maximal record round trips to an equal record`() {
        val original = maximalRecord()
        assertEquals(original, TraceJson.decode(TraceJson.encode(original)))
    }

    @Test
    fun `compact and pretty encodings decode identically`() {
        val original = maximalRecord()
        assertEquals(
            TraceJson.decode(TraceJson.encode(original, prettyPrint = true)),
            TraceJson.decode(TraceJson.encode(original, prettyPrint = false)),
        )
    }

    @Test
    fun `an absent confirmation stays absent rather than becoming a default`() {
        // A ConfirmationOutcome(required=false) means "the gate ran and did not
        // trip". Null means "the gate never ran". Collapsing the two would make
        // the delete-path suite cases unreadable in an exported trace.
        val original = maximalRecord().copy(confirmation = null, validation = ValidationOutcome.PASSED)
        val decoded = TraceJson.decode(TraceJson.encode(original))
        assertEquals(null, decoded.confirmation)
        assertEquals(original, decoded)
    }

    @Test
    fun `a record with empty collections round trips`() {
        val original = maximalRecord().copy(
            rounds = emptyList(),
            plan = emptyList(),
            steps = emptyList(),
            diff = emptyList(),
            settingsSnapshot = emptyMap(),
        )
        assertEquals(original, TraceJson.decode(TraceJson.encode(original)))
    }

    @Test
    fun `a multi turn document round trips`() {
        val records = listOf(maximalRecord(), maximalRecord().copy(turnId = "turn-two"))
        assertEquals(records, TraceJson.decodeAll(TraceJson.encodeAll(records)))
    }

    @Test
    fun `a document from a future schema is refused rather than half read`() {
        val text = TraceJson.encodeAll(listOf(maximalRecord()))
            .replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        assertFailsWith<IllegalArgumentException> { TraceJson.decodeAll(text) }
    }

    @Test
    fun `the prompt is exported verbatim and not summarised`() {
        val original = maximalRecord()
        val encoded = TraceJson.encode(original)
        // Criterion L1 depends on this. A truncating exporter would still pass
        // the round-trip test if it truncated symmetrically.
        assertContains(encoded, "tab and")
        assertEquals(
            original.rounds[0].prompt,
            TraceJson.decode(encoded).rounds[0].prompt,
        )
    }

    /**
     * The drift detector.
     *
     * Encode and decode are hand-written, so a field added to a traced type is
     * silently dropped unless someone remembers to touch this file. This test
     * reflects over the declared properties and fails when one is missing from
     * the encoded JSON, which turns "someone remembers" into "CI tells you".
     */
    @Test
    fun `every declared property of every traced type appears in the export`() {
        val encoded = TraceJson.encode(maximalRecord())
        val types = listOf(
            TraceRecord::class,
            InferenceRound::class,
            StepOutcome::class,
            Timings::class,
            ValidationOutcome::class,
            ConfirmationOutcome::class,
        )
        // Derived conveniences are computed from exported fields, so exporting
        // them would be redundant rather than informative.
        val derived = setOf(
            "retrievalRounds", "repairRounds", "tokensPerSecond",
        )
        val missing = types.flatMap { type ->
            type.memberProperties
                .map { it.name }
                .filterNot { it in derived }
                .filterNot { encoded.contains("\"$it\"") }
                .map { "${type.simpleName}.$it" }
        }
        assertTrue(missing.isEmpty(), "not exported: $missing")
    }
}
