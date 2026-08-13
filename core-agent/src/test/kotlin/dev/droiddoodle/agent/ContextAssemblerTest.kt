package dev.droiddoodle.agent

import dev.droiddoodle.model.Res
import dev.droiddoodle.model.ToolCatalog
import dev.droiddoodle.world.Board
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression coverage for the worked examples and closed-vocabulary hints
 * added to the prompt after the first two on-device Prompt Suite runs (both
 * 20% pass) showed a 1B model reliably guessing the wrong JSON shape or the
 * wrong enum spelling when neither was ever demonstrated. See
 * docs/22-context.md §2-3 and results/README.md.
 *
 * These assertions exist so a future edit to `SYSTEM_RULES` or `ToolCatalog`
 * cannot silently drop the fix -- the Prompt Suite itself only measures that
 * on a device, and this module has none.
 */
class ContextAssemblerTest {

    private val assembler = ContextAssembler(ToolCatalog.ALL) { it.length / 4 }

    private fun assembledText(message: String = "create a village"): String {
        val request = TurnRequest(userMessage = message, board = Board.EMPTY)
        return when (val result = assembler.assemble(request, maxContextTokens = 4096)) {
            is Res.Ok -> result.value.text
            is Res.Err -> error("assembly failed: ${result.error}")
        }
    }

    @Test
    fun `system rules demonstrate relative placement with the grammar's own casing`() {
        val text = assembledText()
        assertContains(text, "\"rel\":\"NEXT_TO\"")
        assertContains(text, "NORTH_OF")
        assertFalse(
            "say north_of" in text,
            "must not regress to prose that names the relation in the wrong case",
        )
    }

    @Test
    fun `system rules demonstrate the attribute map as a flat fact, not a name-value pair`() {
        val text = assembledText()
        assertContains(text, "\"set\":{\"secret\":\"vampire\"}")
        assertFalse(
            "\"attribute\":\"secret\"" in text,
            "must not resemble find's flat attribute=value convention",
        )
    }

    @Test
    fun `system rules demonstrate a multi-step plan chained with a step reference`() {
        val text = assembledText()
        assertContains(text, "\"ref\":\"\$1\"")
        assertContains(text, "\"to\":\"\$2\"")
    }

    @Test
    fun `system rules demonstrate respond for an out-of-scope request`() {
        val text = assembledText()
        assertContains(text, "\"tool\":\"respond\"")
    }

    @Test
    fun `tool block spells out closed vocabularies a model cannot infer from prose`() {
        val text = assembledText()
        assertContains(text, "PLACE, CHARACTER, OBJECT, NOTE, or GROUP")
        assertContains(text, "CONTAINS, CONNECTS, KNOWS, FEARS, OWNS, BLOCKS, or CUSTOM")
        assertContains(text, "ROW, COLUMN, GRID, CLUSTER_LEFT, or CLUSTER_RIGHT")
        assertContains(text, "model.temperature")
        assertContains(text, "agent.confirm_threshold")
    }

    @Test
    fun `assembly is still a pure deterministic function of its inputs`() {
        assertEquals(assembledText(), assembledText())
    }

    @Test
    fun `static blocks still leave comfortable headroom under the context budget`() {
        val request = TurnRequest(userMessage = "create a village", board = Board.EMPTY)
        val prompt = when (val result = assembler.assemble(request, maxContextTokens = 4096)) {
            is Res.Ok -> result.value
            is Res.Err -> error("assembly failed: ${result.error}")
        }
        // docs/22-context.md targets <=1200 tokens for a typical turn. This is
        // not a tight regression pin -- it is a guard against the worked
        // examples and vocabulary hints silently ballooning past that budget
        // on the cheapest possible turn (an empty board).
        assertTrue(
            prompt.totalTokens < 950,
            "static blocks now cost ${prompt.totalTokens} tokens on an empty board; " +
                "re-check docs/22-context.md's budget before raising this bound",
        )
    }
}
