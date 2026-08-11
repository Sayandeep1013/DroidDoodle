package dev.droiddoodle.grammar

import dev.droiddoodle.model.NodeId
import dev.droiddoodle.model.Res
import dev.droiddoodle.model.SettingsRegistry
import dev.droiddoodle.model.ToolCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class PlanEnvelopeCheckerTest {

    private val checker = PlanEnvelopeChecker(
        GrammarSpec(
            tools = ToolCatalog.ALL,
            existingIds = listOf("n1", "n2", "n3").map { NodeId(it) },
            maxSteps = 8,
            agentWritableSettingKeys = SettingsRegistry.AGENT_WRITABLE.map { it.key },
        ),
    )

    private fun accept(json: String) {
        when (val r = checker.check(json)) {
            is Res.Ok -> Unit
            is Res.Err -> fail("expected acceptance but got: ${r.error}")
        }
    }

    private fun reject(json: String): String = when (val r = checker.check(json)) {
        is Res.Ok -> fail("expected rejection but the plan was accepted")
        is Res.Err -> r.error
    }

    @Test
    fun `accepts a respond only plan`() {
        accept("""{"steps":[{"tool":"respond","args":{"text":"which one did you mean?"}}]}""")
    }

    @Test
    fun `accepts a multi step plan using step references`() {
        accept(
            """
            {"steps":[
              {"tool":"create_node","args":{"type":"PLACE","label":"Village"}},
              {"tool":"create_node","args":{"type":"PLACE","label":"Tavern","at":{"rel":"NEXT_TO","ref":"${'$'}1"}}},
              {"tool":"connect","args":{"from":"${'$'}1","to":"${'$'}2","relation":"CONTAINS"}}
            ]}
            """.trimIndent(),
        )
    }

    @Test
    fun `accepts find as the first step`() {
        accept("""{"steps":[{"tool":"find","args":{"text":"dragon"}}]}""")
    }

    @Test
    fun `rejects find anywhere but first`() {
        val error = reject(
            """{"steps":[{"tool":"delete_node","args":{"node":"n1"}},{"tool":"find","args":{"text":"x"}}]}""",
        )
        assertTrue(error.contains("first step"), error)
    }

    @Test
    fun `rejects respond anywhere but last`() {
        val error = reject(
            """{"steps":[{"tool":"respond","args":{"text":"hi"}},{"tool":"delete_node","args":{"node":"n1"}}]}""",
        )
        assertTrue(error.contains("last step"), error)
    }

    @Test
    fun `rejects a node id that is not on the board`() {
        val error = reject("""{"steps":[{"tool":"delete_node","args":{"node":"n99"}}]}""")
        assertTrue(error.contains("not a node on the board"), error)
    }

    @Test
    fun `rejects a protected setting key`() {
        val error = reject(
            """{"steps":[{"tool":"set_setting","args":{"key":"trace.enabled","value":"false"}}]}""",
        )
        assertTrue(error.contains("not one of"), error)
    }

    @Test
    fun `accepts an agent writable setting key`() {
        accept("""{"steps":[{"tool":"set_setting","args":{"key":"model.temperature","value":"0.9"}}]}""")
    }

    @Test
    fun `rejects unknown tools and unknown arguments`() {
        assertTrue(reject("""{"steps":[{"tool":"explode","args":{}}]}""").contains("no tool called"))
        assertTrue(
            reject("""{"steps":[{"tool":"delete_node","args":{"node":"n1","force":"yes"}}]}""")
                .contains("unknown argument"),
        )
    }

    @Test
    fun `rejects a missing required argument`() {
        assertTrue(
            reject("""{"steps":[{"tool":"move_node","args":{"node":"n1"}}]}""")
                .contains("missing required argument 'to'"),
        )
    }

    @Test
    fun `rejects an out of domain enum value`() {
        assertTrue(
            reject("""{"steps":[{"tool":"create_node","args":{"type":"SPACESHIP","label":"x"}}]}""")
                .contains("not one of"),
        )
    }

    @Test
    fun `rejects an argument-less find`() {
        assertTrue(
            reject("""{"steps":[{"tool":"find","args":{}}]}""").contains("at least one argument"),
        )
    }

    @Test
    fun `rejects an empty plan and malformed json`() {
        assertTrue(reject("""{"steps":[]}""").contains("at least one step"))
        assertTrue(reject("""not json at all""").contains("not a valid plan envelope"))
    }

    @Test
    fun `accepts every placement shape`() {
        accept("""{"steps":[{"tool":"move_node","args":{"node":"n1","to":{"auto":true}}}]}""")
        accept("""{"steps":[{"tool":"move_node","args":{"node":"n1","to":{"cell":{"row":-3,"col":2}}}}]}""")
        accept("""{"steps":[{"tool":"move_node","args":{"node":"n1","to":{"rel":"NORTH_OF","ref":"n2"}}}]}""")
    }

    @Test
    fun `rejects a placement referencing a nonexistent node`() {
        assertTrue(
            reject("""{"steps":[{"tool":"move_node","args":{"node":"n1","to":{"rel":"NORTH_OF","ref":"n42"}}}]}""")
                .contains("not a node on the board"),
        )
    }

    @Test
    fun `step count is not checked here because the grammar cannot count`() {
        // max_steps is a static validation concern in :core-agent. The checker
        // is grammar-equivalent, so it must not enforce what GBNF cannot.
        val many = (1..20).joinToString(",") {
            """{"tool":"create_node","args":{"type":"NOTE","label":"n$it"}}"""
        }
        assertEquals(true, checker.check("""{"steps":[$many]}""").isOk)
    }
}
