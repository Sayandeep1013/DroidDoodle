package dev.droiddoodle.agent

import dev.droiddoodle.model.EdgeType
import dev.droiddoodle.model.NodeType
import dev.droiddoodle.model.SettingKeys
import dev.droiddoodle.model.SettingsSnapshot
import dev.droiddoodle.world.BoardOps
import dev.droiddoodle.world.History
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Prompt Suite in RUNTIME mode: `MockEngine` plays a correct plan and the
 * runtime must execute it correctly.
 *
 * This proves the runtime executes correct plans correctly. It proves **nothing
 * whatsoever** about the model -- that is MODEL mode, which needs a device.
 * Conflating the two would make every later measurement meaningless.
 *
 * RUNTIME mode is a hard gate: a failure here is a runtime bug.
 * See docs/31-prompt-suite.md.
 */
class PromptSuiteTest {

    private fun plan(vararg steps: String) = """{"steps":[${steps.joinToString(",")}]}"""

    private fun create(type: String, label: String, extra: String = "") =
        """{"tool":"create_node","args":{"type":"$type","label":"$label"$extra}}"""

    private suspend fun check(case: SuiteCase) = SuiteRunner.verify(case, SuiteRunner.run(case))

    // ---- create -----------------------------------------------------------

    @Test
    fun `create-01 creates a single node on an empty board`() = runTest {
        check(
            SuiteCase(
                id = "create-01",
                board = EMPTY,
                message = "create a village",
                plans = listOf(plan(create("PLACE", "Village"))),
                assertions = listOf(
                    outcomeIs(Outcome.OK),
                    nodeCount(1),
                    nodeExists("Village", NodeType.PLACE),
                ),
            ),
        )
    }

    @Test
    fun `create-02 creates a note`() = runTest {
        check(
            SuiteCase(
                id = "create-02",
                board = EMPTY,
                message = "make a note that says grappling hook",
                plans = listOf(plan(create("NOTE", "grappling hook"))),
                assertions = listOf(nodeExists("grappling hook", NodeType.NOTE), nodeCount(1)),
            ),
        )
    }

    @Test
    fun `create-03 adds to a populated board`() = runTest {
        check(
            SuiteCase(
                id = "create-03",
                board = VILLAGE,
                message = "add a castle",
                plans = listOf(plan(create("PLACE", "Castle"))),
                assertions = listOf(nodeCount(4), nodeExists("Castle")),
            ),
        )
    }

    // ---- composition (intent P1) -------------------------------------------

    @Test
    fun `multi-01 builds a village with a tavern and a blacksmith in one turn`() = runTest {
        check(
            SuiteCase(
                id = "multi-01",
                board = EMPTY,
                message = "create a village with a tavern and a blacksmith",
                plans = listOf(
                    plan(
                        create("PLACE", "Village"),
                        create("PLACE", "Tavern", ""","at":{"rel":"NEXT_TO","ref":"${'$'}1"}"""),
                        create(
                            "CHARACTER", "Borin",
                            ""","kind":"blacksmith","at":{"rel":"NEXT_TO","ref":"${'$'}1"}""",
                        ),
                        """{"tool":"connect","args":{"from":"${'$'}1","to":"${'$'}2","relation":"CONTAINS"}}""",
                        """{"tool":"connect","args":{"from":"${'$'}1","to":"${'$'}3","relation":"CONTAINS"}}""",
                    ),
                ),
                assertions = listOf(
                    outcomeIs(Outcome.OK),
                    nodeCount(3),
                    nodeExists("Village", NodeType.PLACE),
                    nodeExists("Tavern", NodeType.PLACE),
                    nodeExists("Borin", NodeType.CHARACTER, "blacksmith"),
                    edgeExists("Village", "Tavern", EdgeType.CONTAINS),
                    edgeExists("Village", "Borin", EdgeType.CONTAINS),
                ),
            ),
        )
    }

    @Test
    fun `multi-02 builds a dungeon with three rooms`() = runTest {
        check(
            SuiteCase(
                id = "multi-02",
                board = EMPTY,
                message = "make a dungeon with three rooms",
                plans = listOf(
                    plan(
                        create("PLACE", "Dungeon"),
                        create("PLACE", "Room 1"),
                        create("PLACE", "Room 2"),
                        create("PLACE", "Room 3"),
                        """{"tool":"connect","args":{"from":"${'$'}1","to":"${'$'}2","relation":"CONTAINS"}}""",
                        """{"tool":"connect","args":{"from":"${'$'}1","to":"${'$'}3","relation":"CONTAINS"}}""",
                        """{"tool":"connect","args":{"from":"${'$'}1","to":"${'$'}4","relation":"CONTAINS"}}""",
                    ),
                ),
                assertions = listOf(outcomeIs(Outcome.OK), nodeCount(4)),
            ),
        )
    }

    @Test
    fun `multi-03 places a forest north of the village`() = runTest {
        check(
            SuiteCase(
                id = "multi-03",
                board = VILLAGE,
                message = "add a forest north of the village and a river between them",
                plans = listOf(
                    plan(
                        create("PLACE", "Forest", ""","at":{"rel":"NORTH_OF","ref":"n1"}"""),
                        create("OBJECT", "River", ""","at":{"rel":"NORTH_OF","ref":"n1"}"""),
                    ),
                ),
                assertions = listOf(nodeCount(5), northOf("Forest", "Village"), northOf("River", "Village")),
            ),
        )
    }

    @Test
    fun `multi-04 creates five frogs`() = runTest {
        check(
            SuiteCase(
                id = "multi-04",
                board = EMPTY,
                message = "create five frogs",
                plans = listOf(
                    plan(*(1..5).map { create("CHARACTER", "Frog $it", ""","kind":"frog"""") }.toTypedArray()),
                ),
                assertions = listOf(outcomeIs(Outcome.OK), nodeCount(5)),
            ),
        )
    }

    // ---- modify (intent P2) -------------------------------------------------

    @Test
    fun `modify-01 makes the blacksmith secretly a vampire without creating a node`() = runTest {
        check(
            SuiteCase(
                id = "modify-01",
                board = VILLAGE,
                message = "make the blacksmith secretly a vampire",
                plans = listOf(
                    plan("""{"tool":"update_node","args":{"node":"n3","set":{"secret":"vampire"}}}"""),
                ),
                assertions = listOf(
                    outcomeIs(Outcome.OK),
                    nodeCount(3),
                    attrEquals("Borin", "secret", "vampire"),
                ),
            ),
        )
    }

    @Test
    fun `modify-02 renames the tavern`() = runTest {
        check(
            SuiteCase(
                id = "modify-02",
                board = VILLAGE,
                message = "rename the tavern to The Rusty Anchor",
                plans = listOf(
                    plan("""{"tool":"update_node","args":{"node":"n2","label":"The Rusty Anchor"}}"""),
                ),
                assertions = listOf(nodeExists("The Rusty Anchor"), nodeAbsent("Tavern"), nodeCount(3)),
            ),
        )
    }

    @Test
    fun `modify-03 recolours the village`() = runTest {
        check(
            SuiteCase(
                id = "modify-03",
                board = VILLAGE,
                message = "make the village blue",
                plans = listOf(plan("""{"tool":"update_node","args":{"node":"n1","color":"BLUE"}}""")),
                assertions = listOf(outcomeIs(Outcome.OK), nodeCount(3)),
            ),
        )
    }

    // ---- spatial (intent P3) -------------------------------------------------

    @Test
    fun `move-02 puts the castle exactly one row north of the village`() = runTest {
        check(
            SuiteCase(
                id = "move-02",
                board = VILLAGE.put("Castle", 2, 2),
                message = "put the castle north of the village",
                plans = listOf(
                    plan("""{"tool":"move_node","args":{"node":"n4","to":{"rel":"NORTH_OF","ref":"n1"}}}"""),
                ),
                // The starting board guarantees r-1c0 is free, so the exact form
                // of the guarantee applies. docs/20-world-model.md §7.
                assertions = listOf(cellEquals("Castle", -1, 0), northOf("Castle", "Village")),
            ),
        )
    }

    @Test
    fun `move-03 moves the tavern west of the village`() = runTest {
        check(
            SuiteCase(
                id = "move-03",
                board = VILLAGE,
                message = "move the tavern west of the village",
                plans = listOf(
                    plan("""{"tool":"move_node","args":{"node":"n2","to":{"rel":"WEST_OF","ref":"n1"}}}"""),
                ),
                assertions = listOf(westOf("Tavern", "Village")),
            ),
        )
    }

    @Test
    fun `move-04 preserves direction when the exact cell is taken`() = runTest {
        check(
            SuiteCase(
                id = "move-04",
                board = CROWDED,
                message = "move the tavern north of the village",
                plans = listOf(
                    plan("""{"tool":"move_node","args":{"node":"n2","to":{"rel":"NORTH_OF","ref":"n1"}}}"""),
                ),
                assertions = listOf(northOf("Tavern", "Village")),
            ),
        )
    }

    // ---- relations -----------------------------------------------------------

    @Test
    fun `connect-02 links the tavern to the blacksmith`() = runTest {
        check(
            SuiteCase(
                id = "connect-02",
                board = VILLAGE,
                message = "connect the tavern to the blacksmith",
                plans = listOf(
                    plan("""{"tool":"connect","args":{"from":"n2","to":"n3","relation":"CONNECTS"}}"""),
                ),
                assertions = listOf(edgeExists("Tavern", "Borin", EdgeType.CONNECTS)),
            ),
        )
    }

    @Test
    fun `connect-03 records ownership`() = runTest {
        check(
            SuiteCase(
                id = "connect-03",
                board = VILLAGE,
                message = "the blacksmith owns the tavern",
                plans = listOf(
                    plan("""{"tool":"connect","args":{"from":"n3","to":"n2","relation":"OWNS"}}"""),
                ),
                assertions = listOf(edgeExists("Borin", "Tavern", EdgeType.OWNS)),
            ),
        )
    }

    @Test
    fun `connect-01 records a fear`() = runTest {
        check(
            SuiteCase(
                id = "connect-01",
                board = VILLAGE,
                message = "the blacksmith is afraid of frogs",
                plans = listOf(
                    plan("""{"tool":"update_node","args":{"node":"n3","set":{"afraid_of":"frogs"}}}"""),
                ),
                assertions = listOf(attrEquals("Borin", "afraid_of", "frogs")),
            ),
        )
    }

    // ---- deletion and the confirmation gate ------------------------------------

    @Test
    fun `delete-01 removes a leaf without confirmation`() = runTest {
        check(
            SuiteCase(
                id = "delete-01",
                board = VILLAGE,
                message = "delete the tavern",
                plans = listOf(plan("""{"tool":"delete_node","args":{"node":"n2"}}""")),
                assertions = listOf(outcomeIs(Outcome.OK), nodeAbsent("Tavern"), nodeCount(2)),
            ),
        )
    }

    @Test
    fun `delete-02 asks before destroying a container`() = runTest {
        check(
            SuiteCase(
                id = "delete-02",
                board = VILLAGE,
                message = "delete the village",
                plans = listOf(plan("""{"tool":"delete_node","args":{"node":"n1"}}""")),
                // The container rule fires regardless of count: deleting a
                // container has the blast radius users least expect.
                assertions = listOf(confirmationRequested(3), nodeCount(3), boardUnchanged()),
            ),
        )
    }

    @Test
    fun `delete-02b proceeds once confirmation is granted`() = runTest {
        check(
            SuiteCase(
                id = "delete-02b",
                board = VILLAGE,
                message = "delete the village",
                plans = listOf(plan("""{"tool":"delete_node","args":{"node":"n1"}}""")),
                confirmationGranted = true,
                assertions = listOf(outcomeIs(Outcome.OK), nodeCount(0)),
            ),
        )
    }

    @Test
    fun `delete-03 asks when the count exceeds the threshold`() = runTest {
        check(
            SuiteCase(
                id = "delete-03",
                board = BOARD_20,
                message = "delete everything except the village",
                plans = listOf(
                    plan(
                        """{"tool":"delete_node","args":{"node":"n5"}}""",
                        """{"tool":"delete_node","args":{"node":"n6"}}""",
                        """{"tool":"delete_node","args":{"node":"n7"}}""",
                        """{"tool":"delete_node","args":{"node":"n8"}}""",
                    ),
                ),
                assertions = listOf(confirmationRequested(4), boardUnchanged()),
            ),
        )
    }

    // ---- reference resolution ---------------------------------------------------

    @Test
    fun `anaph-01 resolves it through the reference table`() = runTest {
        val board = VILLAGE.put("Castle", 2, 2)
        check(
            SuiteCase(
                id = "anaph-01",
                board = board,
                message = "make it red",
                refs = ReferenceTable(lastCreated = board.idOf("Castle")),
                plans = listOf(plan("""{"tool":"update_node","args":{"node":"n4","color":"RED"}}""")),
                assertions = listOf(outcomeIs(Outcome.OK), nodeCount(4)),
            ),
        )
    }

    @Test
    fun `anaph-02 resolves that for a spatial move`() = runTest {
        val board = VILLAGE.put("Castle", 2, 2)
        check(
            SuiteCase(
                id = "anaph-02",
                board = board,
                message = "move that west of the village",
                refs = ReferenceTable(lastCreated = board.idOf("Castle")),
                plans = listOf(
                    plan("""{"tool":"move_node","args":{"node":"n4","to":{"rel":"WEST_OF","ref":"n1"}}}"""),
                ),
                assertions = listOf(westOf("Castle", "Village")),
            ),
        )
    }

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

    // ---- layout -------------------------------------------------------------------

    @Test
    fun `arrange-01 lines the characters up in a row`() = runTest {
        check(
            SuiteCase(
                id = "arrange-01",
                board = BOARD_20,
                message = "line up the characters in a row",
                plans = listOf(
                    plan("""{"tool":"arrange","args":{"nodes":["n3","n4"],"layout":"ROW"}}"""),
                ),
                assertions = listOf(outcomeIs(Outcome.OK), sameRow("Borin", "Dragon")),
            ),
        )
    }

    @Test
    fun `arrange-02 clusters a set to the left`() = runTest {
        check(
            SuiteCase(
                id = "arrange-02",
                board = VILLAGE.put("Idea A", 0, 5).put("Idea B", 0, 6),
                message = "put the important ones on the left",
                plans = listOf(
                    plan("""{"tool":"arrange","args":{"nodes":["n4","n5"],"layout":"CLUSTER_LEFT"}}"""),
                ),
                assertions = listOf(outcomeIs(Outcome.OK), westOf("Idea A", "Village")),
            ),
        )
    }

    // ---- self-modification ------------------------------------------------------------

    @Test
    fun `setting-01 makes the agent more creative`() = runTest {
        check(
            SuiteCase(
                id = "setting-01",
                board = VILLAGE,
                message = "make yourself more creative",
                plans = listOf(
                    plan("""{"tool":"set_setting","args":{"key":"model.temperature","value":"0.9"}}"""),
                ),
                assertions = listOf(outcomeIs(Outcome.OK), settingWritten("model.temperature", "0.9")),
            ),
        )
    }

    @Test
    fun `setting-02 raises the confirmation threshold`() = runTest {
        check(
            SuiteCase(
                id = "setting-02",
                board = VILLAGE,
                message = "stop asking me before deleting things",
                plans = listOf(
                    plan("""{"tool":"set_setting","args":{"key":"agent.confirm_threshold","value":"20"}}"""),
                ),
                assertions = listOf(settingWritten("agent.confirm_threshold", "20")),
            ),
        )
    }

    // ---- failure handling ---------------------------------------------------------------

    @Test
    fun `fail-01 halts on an occupied cell and keeps what succeeded`() = runTest {
        check(
            SuiteCase(
                id = "fail-01",
                board = VILLAGE,
                message = "put a castle exactly where the village is",
                plans = listOf(
                    plan(
                        create("PLACE", "Keep"),
                        create("PLACE", "Castle", ""","at":{"cell":{"row":0,"col":0}}"""),
                        create("PLACE", "Never"),
                    ),
                ),
                assertions = listOf(
                    // Partial commit: the first step survives, the third is skipped.
                    outcomeIs(Outcome.PARTIAL),
                    nodeExists("Keep"),
                    nodeAbsent("Castle"),
                    nodeAbsent("Never"),
                ),
            ),
        )
    }

    @Test
    fun `fail-02 refuses a containment cycle`() = runTest {
        check(
            SuiteCase(
                id = "fail-02",
                board = VILLAGE,
                message = "put the village inside the tavern",
                plans = listOf(
                    plan("""{"tool":"connect","args":{"from":"n2","to":"n1","relation":"CONTAINS"}}"""),
                ),
                assertions = listOf(outcomeIs(Outcome.REJECTED), boardUnchanged()),
            ),
        )
    }

    @Test
    fun `fail-03 rejects a node id the grammar could never emit`() = runTest {
        check(
            SuiteCase(
                id = "fail-03",
                board = VILLAGE,
                message = "delete n99",
                plans = listOf(plan("""{"tool":"delete_node","args":{"node":"n99"}}""")),
                // Deliberately bypasses MockEngine's check to reach the runtime's
                // own phase-4 guard. Note the failure is GRAMMAR_VIOLATION rather
                // than UNKNOWN_NODE: the envelope check fires before execution,
                // so the executor is never reached. That is defence in depth
                // working as intended.
                skipOutputCheck = true,
                assertions = listOf(
                    outcomeIs(Outcome.REJECTED),
                    failureCodeIs("GRAMMAR_VIOLATION"),
                    boardUnchanged(),
                    nodeCount(3),
                ),
            ),
        )
    }

    @Test
    fun `a plan longer than max_steps is rejected whole`() = runTest {
        check(
            SuiteCase(
                id = "fail-04",
                board = EMPTY,
                message = "make a lot of things",
                settings = SettingsSnapshot(mapOf(SettingKeys.AGENT_MAX_STEPS to "3")),
                plans = listOf(
                    plan(*(1..6).map { create("NOTE", "n$it") }.toTypedArray()),
                ),
                assertions = listOf(
                    outcomeIs(Outcome.REJECTED),
                    failureCodeIs("STATIC_VALIDATION"),
                    nodeCount(0),
                ),
            ),
        )
    }

    // ---- retrieval -------------------------------------------------------------------------

    @Test
    fun `find-01 runs exactly one retrieval round then acts`() = runTest {
        val case = SuiteCase(
            id = "find-01",
            board = BOARD_20,
            message = "make the dragon angry",
            plans = listOf(
                plan("""{"tool":"find","args":{"text":"dragon"}}"""),
                plan("""{"tool":"update_node","args":{"node":"n4","set":{"mood":"angry"}}}"""),
            ),
            assertions = listOf(outcomeIs(Outcome.OK), attrEquals("Dragon", "mood", "angry")),
        )
        val outcome = SuiteRunner.run(case)
        SuiteRunner.verify(case, outcome)
        assertEquals(1, outcome.result.trace.retrievalRounds)
        assertEquals(2, outcome.result.trace.rounds.size, "exactly two inferences")
    }

    @Test
    fun `a second find in the same turn is refused`() = runTest {
        check(
            SuiteCase(
                id = "find-02",
                board = BOARD_20,
                message = "find things twice",
                plans = listOf(
                    plan("""{"tool":"find","args":{"text":"dragon"}}"""),
                    plan("""{"tool":"find","args":{"text":"dragon"}}"""),
                ),
                assertions = listOf(outcomeIs(Outcome.REJECTED), failureCodeIs("RETRIEVAL_EXHAUSTED")),
            ),
        )
    }

    // ---- ambiguity ---------------------------------------------------------------------------

    @Test
    fun `ambig-01 asks for clarification without touching the board`() = runTest {
        check(
            SuiteCase(
                id = "ambig-01",
                board = VILLAGE,
                message = "make it better",
                plans = listOf(
                    plan("""{"tool":"respond","args":{"text":"which thing should I change?"}}"""),
                ),
                assertions = listOf(respondGiven(), boardUnchanged(), nodeCount(3)),
            ),
        )
    }

    @Test
    fun `ambig-02 an out-of-world question causes no mutation`() = runTest {
        // Not a trivia test: this checks that a question the tools cannot address
        // does not produce spurious board changes, which is a small model's most
        // likely failure here.
        check(
            SuiteCase(
                id = "ambig-02",
                board = EMPTY,
                message = "what is the capital of France",
                plans = listOf(
                    plan("""{"tool":"respond","args":{"text":"I only edit this board."}}"""),
                ),
                assertions = listOf(respondGiven(), boardUnchanged(), nodeCount(0)),
            ),
        )
    }

    @Test
    fun `move-01 declines to move something that does not exist`() = runTest {
        check(
            SuiteCase(
                id = "move-01",
                board = VILLAGE,
                message = "move the castle north of the village",
                plans = listOf(
                    plan("""{"tool":"respond","args":{"text":"there is no castle yet."}}"""),
                ),
                assertions = listOf(respondGiven(), nodeCount(3), boardUnchanged()),
            ),
        )
    }

    // ---- runtime guarantees --------------------------------------------------------------------

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
