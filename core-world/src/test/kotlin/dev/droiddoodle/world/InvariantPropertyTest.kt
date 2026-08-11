package dev.droiddoodle.world

import dev.droiddoodle.model.ArrangeLayout
import dev.droiddoodle.model.Cell
import dev.droiddoodle.model.EdgeType
import dev.droiddoodle.model.NodeRef
import dev.droiddoodle.model.NodeType
import dev.droiddoodle.model.Placement
import dev.droiddoodle.model.Relation
import dev.droiddoodle.model.Res
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Property tests over random operation sequences.
 *
 * These carry real weight rather than being a nicety: constraint C2 means this
 * logic cannot be exercised by hand on the development machine, so randomised
 * invariant checking is the only thing standing between a subtle placement bug
 * and a device build.
 *
 * Seeded so failures reproduce exactly. Randomness lives only in the test;
 * production code takes none (architecture rule R4).
 */
class InvariantPropertyTest {

    private fun assertInvariants(board: Board, context: String) {
        // No two nodes share a cell.
        val cells = board.nodes.values.map { it.cell }
        assertEquals(
            cells.size, cells.toSet().size,
            "$context: two nodes occupy the same cell",
        )

        // The derived occupancy index agrees with the node map.
        assertEquals(board.nodes.size, board.occupancy.size, "$context: occupancy size")
        for ((cell, id) in board.occupancy) {
            assertEquals(cell, board.node(id)?.cell, "$context: occupancy disagrees for $id")
        }

        // Every node sits inside the addressable field.
        assertTrue(cells.all { it.inBounds }, "$context: a node is out of bounds")

        // Containment: at most one container each, and no cycles.
        for (id in board.nodes.keys) {
            val containers = board.edges.values.count { it.type == EdgeType.CONTAINS && it.to == id }
            assertTrue(containers <= 1, "$context: $id has $containers containers")
            assertTrue(
                id !in board.descendantsOf(id),
                "$context: $id transitively contains itself",
            )
            assertTrue(
                board.depthOf(id) <= dev.droiddoodle.model.Limits.CONTAINMENT_MAX_DEPTH,
                "$context: $id nested too deep",
            )
        }

        // No edge dangles.
        for (edge in board.edges.values) {
            assertTrue(board.node(edge.from) != null, "$context: ${edge.id} has a dead source")
            assertTrue(board.node(edge.to) != null, "$context: ${edge.id} has a dead target")
        }
    }

    @Test
    fun `invariants survive long random operation sequences`() {
        for (seed in 1..40) {
            val rng = Random(seed)
            var board = Board.EMPTY
            repeat(120) { step ->
                board = applyRandomOperation(board, rng, step)
                assertInvariants(board, "seed $seed step $step")
            }
        }
    }

    @Test
    fun `the same operation sequence always produces the same board`() {
        // Determinism is what lets the Prompt Suite run as ordinary assertions
        // rather than as a flaky integration test. Any hash-order dependence or
        // hidden ambient state in placement would show up here.
        for (seed in 1..10) {
            var first = Board.EMPTY
            val rngA = Random(seed)
            repeat(80) { first = applyRandomOperation(first, rngA, it) }

            var second = Board.EMPTY
            val rngB = Random(seed)
            repeat(80) { second = applyRandomOperation(second, rngB, it) }

            assertEquals(first, second, "seed $seed produced divergent boards")
        }
    }

    private fun applyRandomOperation(board: Board, rng: Random, step: Int): Board =
        when (val r = randomOperation(board, rng, step)) {
            is Res.Ok -> r.value.board
            is Res.Err -> board
        }

    private fun randomOperation(
        board: Board,
        rng: Random,
        step: Int,
    ): Res<BoardChange, dev.droiddoodle.model.WorldError> =
        when (rng.nextInt(0, 8)) {
            0, 1, 2 -> BoardOps.addNode(
                board = board,
                type = NodeType.entries[rng.nextInt(NodeType.entries.size)],
                label = "node$step",
                placement = randomPlacement(board, rng),
            )

            3 -> board.randomId(rng)?.let { BoardOps.removeNode(board, it) }
                ?: Res.Ok(BoardChange(board, emptyList()))

            4 -> board.randomId(rng)?.let { id ->
                BoardOps.moveNode(board, id, randomPlacement(board, rng))
            } ?: Res.Ok(BoardChange(board, emptyList()))

            5 -> {
                val from = board.randomId(rng)
                val to = board.randomId(rng)
                if (from != null && to != null) {
                    BoardOps.addEdge(
                        board,
                        EdgeType.entries[rng.nextInt(EdgeType.entries.size)],
                        from,
                        to,
                        label = "rel",
                    )
                } else {
                    Res.Ok(BoardChange(board, emptyList()))
                }
            }

            6 -> board.randomId(rng)?.let { id ->
                BoardOps.updateNode(
                    board, id,
                    label = "renamed$step",
                    setAttributes = mapOf("k$step" to "v"),
                )
            } ?: Res.Ok(BoardChange(board, emptyList()))

            else -> {
                val ids = board.nodes.keys.shuffled(rng).take(rng.nextInt(1, 5))
                if (ids.isEmpty()) {
                    Res.Ok(BoardChange(board, emptyList()))
                } else {
                    BoardOps.arrange(
                        board, ids,
                        ArrangeLayout.entries[rng.nextInt(ArrangeLayout.entries.size)],
                    )
                }
            }
        }

    private fun randomPlacement(board: Board, rng: Random): Placement =
        when (rng.nextInt(0, 3)) {
            0 -> Placement.Auto
            1 -> Placement.Absolute(Cell(rng.nextInt(-10, 11), rng.nextInt(-10, 11)))
            else -> board.randomId(rng)?.let {
                Placement.Relative(
                    Relation.entries[rng.nextInt(Relation.entries.size)],
                    NodeRef.Existing(it),
                )
            } ?: Placement.Auto
        }

    private fun Board.randomId(rng: Random) =
        nodes.keys.sortedBy { it.value }.let { if (it.isEmpty()) null else it[rng.nextInt(it.size)] }
}
