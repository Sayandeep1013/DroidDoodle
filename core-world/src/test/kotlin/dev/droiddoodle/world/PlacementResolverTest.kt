package dev.droiddoodle.world

import dev.droiddoodle.model.Cell
import dev.droiddoodle.model.NodeId
import dev.droiddoodle.model.NodeRef
import dev.droiddoodle.model.Placement
import dev.droiddoodle.model.Relation
import dev.droiddoodle.model.Res
import dev.droiddoodle.model.WorldErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class PlacementResolverTest {

    private fun resolve(board: Board, placement: Placement, ignoring: NodeId? = null): Cell =
        when (val r = PlacementResolver.resolve(board, placement, ignoring)) {
            is Res.Ok -> r.value
            is Res.Err -> fail("expected a cell but got ${r.error.code}: ${r.error.message}")
        }

    private fun errorFor(board: Board, placement: Placement): WorldErrorCode =
        when (val r = PlacementResolver.resolve(board, placement)) {
            is Res.Ok -> fail("expected failure, got ${r.value}")
            is Res.Err -> r.error.code
        }

    private fun relative(board: Board, label: String, relation: Relation): Placement.Relative =
        Placement.Relative(relation, NodeRef.Existing(board.labelled(label)!!))

    // ---- absolute -------------------------------------------------------

    @Test
    fun `absolute placement returns the exact cell when free`() {
        assertEquals(Cell(3, -2), resolve(Board.EMPTY, Placement.Absolute(Cell(3, -2))))
    }

    @Test
    fun `absolute placement never searches for an alternative`() {
        val board = villageBoard()
        assertEquals(
            WorldErrorCode.CELL_OCCUPIED,
            errorFor(board, Placement.Absolute(Cell(0, 0))),
        )
    }

    @Test
    fun `absolute placement rejects out of bounds cells`() {
        assertEquals(
            WorldErrorCode.OUT_OF_BOUNDS,
            errorFor(Board.EMPTY, Placement.Absolute(Cell(Cell.MAX + 1, 0))),
        )
    }

    @Test
    fun `absolute placement may reuse the cell of the node being moved`() {
        val board = villageBoard()
        val tavern = board.labelled("Tavern")!!
        assertEquals(
            Cell(0, 1),
            resolve(board, Placement.Absolute(Cell(0, 1)), ignoring = tavern),
        )
    }

    // ---- relative, exact ------------------------------------------------

    @Test
    fun `north of resolves to exactly one row above when free`() {
        val board = villageBoard()
        assertEquals(Cell(-1, 0), resolve(board, relative(board, "Village", Relation.NORTH_OF)))
    }

    @Test
    fun `each direction offsets along the correct axis`() {
        val board = villageBoard()
        assertEquals(Cell(-1, 0), resolve(board, relative(board, "Village", Relation.NORTH_OF)))
        assertEquals(Cell(1, 1), resolve(board, relative(board, "Tavern", Relation.SOUTH_OF)))
        assertEquals(Cell(0, -1), resolve(board, relative(board, "Village", Relation.WEST_OF)))
        assertEquals(Cell(1, 1), resolve(board, relative(board, "Borin", Relation.EAST_OF)))
    }

    // ---- relative, fallback ordering ------------------------------------

    @Test
    fun `north of falls back laterally before going further north`() {
        // Candidate order for NORTH_OF from (0,0), per docs/20-world-model.md §7:
        //   (-1,0) | (-1,-1) (-1,1) | (-2,0) | (-2,-1) (-2,1) | (-3,0) …
        var board = villageBoard()
        val expected = listOf(
            Cell(-1, 0), Cell(-1, -1), Cell(-1, 1),
            Cell(-2, 0), Cell(-2, -1), Cell(-2, 1),
            Cell(-3, 0),
        )
        for ((index, cell) in expected.withIndex()) {
            val actual = resolve(board, relative(board, "Village", Relation.NORTH_OF))
            assertEquals(cell, actual, "candidate #$index")
            board = board.withNode("blocker$index", cell.row, cell.col)
        }
    }

    @Test
    fun `the directional invariant holds even when the exact cell is taken`() {
        val board = crowdedBoard()
        val village = board.cellOf("Village")
        val placed = resolve(board, relative(board, "Village", Relation.NORTH_OF))
        assertTrue(
            placed.row < village.row,
            "north_of must always place strictly north, got $placed against $village",
        )
    }

    @Test
    fun `next to prefers east then west then south then north`() {
        var board = Board.EMPTY.withNode("Anchor", 0, 0)
        val order = listOf(Cell(0, 1), Cell(0, -1), Cell(1, 0), Cell(-1, 0))
        for ((index, cell) in order.withIndex()) {
            val actual = resolve(board, relative(board, "Anchor", Relation.NEXT_TO))
            assertEquals(cell, actual, "next_to candidate #$index")
            board = board.withNode("blocker$index", cell.row, cell.col)
        }
    }

    @Test
    fun `relative placement reports an unknown reference`() {
        val placement = Placement.Relative(Relation.NORTH_OF, NodeRef.Existing(NodeId("n99")))
        assertEquals(WorldErrorCode.UNKNOWN_REF, errorFor(villageBoard(), placement))
    }

    @Test
    fun `an unresolved step reference is refused by the world layer`() {
        // The executor substitutes step refs before reaching here; seeing one is
        // a defence-in-depth failure, not an expected path.
        val placement = Placement.Relative(Relation.NORTH_OF, NodeRef.Step(1))
        assertEquals(WorldErrorCode.UNKNOWN_REF, errorFor(villageBoard(), placement))
    }

    @Test
    fun `relative placement fails when every candidate is off the board`() {
        val board = Board.EMPTY.withNode("Edge", Cell.MIN, 0)
        assertEquals(
            WorldErrorCode.NO_FREE_CELL,
            errorFor(board, relative(board, "Edge", Relation.NORTH_OF)),
        )
    }

    // ---- auto -----------------------------------------------------------

    @Test
    fun `auto placement puts the first node at the origin`() {
        assertEquals(Cell.ORIGIN, resolve(Board.EMPTY, Placement.Auto))
    }

    @Test
    fun `auto placement searches outward from the centroid`() {
        val board = Board.EMPTY.withNode("A", 0, 0).withNode("B", 2, 2)
        // Centroid of (0,0) and (2,2) is (1,1), which is free.
        assertEquals(Cell(1, 1), resolve(board, Placement.Auto))
    }

    @Test
    fun `auto placement steps to the ring when the centroid is taken`() {
        val board = Board.EMPTY.withNode("A", 0, 0).withNode("B", 2, 2).withNode("C", 1, 1)
        // Ring radius 1 around (1,1) walks clockwise from the north-west corner.
        assertEquals(Cell(0, 1), resolve(board, Placement.Auto))
    }

    // ---- ring walk ------------------------------------------------------

    @Test
    fun `ring walk is clockwise from the north west corner`() {
        assertEquals(listOf(Cell(0, 0)), PlacementResolver.ringCells(Cell(0, 0), 0))
        assertEquals(
            listOf(
                Cell(-1, -1), Cell(-1, 0), Cell(-1, 1),
                Cell(0, 1), Cell(1, 1),
                Cell(1, 0), Cell(1, -1),
                Cell(0, -1),
            ),
            PlacementResolver.ringCells(Cell(0, 0), 1),
        )
    }

    @Test
    fun `ring walk visits exactly eight times the radius cells`() {
        for (radius in 1..5) {
            val ring = PlacementResolver.ringCells(Cell(0, 0), radius)
            assertEquals(8 * radius, ring.size, "radius $radius")
            assertEquals(ring.size, ring.toSet().size, "radius $radius has duplicates")
            assertTrue(ring.all { it.chebyshevTo(Cell(0, 0)) == radius }, "radius $radius")
        }
    }
}
