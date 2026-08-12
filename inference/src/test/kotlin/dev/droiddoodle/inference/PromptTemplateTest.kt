package dev.droiddoodle.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptTemplateTest {

    @Test
    fun `every template ends by opening the assistant turn`() {
        // If a template ended by closing a turn instead, the model would be
        // asked to start a fresh user turn and the grammar would fight it.
        assertTrue(PromptTemplate.CHATML.wrap("x").endsWith("<|im_start|>assistant\n"))
        assertTrue(PromptTemplate.GEMMA.wrap("x").endsWith("<start_of_turn>model\n"))
        assertTrue(PromptTemplate.LLAMA3.wrap("x").endsWith("assistant<|end_header_id|>\n\n"))
    }

    @Test
    fun `every template contains the prompt exactly once`() {
        for (template in PromptTemplate.entries) {
            val wrapped = template.wrap(MARKER)
            assertEquals(
                1,
                wrapped.split(MARKER).size - 1,
                "$template repeated or dropped the prompt",
            )
        }
    }

    @Test
    fun `no template writes a BOS token into the text`() {
        // Tokenisation uses add_special = true, so a literal BOS here would be
        // a second one. See the class comment.
        for (template in PromptTemplate.entries) {
            val wrapped = template.wrap(MARKER)
            assertFalse(wrapped.contains("<bos>"), "$template emitted a literal BOS")
            assertFalse(wrapped.contains("<|begin_of_text|>"), "$template emitted a literal BOS")
        }
    }

    @Test
    fun `plain is the identity`() {
        assertEquals(MARKER, PromptTemplate.PLAIN.wrap(MARKER))
        assertEquals("", PromptTemplate.PLAIN.envelope())
    }

    @Test
    fun `envelope is the wrapper with nothing in it`() {
        for (template in PromptTemplate.entries) {
            assertEquals(template.wrap(""), template.envelope())
        }
    }

    @Test
    fun `keys round trip and unknown keys are rejected`() {
        for (template in PromptTemplate.entries) {
            assertEquals(template, PromptTemplate.fromKey(template.key))
        }
        assertNull(PromptTemplate.fromKey("mistral"))
        assertNull(PromptTemplate.fromKey(""))
    }

    private companion object {
        const val MARKER = "ASSEMBLED_CONTEXT"
    }
}
