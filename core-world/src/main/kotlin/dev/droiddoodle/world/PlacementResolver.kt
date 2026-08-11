package dev.droiddoodle.world

import dev.droiddoodle.model.Cell
import dev.droiddoodle.model.Limits
import dev.droiddoodle.model.NodeId
import dev.droiddoodle.model.NodeRef
import dev.droiddoodle.model.Placement
import dev.droiddoodle.model.Relation
import dev.droiddoodle.model.Res
import dev.droiddoodle.model.WorldError
import dev.droiddoodle.model.WorldErrorCode
import kotlin.math.abs

/**
 * Turns a stated intent into a concrete cell.
 *
 * Fully deterministic: given an identical board and placement the result is
 * always identical. There is no randomness and no tie-breaking by the iteration
 * order of a hash map, which is what lets every spatial behaviour be asserted
 * exactly in a unit test.
 *
 * See docs/20-world-model.md §7.
 */
public object PlacementResolver {

    public fun resolve(
        board: Board,
        placement: Placement,
        ignoring: NodeId? = null,
    ): Res<Cell, WorldError> = when (placement) {
        is Placement.Absolute -> resolveAbsolute(board, placement.cell, ignoring)
        is Placement.Relative -> resolveRelative(board, placement, ignoring)
        is Placement.Auto -> resolveAuto(board, ignoring)
    }

    // ---- absolute -------------------------------------------------------

    /**
     * Absolute placement never searches for an alternative. If the model asked
     * for an exact cell, silently moving it elsewhere would make the trace
     * misleading about what was requested.
     */
    private fun resolveAbsolute(board: Board, cell: Cell, ignoring: NodeId?): Res<Cell, WorldError> {
        if (!cell.inBounds) {
            return Res.Err(
                WorldError(
                    WorldErrorCode.OUT_OF_BOUNDS,
                    "$cell is outside the board; rows and columns run from " +
                        "${Cell.MIN} to ${Cell.MAX}",
                ),
            )
        }
        val occupant = board.nodeAt(cell)
        if (occupant != null && occupant != ignoring) {
            return Res.Err(
                WorldError(WorldErrorCode.CELL_OCCUPIED, "cell $cell is taken by $occupant"),
            )
        }
        return Res.Ok(cell)
    }

    // ---- relative -------------------------------------------------------

    private fun resolveRelative(
        board: Board,
        placement: Placement.Relative,
        ignoring: NodeId?,
    ): Res<Cell, WorldError> {
        val refId = (placement.ref as? NodeRef.Existing)?.id
            ?: return Res.Err(
                WorldError(
                    WorldErrorCode.UNKNOWN_REF,
                    "placement reference must be an existing node, got ${placement.ref}",
                ),
            )
        val refNode = board.node(refId)
            ?: return Res.Err(
                WorldError(WorldErrorCode.UNKNOWN_REF, "no node called $refId"),
            )

        val candidates = if (placement.relation == Relation.NEXT_TO) {
            nextToCandidates(refNode.cell)
        } else {
            directionalCandidates(refNode.cell, placement.relation)
        }

        val hit = candidates.firstOrNull { board.isFree(it, ignoring) }
        return if (hit != null) {
            Res.Ok(hit)
        } else {
            Res.Err(
                WorldError(
                    WorldErrorCode.NO_FREE_CELL,
                    "no free cell ${describe(placement.relation)} ${refNode.label} " +
                        "within ${Limits.RELATIVE_MAX_DISTANCE} cells",
                ),
            )
        }
    }

    /** East, west, south, north -- then a ring search centred on the reference. */
    private fun nextToCandidates(ref: Cell): List<Cell> {
        val orthogonal = listOf(
            ref.offset(0, 1),
            ref.offset(0, -1),
            ref.offset(1, 0),
            ref.offset(-1, 0),
        )
        val fallback = (1..Limits.RELATIVE_MAX_DISTANCE).flatMap { ringCells(ref, it) }
        return orthogonal + fallback.filterNot { it in orthogonal }
    }

    /**
     * Candidates that **preserve the relation**, ordered by Manhattan distance
     * from the reference, then by primary-axis distance, then by lateral
     * distance, then negative lateral before positive.
     *
     * For NORTH_OF from (r, c) this yields:
     *
     *     (r-1,c) | (r-1,c-1) (r-1,c+1) | (r-2,c) | (r-2,c-1) (r-2,c+1) | (r-3,c) …
     *
     * Preserving the axis rather than spiralling in all directions is what keeps
     * "north of" from quietly coming to mean "somewhere near". The directional
     * invariant always holds: after a successful NORTH_OF, placed.row < ref.row.
     */
    private fun directionalCandidates(ref: Cell, relation: Relation): List<Cell> {
        val max = Limits.RELATIVE_MAX_DISTANCE
        val scored = ArrayList<Triple<Cell, Int, Int>>(max * (2 * max + 1))
        for (d in 1..max) {
            for (lat in -max..max) {
                val cell = when (relation) {
                    Relation.NORTH_OF -> Cell(ref.row - d, ref.col + lat)
                    Relation.SOUTH_OF -> Cell(ref.row + d, ref.col + lat)
                    Relation.WEST_OF -> Cell(ref.row + lat, ref.col - d)
                    Relation.EAST_OF -> Cell(ref.row + lat, ref.col + d)
                    Relation.NEXT_TO -> error("NEXT_TO uses nextToCandidates")
                }
                scored += Triple(cell, d, lat)
            }
        }
        return scored
            .sortedWith(
                compareBy(
                    { it.second + abs(it.third) },
                    { it.second },
                    { abs(it.third) },
                    { it.third },
                ),
            )
            .map { it.first }
    }

    // ---- auto -----------------------------------------------------------

    private fun resolveAuto(board: Board, ignoring: NodeId?): Res<Cell, WorldError> {
        if (board.isEmpty) return Res.Ok(Cell.ORIGIN)
        val centre = board.centroid
        for (radius in 0..Limits.AUTO_MAX_RADIUS) {
            val hit = ringCells(centre, radius).firstOrNull { board.isFree(it, ignoring) }
            if (hit != null) return Res.Ok(hit)
        }
        return Res.Err(
            WorldError(
                WorldErrorCode.NO_FREE_CELL,
                "no free cell within ${Limits.AUTO_MAX_RADIUS} of $centre",
            ),
        )
    }

    /**
     * Cells at exactly Chebyshev distance [radius], walked clockwise starting
     * from the north-west corner.
     *
     * A clockwise ring walk is specified rather than "compass order" because
     * compass order is only well defined for radius 1; beyond that a ring has
     * more than eight cells and the phrase stops determining an ordering.
     */
    internal fun ringCells(centre: Cell, radius: Int): List<Cell> {
        if (radius == 0) return listOf(centre)
        val r = centre.row
        val c = centre.col
        val out = ArrayList<Cell>(8 * radius)
        for (col in (c - radius)..(c + radius)) out += Cell(r - radius, col)
        for (row in (r - radius + 1)..(r + radius)) out += Cell(row, c + radius)
        for (col in (c + radius - 1) downTo (c - radius)) out += Cell(r + radius, col)
        for (row in (r + radius - 1) downTo (r - radius + 1)) out += Cell(row, c - radius)
        return out
    }

    private fun describe(relation: Relation): String = when (relation) {
        Relation.NORTH_OF -> "north of"
        Relation.SOUTH_OF -> "south of"
        Relation.EAST_OF -> "east of"
        Relation.WEST_OF -> "west of"
        Relation.NEXT_TO -> "next to"
    }
}
