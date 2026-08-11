package dev.droiddoodle.world

import dev.droiddoodle.model.ArrangeLayout
import dev.droiddoodle.model.Cell
import dev.droiddoodle.model.EdgeType
import dev.droiddoodle.model.Limits
import dev.droiddoodle.model.NodeColor
import dev.droiddoodle.model.NodeRef
import dev.droiddoodle.model.NodeType
import dev.droiddoodle.model.Placement
import dev.droiddoodle.model.Relation
import dev.droiddoodle.model.WorldErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardOpsTest {

    // ---- create ---------------------------------------------------------

    @Test
    fun `creating a node allocates a monotonic id and records a delta`() {
        val change = BoardOps.addNode(Board.EMPTY, NodeType.PLACE, "Village").expectOk()
        assertEquals(1, change.board.size)
        assertEquals("n1", change.board.nodes.keys.first().value)
        assertEquals(1, change.diff.size)
        assertEquals(Cell.ORIGIN, change.board.cellOf("Village"))
    }

    @Test
    fun `ids are never reused after deletion`() {
        var board = Board.EMPTY.withNode("A", 0, 0)
        val a = board.labelled("A")!!
        board = BoardOps.removeNode(board, a).board()
        board = BoardOps.addNode(board, NodeType.PLACE, "B").board()
        assertEquals("n2", board.labelled("B")!!.value)
    }

    @Test
    fun `field limits are enforced rather than truncated`() {
        assertEquals(
            WorldErrorCode.INVALID_FIELD,
            BoardOps.addNode(Board.EMPTY, NodeType.PLACE, "  ").expectErr().code,
        )
        assertEquals(
            WorldErrorCode.INVALID_FIELD,
            BoardOps.addNode(Board.EMPTY, NodeType.PLACE, "x".repeat(Limits.LABEL_MAX + 1))
                .expectErr().code,
        )
        assertEquals(
            WorldErrorCode.INVALID_FIELD,
            BoardOps.addNode(
                Board.EMPTY, NodeType.PLACE, "ok",
                attributes = (1..Limits.ATTRS_MAX + 1).associate { "k$it" to "v" },
            ).expectErr().code,
        )
    }

    @Test
    fun `attribute keys are normalised on write`() {
        val board = BoardOps.addNode(
            Board.EMPTY, NodeType.CHARACTER, "Borin",
            attributes = mapOf("Secret Identity" to "vampire"),
        ).board()
        assertEquals("vampire", board.nodes.values.first().attributes["secret_identity"])
    }

    @Test
    fun `the board rejects creation beyond its cap`() {
        var board = Board.EMPTY
        repeat(Limits.BOARD_MAX_NODES) { board = BoardOps.addNode(board, NodeType.OBJECT, "n$it").board() }
        assertEquals(Limits.BOARD_MAX_NODES, board.size)
        assertEquals(
            WorldErrorCode.BOARD_FULL,
            BoardOps.addNode(board, NodeType.OBJECT, "one too many").expectErr().code,
        )
    }

    // ---- update ---------------------------------------------------------

    @Test
    fun `update merges attributes rather than replacing them`() {
        var board = villageBoard()
        val borin = board.labelled("Borin")!!
        board = BoardOps.updateNode(board, borin, setAttributes = mapOf("secret" to "vampire")).board()
        board = BoardOps.updateNode(board, borin, setAttributes = mapOf("afraid_of" to "frogs")).board()
        val attrs = board.node(borin)!!.attributes
        assertEquals("vampire", attrs["secret"])
        assertEquals("frogs", attrs["afraid_of"])
    }

    @Test
    fun `unset removes only the named keys`() {
        var board = villageBoard()
        val borin = board.labelled("Borin")!!
        board = BoardOps.updateNode(
            board, borin,
            setAttributes = mapOf("secret" to "vampire", "afraid_of" to "frogs"),
        ).board()
        board = BoardOps.updateNode(board, borin, unsetAttributes = listOf("secret")).board()
        val attrs = board.node(borin)!!.attributes
        assertNull(attrs["secret"])
        assertEquals("frogs", attrs["afraid_of"])
    }

    @Test
    fun `omitted arguments leave fields untouched`() {
        var board = villageBoard()
        val tavern = board.labelled("Tavern")!!
        val before = board.node(tavern)!!
        board = BoardOps.updateNode(board, tavern, color = NodeColor.BLUE).board()
        val after = board.node(tavern)!!
        assertEquals(before.label, after.label)
        assertEquals(before.kind, after.kind)
        assertEquals(before.cell, after.cell)
        assertEquals(NodeColor.BLUE, after.style.color)
    }

    @Test
    fun `a no-op update produces no delta`() {
        val board = villageBoard()
        val change = BoardOps.updateNode(board, board.labelled("Tavern")!!).expectOk()
        assertTrue(change.diff.isEmpty())
        assertEquals(board, change.board)
    }

    // ---- move -----------------------------------------------------------

    @Test
    fun `a node vacates its own cell before placement is resolved`() {
        // Tavern sits at (0,1), which is exactly east of Village at (0,0).
        // Without vacate-first, NEXT_TO would find its own cell occupied and
        // push it somewhere else.
        val board = villageBoard()
        val tavern = board.labelled("Tavern")!!
        val village = board.labelled("Village")!!
        val change = BoardOps.moveNode(
            board, tavern,
            Placement.Relative(Relation.NEXT_TO, NodeRef.Existing(village)),
        ).expectOk()
        assertEquals(Cell(0, 1), change.board.node(tavern)!!.cell)
        assertTrue(change.diff.isEmpty(), "staying put is not a change")
    }

    @Test
    fun `moving reports both endpoints in the delta`() {
        val board = villageBoard()
        val tavern = board.labelled("Tavern")!!
        val change = BoardOps.moveNode(board, tavern, Placement.Absolute(Cell(5, 5))).expectOk()
        assertEquals(Cell(0, 1), change.diff.single().before)
        assertEquals(Cell(5, 5), change.diff.single().after)
        assertNull(change.board.at(0, 1))
    }

    // ---- delete ---------------------------------------------------------

    @Test
    fun `deleting a container cascades into everything it holds`() {
        val board = villageBoard()
        val change = BoardOps.removeNode(board, board.labelled("Village")!!).expectOk()
        assertEquals(0, change.board.size)
        assertEquals(0, change.board.edges.size)
        assertEquals(3, change.diff.size)
    }

    @Test
    fun `deleting a leaf leaves its container intact`() {
        val board = villageBoard()
        val change = BoardOps.removeNode(board, board.labelled("Tavern")!!).expectOk()
        assertEquals(2, change.board.size)
        assertNotNull(change.board.labelled("Village"))
        assertEquals(1, change.board.edges.size)
    }

    @Test
    fun `the deletion footprint is what the confirmation gate counts`() {
        val board = villageBoard()
        assertEquals(3, BoardOps.deletionFootprint(board, board.labelled("Village")!!).size)
        assertEquals(1, BoardOps.deletionFootprint(board, board.labelled("Tavern")!!).size)
    }

    // ---- edges ----------------------------------------------------------

    @Test
    fun `self edges are refused`() {
        val board = villageBoard()
        val village = board.labelled("Village")!!
        assertEquals(
            WorldErrorCode.SELF_EDGE,
            BoardOps.addEdge(board, EdgeType.CONNECTS, village, village).expectErr().code,
        )
    }

    @Test
    fun `exact duplicate edges are refused`() {
        val board = villageBoard()
        assertEquals(
            WorldErrorCode.DUPLICATE_EDGE,
            BoardOps.addEdge(
                board, EdgeType.CONTAINS,
                board.labelled("Village")!!, board.labelled("Tavern")!!,
            ).expectErr().code,
        )
    }

    @Test
    fun `a reversed symmetric edge is a no-op rather than an error`() {
        var board = villageBoard()
        val tavern = board.labelled("Tavern")!!
        val borin = board.labelled("Borin")!!
        board = BoardOps.addEdge(board, EdgeType.CONNECTS, tavern, borin).board()
        val edgeCount = board.edges.size
        val change = BoardOps.addEdge(board, EdgeType.CONNECTS, borin, tavern).expectOk()
        assertEquals(edgeCount, change.board.edges.size)
        assertTrue(change.diff.isEmpty())
    }

    @Test
    fun `custom relations require a label`() {
        val board = villageBoard()
        assertEquals(
            WorldErrorCode.INVALID_FIELD,
            BoardOps.addEdge(
                board, EdgeType.CUSTOM,
                board.labelled("Tavern")!!, board.labelled("Borin")!!,
            ).expectErr().code,
        )
        assertTrue(
            BoardOps.addEdge(
                board, EdgeType.CUSTOM,
                board.labelled("Tavern")!!, board.labelled("Borin")!!, "haunts",
            ).isOk,
        )
    }

    @Test
    fun `containment cycles are refused`() {
        val board = villageBoard()
        assertEquals(
            WorldErrorCode.CONTAINMENT_CYCLE,
            BoardOps.addEdge(
                board, EdgeType.CONTAINS,
                board.labelled("Tavern")!!, board.labelled("Village")!!,
            ).expectErr().code,
        )
    }

    @Test
    fun `a node may have only one container`() {
        var board = villageBoard().withNode("Castle", 2, 2)
        assertEquals(
            WorldErrorCode.ALREADY_CONTAINED,
            BoardOps.addEdge(
                board, EdgeType.CONTAINS,
                board.labelled("Castle")!!, board.labelled("Tavern")!!,
            ).expectErr().code,
        )
    }

    @Test
    fun `containment depth is capped at four`() {
        var board = Board.EMPTY
            .withNode("A", 0, 0).withNode("B", 0, 1).withNode("C", 0, 2)
            .withNode("D", 0, 3).withNode("E", 0, 4)
            .withEdge("A", "B", EdgeType.CONTAINS)
            .withEdge("B", "C", EdgeType.CONTAINS)
            .withEdge("C", "D", EdgeType.CONTAINS)
        assertEquals(4, board.depthOf(board.labelled("D")!!))
        assertEquals(
            WorldErrorCode.CONTAINMENT_TOO_DEEP,
            BoardOps.addEdge(
                board, EdgeType.CONTAINS,
                board.labelled("D")!!, board.labelled("E")!!,
            ).expectErr().code,
        )
    }

    @Test
    fun `removing a non existent edge reports unknown edge`() {
        val board = villageBoard()
        assertEquals(
            WorldErrorCode.UNKNOWN_EDGE,
            BoardOps.removeEdge(
                board, board.labelled("Tavern")!!, board.labelled("Borin")!!,
            ).expectErr().code,
        )
    }

    // ---- arrange --------------------------------------------------------

    @Test
    fun `row layout places members in consecutive columns`() {
        val board = Board.EMPTY.withNode("A", 3, 5).withNode("B", 1, 2).withNode("C", 7, 9)
        val ids = listOf("A", "B", "C").map { board.labelled(it)!! }
        val out = BoardOps.arrange(board, ids, ArrangeLayout.ROW).board()
        assertEquals(Cell(3, 2), out.cellOf("A"))
        assertEquals(Cell(3, 3), out.cellOf("B"))
        assertEquals(Cell(3, 4), out.cellOf("C"))
    }

    @Test
    fun `grid layout is square-ish and fills row major`() {
        var board = Board.EMPTY
        repeat(5) { board = board.withNode("N$it", it, it) }
        val ids = (0 until 5).map { board.labelled("N$it")!! }
        val out = BoardOps.arrange(board, ids, ArrangeLayout.GRID).board()
        // ceil(sqrt(5)) == 3 columns wide, anchored at the set's north-west most cell.
        assertEquals(Cell(0, 0), out.cellOf("N0"))
        assertEquals(Cell(0, 1), out.cellOf("N1"))
        assertEquals(Cell(0, 2), out.cellOf("N2"))
        assertEquals(Cell(1, 0), out.cellOf("N3"))
        assertEquals(Cell(1, 1), out.cellOf("N4"))
    }

    @Test
    fun `arrange is atomic when a non member blocks a target`() {
        val board = Board.EMPTY
            .withNode("A", 0, 0)
            .withNode("B", 0, 5)
            .withNode("Blocker", 0, 1)
        val ids = listOf("A", "B").map { board.labelled(it)!! }
        val error = BoardOps.arrange(board, ids, ArrangeLayout.ROW).expectErr()
        assertEquals(WorldErrorCode.ARRANGE_BLOCKED, error.code)
    }

    @Test
    fun `arrange members never collide with each other`() {
        // Every member's current cell is inside the target span, so an
        // implementation that placed them one at a time would collide.
        var board = Board.EMPTY
        repeat(4) { board = board.withNode("N$it", 0, it) }
        val ids = (0 until 4).map { board.labelled("N$it")!! }
        val out = BoardOps.arrange(board, ids, ArrangeLayout.ROW).board()
        assertEquals(4, out.nodes.values.map { it.cell }.toSet().size)
    }

    @Test
    fun `cluster left moves the set west of everything that stays put`() {
        val board = Board.EMPTY
            .withNode("Keep", 0, 0)
            .withNode("A", 0, 3)
            .withNode("B", 0, 4)
        val ids = listOf("A", "B").map { board.labelled(it)!! }
        val out = BoardOps.arrange(board, ids, ArrangeLayout.CLUSTER_LEFT).board()
        assertTrue(out.cellOf("A").col < out.cellOf("Keep").col)
        assertTrue(out.cellOf("B").col < out.cellOf("Keep").col)
    }

    @Test
    fun `arrange refuses an oversized set`() {
        // Spread across rows: columns are bounded at Cell.MAX, so a single row
        // cannot hold this many nodes.
        var board = Board.EMPTY
        repeat(Limits.ARRANGE_MAX_NODES + 1) { board = board.withNode("N$it", it / 8, it % 8) }
        val ids = board.nodes.keys.toList()
        assertEquals(
            WorldErrorCode.INVALID_FIELD,
            BoardOps.arrange(board, ids, ArrangeLayout.ROW).expectErr().code,
        )
    }
}
