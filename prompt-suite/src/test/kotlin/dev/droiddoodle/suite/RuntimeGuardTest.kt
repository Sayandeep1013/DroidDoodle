package dev.droiddoodle.suite

import dev.droiddoodle.agent.Outcome
import dev.droiddoodle.agent.ReferenceTable
import dev.droiddoodle.model.NodeType
import dev.droiddoodle.model.SettingKeys
import dev.droiddoodle.model.SettingsSnapshot
import dev.droiddoodle.world.BoardOps
import dev.droiddoodle.world.History
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runtime guarantees that are not Prompt Suite cases: prompt determinism, the
 * three-inference ceiling, digest omission rules, and that every turn is
 * traced. The suite cases themselves live in `PromptSuite` and are executed by
 * `PromptSuiteTest`.
 *
 * This proves the runtime executes correct plans correctly. It proves **nothing
 * whatsoever** about the model -- that is MODEL mode, which needs a device.
 * Conflating the two would make every later measurement meaningless.
 *
 * RUNTIME mode is a hard gate: a failure here is a runtime bug.
 * See docs/31-prompt-suite.md.
 */
class RuntimeGuardTest {

    @Test
    fun `the reference table appears in the prompt and names the right node`() = runTest {
        // The mechanism itself, not just its effect: the model must be told what
        // "it" refers to, because resolving anaphora is the runtime's job.
        val board = VILLAGE.put("Castle", 2, 2)
        val case = SuiteCase(
            id = "refs-render",
            board = board,
            message = "make it red",
            refs = ReferenceTable(lastCreated = board.idOf("Castle"), selected = board.idOf("Borin")),
            plans = listOf(plan("""{"tool":"update_node","args":{"node":"n4","color":"RED"}}""")),
            assertions = listOf(outcomeIs(Outcome.OK)),
        )
        val outcome = SuiteRunner.run(case)
        val prompt = outcome.result.trace.rounds.first().prompt
        assertEquals(true, prompt.contains("refs: last_created=n4 selected=n3"), prompt)
    }

    @Test
    fun `anaph-04 undo restores the board exactly (intent P4)`() {
        // Undo is a History operation rather than a tool, so it is asserted
        // directly rather than through a scripted plan.
        val before = VILLAGE
        val after = when (val r = BoardOps.addNode(before, NodeType.PLACE, "Castle")) {
            is dev.droiddoodle.model.Res.Ok -> r.value.board
            is dev.droiddoodle.model.Res.Err -> error("fixture failed")
        }
        val restored = History().record(before).undo(after)!!
        assertEquals(before, restored.board)
    }

    @Test
    fun `a respond-only turn produces no board change to undo`() = runTest {
        val outcome = SuiteRunner.run(
            SuiteCase(
                id = "guard-noundo",
                board = VILLAGE,
                message = "hello",
                plans = listOf(plan("""{"tool":"respond","args":{"text":"hi"}}""")),
                assertions = emptyList(),
            ),
        )
        assertEquals(true, outcome.result.diff.isEmpty())
        assertEquals(false, outcome.result.mutatedBoard)
    }

    @Test
    fun `every turn produces a trace, including rejected ones`() = runTest {
        val outcome = SuiteRunner.run(
            SuiteCase(
                id = "guard-trace",
                board = VILLAGE,
                message = "break something",
                plans = listOf(
                    plan("""{"tool":"connect","args":{"from":"n2","to":"n1","relation":"CONTAINS"}}"""),
                ),
                assertions = emptyList(),
            ),
        )
        val trace = outcome.result.trace
        assertEquals(Outcome.REJECTED, trace.outcome)
        assertEquals(1, trace.rounds.size)
        assertEquals(true, trace.rounds.first().prompt.isNotBlank())
        assertEquals(true, trace.rounds.first().blockTokens.isNotEmpty())
    }

    @Test
    fun `identical inputs produce a byte-identical prompt`() = runTest {
        // Determinism is what lets this suite be ordinary assertions rather than
        // a flaky integration test.
        fun case() = SuiteCase(
            id = "guard-determinism",
            board = VILLAGE,
            message = "add a castle",
            plans = listOf(plan(create("PLACE", "Castle"))),
            assertions = emptyList(),
        )
        val a = SuiteRunner.run(case()).result.trace.rounds.first().prompt
        val b = SuiteRunner.run(case()).result.trace.rounds.first().prompt
        assertEquals(a, b)
    }

    @Test
    fun `at most three inferences are ever issued for one message`() = runTest {
        // The ceiling is a hard property of the strategy: initial, one retrieval
        // re-plan, one repair.
        val outcome = SuiteRunner.run(
            SuiteCase(
                id = "guard-ceiling",
                board = BOARD_20,
                message = "find and fix",
                settings = SettingsSnapshot(mapOf(SettingKeys.AGENT_AUTO_REPAIR to "true")),
                plans = listOf(
                    plan("""{"tool":"find","args":{"text":"dragon"}}"""),
                    plan("""{"tool":"move_node","args":{"node":"n4","to":{"cell":{"row":0,"col":0}}}}"""),
                    plan("""{"tool":"move_node","args":{"node":"n4","to":{"cell":{"row":-5,"col":-5}}}}"""),
                ),
                assertions = emptyList(),
            ),
        )
        assertEquals(3, outcome.result.trace.rounds.size)
        assertEquals(1, outcome.result.trace.retrievalRounds)
        assertEquals(1, outcome.result.trace.repairRounds)
    }

    @Test
    fun `the digest omits blank kind, empty attributes and default colour`() = runTest {
        val outcome = SuiteRunner.run(
            SuiteCase(
                id = "guard-digest",
                board = VILLAGE,
                message = "look",
                plans = listOf(plan("""{"tool":"respond","args":{"text":"ok"}}""")),
                assertions = emptyList(),
            ),
        )
        val prompt = outcome.result.trace.rounds.first().prompt
        assertEquals(true, prompt.contains("""n1 place "Village" @0,0"""), prompt)
        assertEquals(true, prompt.contains("""n3 char "Borin" ~blacksmith @1,0"""), prompt)
        assertEquals(false, prompt.contains("#default"), "default colour must be omitted")
        assertEquals(true, prompt.contains("n1>n2 contains"), prompt)
    }
}
