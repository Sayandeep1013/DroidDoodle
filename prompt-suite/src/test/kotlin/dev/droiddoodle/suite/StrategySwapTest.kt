package dev.droiddoodle.suite

import dev.droiddoodle.agent.PlanThenExecuteStrategy
import dev.droiddoodle.agent.SingleShotStrategy
import dev.droiddoodle.agent.StrategyRegistry
import dev.droiddoodle.inference.MockEngine
import dev.droiddoodle.inference.MockResponse
import dev.droiddoodle.model.Clock
import dev.droiddoodle.model.IdGenerator
import dev.droiddoodle.agent.ToolRegistry
import dev.droiddoodle.agent.TurnDeps
import dev.droiddoodle.agent.TurnRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Intent criterion L2: the loop strategy is swappable.
 *
 * The criterion was previously recorded as "unproven until a second strategy
 * exists", because two of the three registered strategies threw
 * `NotImplementedError`. This is the evidence that replaces that note.
 *
 * What it establishes: the same request, through the same interface, produces
 * observably different behaviour and a different `strategyId` in the trace.
 * What it does not establish: that the interface would accommodate a loop
 * shaped unlike plan-then-execute. `ReActStrategy` is still a stub, and that
 * question stays open until it is not.
 */
class StrategySwapTest {

    private fun deps(vararg plans: String) = TurnDeps(
        engine = MockEngine(script = plans.map { MockResponse(it) }),
        registry = ToolRegistry(),
        clock = Clock.fixed(),
        turnIds = IdGenerator.sequential(),
    )

    private fun request() = TurnRequest(
        userMessage = "find and move the dragon",
        board = BOARD_20,
    )

    private val findThenMove = arrayOf(
        plan("""{"tool":"find","args":{"text":"dragon"}}"""),
        plan("""{"tool":"move_node","args":{"node":"n4","to":{"cell":{"row":0,"col":3}}}}"""),
    )

    @Test
    fun `plan_then_execute spends a second inference on retrieval`() = runTest {
        val result = PlanThenExecuteStrategy().run(request(), deps(*findThenMove))
        assertEquals(2, result.trace.rounds.size)
        assertEquals(1, result.trace.retrievalRounds)
        assertEquals("plan_then_execute", result.trace.strategyId)
    }

    @Test
    fun `single_shot refuses the retrieval round instead`() = runTest {
        // Same request, same canned plans, one inference. The find-first plan is
        // refused rather than re-planned, which is the whole difference.
        val result = SingleShotStrategy().run(request(), deps(*findThenMove))
        assertEquals(1, result.trace.rounds.size)
        assertEquals(0, result.trace.retrievalRounds)
        assertEquals("single_shot", result.trace.strategyId)
        assertEquals("RETRIEVAL_EXHAUSTED", result.failure?.code)
        assertTrue(result.diff.isEmpty(), "a refused turn must not touch the board")
    }

    @Test
    fun `the registry resolves every id the setting offers`() {
        // The enum in docs/26-settings.md and the registry must not disagree:
        // an unresolvable id would fall through to the default and silently run
        // a strategy the trace then misreports.
        assertEquals("plan_then_execute", StrategyRegistry.create("plan_then_execute").id)
        assertEquals("single_shot", StrategyRegistry.create("single_shot").id)
        assertEquals("react", StrategyRegistry.create("react").id)
    }

    @Test
    fun `an unknown id falls back to the default rather than crashing`() {
        assertEquals("plan_then_execute", StrategyRegistry.create("nonsense").id)
    }
}
