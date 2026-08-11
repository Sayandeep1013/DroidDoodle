package dev.droiddoodle.inference

import dev.droiddoodle.model.Res
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MockEngineTest {

    @Test
    fun `responses are returned in script order`() = runTest {
        val engine = MockEngine("first", "second")
        assertEquals("first", engine.generate("p1", "g", SamplingParams()).text)
        assertEquals("second", engine.generate("p2", "g", SamplingParams()).text)
        assertEquals(2, engine.callCount)
        assertEquals(listOf("p1", "p2"), engine.prompts)
    }

    @Test
    fun `exhausting the script throws rather than returning an empty response`() = runTest {
        val engine = MockEngine("only one")
        engine.generate("p", "g", SamplingParams())
        assertFailsWith<MockScriptExhausted> {
            engine.generate("p", "g", SamplingParams())
        }
    }

    @Test
    fun `output failing the injected check is refused`() = runTest {
        val engine = MockEngine(
            script = listOf(MockResponse("nonsense")),
            outputCheck = { _, _ -> Res.Err("not a plan") },
        )
        val failure = assertFailsWith<MockOutputRejected> {
            engine.generate("p", "g", SamplingParams())
        }
        // The message must name the offending output, since this fires during
        // test authoring and the author needs to see what they scripted.
        assertEquals(true, failure.message!!.contains("nonsense"))
    }

    @Test
    fun `the grammar handed to the engine is recorded`() = runTest {
        val engine = MockEngine("x")
        engine.generate("p", "root ::= \"x\"", SamplingParams())
        assertEquals(listOf("root ::= \"x\""), engine.grammars)
    }

    @Test
    fun `token counts fall back to the tokenizer when unscripted`() = runTest {
        val engine = MockEngine(
            script = listOf(MockResponse("abcdefgh")),
            tokenizer = { it.length / 4 },
        )
        val result = engine.generate("12345678", "g", SamplingParams())
        assertEquals(2, result.promptTokens)
        assertEquals(2, result.outputTokens)
    }

    @Test
    fun `tokens per second is derived from decode time`() {
        val result = GenerationResult(
            text = "x",
            promptTokens = 100,
            outputTokens = 50,
            prefillMillis = 500,
            decodeMillis = 2000,
        )
        assertEquals(25.0, result.tokensPerSecond)
    }

    @Test
    fun `zero decode time does not divide by zero`() {
        val result = GenerationResult("x", 1, 1, 0, 0)
        assertEquals(0.0, result.tokensPerSecond)
    }
}
