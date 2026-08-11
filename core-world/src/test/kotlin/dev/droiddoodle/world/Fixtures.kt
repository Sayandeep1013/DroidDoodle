package dev.droiddoodle.world

import dev.droiddoodle.model.Cell
import dev.droiddoodle.model.EdgeType
import dev.droiddoodle.model.NodeId
import dev.droiddoodle.model.NodeType
import dev.droiddoodle.model.Placement
import dev.droiddoodle.model.Res
import dev.droiddoodle.model.WorldError
import kotlin.test.fail

/** Unwraps a successful change, failing the test with the error message otherwise. */
internal fun Res<BoardChange, WorldError>.expectOk(): BoardChange = when (this) {
    is Res.Ok -> value
    is Res.Err -> fail("expected success but got ${error.code}: ${error.message}")
}

internal fun Res<BoardChange, WorldError>.expectErr(): WorldError = when (this) {
    is Res.Ok -> fail("expected failure but the operation succeeded")
    is Res.Err -> error
}

internal fun Res<BoardChange, WorldError>.board(): Board = expectOk().board

internal fun Board.at(row: Int, col: Int): NodeId? = nodeAt(Cell(row, col))

internal fun Board.labelled(label: String): NodeId? =
    nodes.values.firstOrNull { it.label == label }?.id

internal fun Board.cellOf(label: String): Cell =
    nodes.values.firstOrNull { it.label == label }?.cell
        ?: fail("no node labelled '$label' on the board")

/** Adds a node at an exact cell, for tests that need a known layout. */
internal fun Board.withNode(
    label: String,
    row: Int,
    col: Int,
    type: NodeType = NodeType.PLACE,
    kind: String = "",
): Board = BoardOps.addNode(
    board = this,
    type = type,
    label = label,
    kind = kind,
    placement = Placement.Absolute(Cell(row, col)),
).board()

internal fun Board.withEdge(fromLabel: String, toLabel: String, type: EdgeType): Board =
    BoardOps.addEdge(
        board = this,
        type = type,
        from = labelled(fromLabel) ?: fail("no node '$fromLabel'"),
        to = labelled(toLabel) ?: fail("no node '$toLabel'"),
    ).board()

/**
 * The VILLAGE fixture from docs/31-prompt-suite.md §2:
 * Village @0,0 · Tavern @0,1 · Borin @1,0 (character, blacksmith),
 * with Village CONTAINS Tavern and Village CONTAINS Borin.
 */
internal fun villageBoard(): Board = Board.EMPTY
    .withNode("Village", 0, 0)
    .withNode("Tavern", 0, 1)
    .withNode("Borin", 1, 0, NodeType.CHARACTER, "blacksmith")
    .withEdge("Village", "Tavern", EdgeType.CONTAINS)
    .withEdge("Village", "Borin", EdgeType.CONTAINS)

/** VILLAGE plus every cell in rows -1..1, columns -1..1 filled. */
internal fun crowdedBoard(): Board {
    var board = villageBoard()
    var filler = 1
    for (row in -1..1) {
        for (col in -1..1) {
            if (board.at(row, col) == null) {
                board = board.withNode("filler${filler++}", row, col, NodeType.OBJECT)
            }
        }
    }
    return board
}
