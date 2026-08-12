package dev.droiddoodle.suite

import dev.droiddoodle.agent.Outcome
import dev.droiddoodle.agent.ReferenceTable
import dev.droiddoodle.model.EdgeType
import dev.droiddoodle.model.NodeType
import dev.droiddoodle.model.SettingKeys
import dev.droiddoodle.model.SettingsSnapshot

/**
 * The Prompt Suite of docs/31-prompt-suite.md, as data.
 *
 * This lives in a main source set, not a test one, because the suite is the
 * project's measurement instrument rather than a test of it. P10 has to run
 * these same cases against a real model on a real device, and a second copy of
 * the list in the app would drift from this one -- the same argument that keeps
 * the grammar generated from the tool schemas.
 *
 * Each case carries both a canned plan and its assertions. RUNTIME mode feeds
 * the canned plan to MockEngine and checks the runtime executed it correctly.
 * MODEL mode ignores the plan entirely, asks a real model, and checks the same
 * assertions. A case that passes in RUNTIME mode says nothing whatsoever about
 * any model; conflating the two would make every later measurement meaningless.
 */
public object PromptSuite {

    /** Category prefix of a case id, used for per-category pass rates in P10. */
    public fun categoryOf(id: String): String = id.substringBefore('-')

    public val ALL: List<SuiteCase> = listOf(
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
        SuiteCase(
            id = "create-02",
            board = EMPTY,
            message = "make a note that says grappling hook",
            plans = listOf(plan(create("NOTE", "grappling hook"))),
            assertions = listOf(nodeExists("grappling hook", NodeType.NOTE), nodeCount(1)),
        ),
        SuiteCase(
            id = "create-03",
            board = VILLAGE,
            message = "add a castle",
            plans = listOf(plan(create("PLACE", "Castle"))),
            assertions = listOf(nodeCount(4), nodeExists("Castle")),
        ),
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
        SuiteCase(
            id = "multi-04",
            board = EMPTY,
            message = "create five frogs",
            plans = listOf(
                plan(*(1..5).map { create("CHARACTER", "Frog $it", ""","kind":"frog"""") }.toTypedArray()),
            ),
            assertions = listOf(outcomeIs(Outcome.OK), nodeCount(5)),
        ),
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
        SuiteCase(
            id = "modify-02",
            board = VILLAGE,
            message = "rename the tavern to The Rusty Anchor",
            plans = listOf(
                plan("""{"tool":"update_node","args":{"node":"n2","label":"The Rusty Anchor"}}"""),
            ),
            assertions = listOf(nodeExists("The Rusty Anchor"), nodeAbsent("Tavern"), nodeCount(3)),
        ),
        SuiteCase(
            id = "modify-03",
            board = VILLAGE,
            message = "make the village blue",
            plans = listOf(plan("""{"tool":"update_node","args":{"node":"n1","color":"BLUE"}}""")),
            assertions = listOf(outcomeIs(Outcome.OK), nodeCount(3)),
        ),
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
        SuiteCase(
            id = "move-03",
            board = VILLAGE,
            message = "move the tavern west of the village",
            plans = listOf(
                plan("""{"tool":"move_node","args":{"node":"n2","to":{"rel":"WEST_OF","ref":"n1"}}}"""),
            ),
            assertions = listOf(westOf("Tavern", "Village")),
        ),
        SuiteCase(
            id = "move-04",
            board = CROWDED,
            message = "move the tavern north of the village",
            plans = listOf(
                plan("""{"tool":"move_node","args":{"node":"n2","to":{"rel":"NORTH_OF","ref":"n1"}}}"""),
            ),
            assertions = listOf(northOf("Tavern", "Village")),
        ),
        SuiteCase(
            id = "connect-02",
            board = VILLAGE,
            message = "connect the tavern to the blacksmith",
            plans = listOf(
                plan("""{"tool":"connect","args":{"from":"n2","to":"n3","relation":"CONNECTS"}}"""),
            ),
            assertions = listOf(edgeExists("Tavern", "Borin", EdgeType.CONNECTS)),
        ),
        SuiteCase(
            id = "connect-03",
            board = VILLAGE,
            message = "the blacksmith owns the tavern",
            plans = listOf(
                plan("""{"tool":"connect","args":{"from":"n3","to":"n2","relation":"OWNS"}}"""),
            ),
            assertions = listOf(edgeExists("Borin", "Tavern", EdgeType.OWNS)),
        ),
        SuiteCase(
            id = "connect-01",
            board = VILLAGE,
            message = "the blacksmith is afraid of frogs",
            plans = listOf(
                plan("""{"tool":"update_node","args":{"node":"n3","set":{"afraid_of":"frogs"}}}"""),
            ),
            assertions = listOf(attrEquals("Borin", "afraid_of", "frogs")),
        ),
        SuiteCase(
            id = "delete-01",
            board = VILLAGE,
            message = "delete the tavern",
            plans = listOf(plan("""{"tool":"delete_node","args":{"node":"n2"}}""")),
            assertions = listOf(outcomeIs(Outcome.OK), nodeAbsent("Tavern"), nodeCount(2)),
        ),
        SuiteCase(
            id = "delete-02",
            board = VILLAGE,
            message = "delete the village",
            plans = listOf(plan("""{"tool":"delete_node","args":{"node":"n1"}}""")),
            // The container rule fires regardless of count: deleting a
            // container has the blast radius users least expect.
            assertions = listOf(confirmationRequested(3), nodeCount(3), boardUnchanged()),
        ),
        SuiteCase(
            id = "delete-02b",
            board = VILLAGE,
            message = "delete the village",
            plans = listOf(plan("""{"tool":"delete_node","args":{"node":"n1"}}""")),
            confirmationGranted = true,
            assertions = listOf(outcomeIs(Outcome.OK), nodeCount(0)),
        ),
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
        SuiteCase(
            id = "anaph-01",
            board = board,
            message = "make it red",
            refs = ReferenceTable(lastCreated = board.idOf("Castle")),
            plans = listOf(plan("""{"tool":"update_node","args":{"node":"n4","color":"RED"}}""")),
            assertions = listOf(outcomeIs(Outcome.OK), nodeCount(4)),
        ),
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
        SuiteCase(
            id = "arrange-01",
            board = BOARD_20,
            message = "line up the characters in a row",
            plans = listOf(
                plan("""{"tool":"arrange","args":{"nodes":["n3","n4"],"layout":"ROW"}}"""),
            ),
            assertions = listOf(outcomeIs(Outcome.OK), sameRow("Borin", "Dragon")),
        ),
        SuiteCase(
            id = "arrange-02",
            board = VILLAGE.put("Idea A", 0, 5).put("Idea B", 0, 6),
            message = "put the important ones on the left",
            plans = listOf(
                plan("""{"tool":"arrange","args":{"nodes":["n4","n5"],"layout":"CLUSTER_LEFT"}}"""),
            ),
            assertions = listOf(outcomeIs(Outcome.OK), westOf("Idea A", "Village")),
        ),
        SuiteCase(
            id = "setting-01",
            board = VILLAGE,
            message = "make yourself more creative",
            plans = listOf(
                plan("""{"tool":"set_setting","args":{"key":"model.temperature","value":"0.9"}}"""),
            ),
            assertions = listOf(outcomeIs(Outcome.OK), settingWritten("model.temperature", "0.9")),
        ),
        SuiteCase(
            id = "setting-02",
            board = VILLAGE,
            message = "stop asking me before deleting things",
            plans = listOf(
                plan("""{"tool":"set_setting","args":{"key":"agent.confirm_threshold","value":"20"}}"""),
            ),
            assertions = listOf(settingWritten("agent.confirm_threshold", "20")),
        ),
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
        SuiteCase(
            id = "fail-02",
            board = VILLAGE,
            message = "put the village inside the tavern",
            plans = listOf(
                plan("""{"tool":"connect","args":{"from":"n2","to":"n1","relation":"CONTAINS"}}"""),
            ),
            assertions = listOf(outcomeIs(Outcome.REJECTED), boardUnchanged()),
        ),
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
        SuiteCase(
        id = "find-01",
        board = BOARD_20,
        message = "make the dragon angry",
        plans = listOf(
            plan("""{"tool":"find","args":{"text":"dragon"}}"""),
            plan("""{"tool":"update_node","args":{"node":"n4","set":{"mood":"angry"}}}"""),
        ),
        assertions = listOf(outcomeIs(Outcome.OK), attrEquals("Dragon", "mood", "angry")),
        ),
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
        SuiteCase(
            id = "ambig-01",
            board = VILLAGE,
            message = "make it better",
            plans = listOf(
                plan("""{"tool":"respond","args":{"text":"which thing should I change?"}}"""),
            ),
            assertions = listOf(respondGiven(), boardUnchanged(), nodeCount(3)),
        ),
        SuiteCase(
            id = "ambig-02",
            board = EMPTY,
            message = "what is the capital of France",
            plans = listOf(
                plan("""{"tool":"respond","args":{"text":"I only edit this board."}}"""),
            ),
            assertions = listOf(respondGiven(), boardUnchanged(), nodeCount(0)),
        ),
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

    public val BY_ID: Map<String, SuiteCase> = ALL.associateBy { it.id }

    public val CATEGORIES: List<String> = ALL.map { categoryOf(it.id) }.distinct()
}
