package dev.droiddoodle.grammar

import dev.droiddoodle.model.NodeId
import dev.droiddoodle.model.SettingsRegistry
import dev.droiddoodle.model.ToolCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GrammarBuilderTest {

    private val agentKeys = SettingsRegistry.AGENT_WRITABLE.map { it.key }

    private fun spec(ids: List<String>, maxSteps: Int = 8) = GrammarSpec(
        tools = ToolCatalog.ALL,
        existingIds = ids.map { NodeId(it) },
        maxSteps = maxSteps,
        agentWritableSettingKeys = agentKeys,
    )

    private fun ruleNames(grammar: String): Set<String> =
        grammar.lineSequence().filter { it.contains("::=") }.map { it.substringBefore(" ::=") }.toSet()

    private fun ruleBody(grammar: String, name: String): String =
        grammar.lineSequence().first { it.startsWith("$name ::=") }.substringAfter("::=").trim()

    @Test
    fun `every tool gets a rule and the core rules are present`() {
        val grammar = GrammarBuilder.build(spec(listOf("n1", "n2")))
        val names = ruleNames(grammar)
        assertTrue("root" in names)
        assertTrue("first" in names)
        assertTrue("step" in names)
        for (tool in ToolCatalog.ALL) {
            assertTrue("tool-" + tool.name.replace('_', '-') in names, "missing rule for ${tool.name}")
        }
    }

    @Test
    fun `respond may stand alone and may only be last`() {
        val root = ruleBody(GrammarBuilder.build(spec(listOf("n1"))), "root")
        // The leading alternative is respond by itself -- the correct output for
        // a question or a refusal, where the right number of mutations is zero.
        assertTrue(root.contains("tool-respond |"), "respond-only branch missing from: $root")
        assertTrue(root.contains("tool-respond )?"), "trailing respond branch missing from: $root")
        assertFalse(ruleBody(GrammarBuilder.build(spec(listOf("n1"))), "step").contains("tool-respond"))
    }

    @Test
    fun `find may only be first`() {
        val grammar = GrammarBuilder.build(spec(listOf("n1")))
        assertTrue(ruleBody(grammar, "first").contains("tool-find"))
        assertFalse(ruleBody(grammar, "step").contains("tool-find"))
    }

    @Test
    fun `node ids are enumerated so they cannot be hallucinated`() {
        val grammar = GrammarBuilder.build(spec(listOf("n1", "n7", "n2")))
        val existing = ruleBody(grammar, "existing")
        assertTrue(existing.contains("\\\"n1\\\"") || existing.contains("n1"), existing)
        assertTrue(existing.contains("n7"))
        assertFalse(existing.contains("n99"))
    }

    @Test
    fun `an empty board omits the existing rule entirely`() {
        val grammar = GrammarBuilder.build(spec(emptyList()))
        assertFalse("existing" in ruleNames(grammar))
        assertTrue(ruleBody(grammar, "noderef").contains("stepref"))
        assertFalse(ruleBody(grammar, "noderef").contains("existing"))
    }

    @Test
    fun `step references span one to max steps minus one`() {
        val body = ruleBody(GrammarBuilder.build(spec(emptyList(), maxSteps = 4)), "stepref")
        assertTrue(body.contains("\$1"))
        assertTrue(body.contains("\$3"))
        assertFalse(body.contains("\$4"))
    }

    @Test
    fun `the setting key domain excludes protected keys`() {
        val body = ruleBody(GrammarBuilder.build(spec(listOf("n1"))), "settingkey")
        assertTrue(body.contains("model.temperature"))
        assertTrue(body.contains("agent.confirm_threshold"))
        // An agent able to disable its own observability defeats the project's
        // purpose, so trace.* is absent from the grammar, not merely rejected.
        assertFalse(body.contains("trace.enabled"))
        assertFalse(body.contains("model.id"))
        assertFalse(body.contains("agent.loop_strategy"))
    }

    @Test
    fun `find requires at least one argument at the grammar level`() {
        val body = ruleBody(GrammarBuilder.build(spec(listOf("n1"))), "tool-find")
        // Suffix expansion: one branch per possible first-present argument, and
        // no empty branch.
        assertTrue(body.contains("|"), "expected alternatives in: $body")
        assertTrue(body.contains("text"))
        assertTrue(body.contains("attribute"))
    }

    @Test
    fun `renaming a tool argument changes the grammar`() {
        // This is the drift detector behind intent criterion L5: a grammar that
        // did not change when its schema did would be silently out of date.
        val base = GrammarBuilder.build(spec(listOf("n1")))
        val renamed = GrammarBuilder.build(
            spec(listOf("n1")).copy(
                tools = ToolCatalog.ALL.map { tool ->
                    if (tool.name != ToolCatalog.MOVE_NODE) {
                        tool
                    } else {
                        tool.copy(args = tool.args.map { if (it.name == "to") it.copy(name = "dest") else it })
                    }
                },
            ),
        )
        assertTrue(base != renamed, "renaming an argument must change the grammar")
        assertTrue(ruleBody(renamed, "tool-move-node").contains("dest"))
    }

    @Test
    fun `the grammar is stable across rebuilds with identical input`() {
        assertEquals(
            GrammarBuilder.build(spec(listOf("n1", "n2", "n3"))),
            GrammarBuilder.build(spec(listOf("n1", "n2", "n3"))),
        )
    }

    @Test
    fun `id order in the spec does not affect the grammar`() {
        assertEquals(
            GrammarBuilder.build(spec(listOf("n1", "n2", "n10"))),
            GrammarBuilder.build(spec(listOf("n10", "n1", "n2"))),
        )
    }
}
