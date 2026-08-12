package dev.droiddoodle.grammar

import dev.droiddoodle.model.SettingsRegistry
import dev.droiddoodle.model.ToolCatalog
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the operator-precedence class of grammar defect.
 *
 * The first on-device run produced output the grammar was supposed to make
 * impossible — bare `"type":"OBJECT"` fragments sitting directly in the steps
 * array. The cause was an unbracketed alternation in a tool rule: GBNF binds
 * `|` looser than concatenation, so
 *
 *     tool-find ::= "{...{" ( A ) | ( B ) | ( C ) "}}"
 *
 * splits the whole rule, leaving `( B )` as a legal complete match with no
 * braces around it.
 *
 * Nothing existing could have caught this. The snapshot tests assert the
 * grammar is *stable*, not that it is *correct*, and `PlanEnvelopeChecker` is
 * schema-equivalent rather than a GBNF interpreter — so a grammar that admits
 * too much is precisely the blind spot between them. These tests sit in that
 * gap: they check the shape of what is emitted rather than what it parses.
 */
class GrammarAlternationTest {

    private fun grammarFor(existingIds: List<String>): Map<String, String> {
        val spec = GrammarSpec(
            tools = ToolCatalog.ALL,
            existingIds = existingIds.map { dev.droiddoodle.model.NodeId(it) },
            maxSteps = 8,
            agentWritableSettingKeys = SettingsRegistry.AGENT_WRITABLE.map { it.key },
        )
        return GrammarBuilder.build(spec)
            .lineSequence()
            .filter { it.contains("::=") }
            .associate { line ->
                line.substringBefore("::=").trim() to line.substringAfter("::=").trim()
            }
    }

    /**
     * Splits on `|` that is genuinely at the top level: not inside parentheses,
     * brackets, or a quoted literal.
     */
    private fun topLevelAlternatives(body: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var inQuote = false
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                inQuote && c == '\\' -> {
                    current.append(c).append(body.getOrElse(i + 1) { ' ' }); i += 2; continue
                }
                c == '"' -> { inQuote = !inQuote; current.append(c) }
                inQuote -> current.append(c)
                c == '(' || c == '[' -> { depth++; current.append(c) }
                c == ')' || c == ']' -> { depth--; current.append(c) }
                c == '|' && depth == 0 -> { parts.add(current.toString().trim()); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        parts.add(current.toString().trim())
        return parts.filter { it.isNotEmpty() }
    }

    @Test
    fun `every tool rule is a single alternative that opens with its own tool literal`() {
        // If a tool rule has top-level alternatives, at least one of them is
        // reachable without the opening brace -- which is the whole bug.
        for ((name, body) in grammarFor(listOf("n1", "n2"))) {
            if (!name.startsWith("tool-")) continue
            val alternatives = topLevelAlternatives(body)
            assertTrue(
                alternatives.size == 1,
                "$name has ${alternatives.size} top-level alternatives; an unbracketed " +
                    "alternation lets a fragment match on its own. Body: $body",
            )
            assertTrue(
                alternatives.single().startsWith("\"{\\\"tool\\\":"),
                "$name does not open with its tool literal. Body: $body",
            )
        }
    }

    @Test
    fun `the all-optional tool keeps its alternation bracketed`() {
        // `find` is the tool the suffix expansion exists for, and the only one
        // that produced a top-level alternation.
        val body = grammarFor(listOf("n1")).getValue("tool-find")
        assertTrue(body.contains("|"), "find should still offer argument alternatives")
        assertTrue(
            topLevelAlternatives(body).size == 1,
            "find's alternation escaped its brackets: $body",
        )
    }

    @Test
    fun `an empty board does not change the invariant`() {
        for ((name, body) in grammarFor(emptyList())) {
            if (!name.startsWith("tool-")) continue
            assertTrue(
                topLevelAlternatives(body).size == 1,
                "$name split into alternatives on an empty board. Body: $body",
            )
        }
    }

    @Test
    fun `the outputs from the first device run are not admitted`() {
        // Captured verbatim from results/. Each one was produced by a sampler
        // that believed it was following this grammar.
        val leaked = listOf(
            """{"steps":["type":"NOTE","attribute":"none",{"tool":"move_node","args":{"node":"${'$'}1","to":{"auto":true}}}]}""",
            """{"steps":["type":"PLACE","attribute":"city",{"tool":"update_node","args":{"node":"${'$'}1","label":"x"}}]}""",
        )
        val spec = GrammarSpec(
            tools = ToolCatalog.ALL,
            existingIds = emptyList(),
            maxSteps = 8,
            agentWritableSettingKeys = SettingsRegistry.AGENT_WRITABLE.map { it.key },
        )
        val checker = PlanEnvelopeChecker(spec)
        for (output in leaked) {
            assertTrue(
                checker.check(output).let { it !is dev.droiddoodle.model.Res.Ok },
                "the checker should reject: $output",
            )
        }
    }
}
