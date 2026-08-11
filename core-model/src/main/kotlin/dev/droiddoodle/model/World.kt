package dev.droiddoodle.model

/**
 * A grid cell. Coordinates are **signed**: `row` increases southward, `col`
 * increases eastward.
 *
 * Signed coordinates exist to avoid a specific bug class. With 0-based indices,
 * placing something north of row 0 forces every node on the board to shift,
 * which corrupts undo records and invalidates ids held in the reference table.
 * Signed coordinates make northward growth a no-op. See docs/20-world-model.md §1.
 */
public data class Cell(public val row: Int, public val col: Int) {
    public fun offset(dRow: Int, dCol: Int): Cell = Cell(row + dRow, col + dCol)

    public val inBounds: Boolean
        get() = row in MIN..MAX && col in MIN..MAX

    /** Chebyshev distance, the metric used by the ring searches in placement. */
    public fun chebyshevTo(other: Cell): Int =
        maxOf(kotlin.math.abs(row - other.row), kotlin.math.abs(col - other.col))

    override fun toString(): String = "r${row}c${col}"

    public companion object {
        public const val MIN: Int = -32
        public const val MAX: Int = 32
        public val ORIGIN: Cell = Cell(0, 0)
    }
}

public enum class NodeType {
    PLACE, CHARACTER, OBJECT, NOTE, GROUP;

    /** Abbreviated form used in the viewport digest. See docs/22-context.md §4. */
    public val digestTag: String
        get() = when (this) {
            PLACE -> "place"
            CHARACTER -> "char"
            OBJECT -> "obj"
            NOTE -> "note"
            GROUP -> "group"
        }
}

public enum class NodeColor { DEFAULT, RED, ORANGE, YELLOW, GREEN, BLUE, PURPLE, GRAY }

public enum class NodeSize { SMALL, MEDIUM, LARGE }

public data class Style(
    public val color: NodeColor = NodeColor.DEFAULT,
    public val size: NodeSize = NodeSize.MEDIUM,
) {
    public companion object {
        public val DEFAULT: Style = Style()
    }
}

/**
 * `type` is a closed enum because the engine reasons about it: it drives
 * rendering and which edges are legal. `kind`, `label` and `attributes` are open
 * because the engine never reasons about them -- they are payload.
 *
 * This is the "closed structure, open semantics" decision (D3) made concrete. It
 * is what lets "turn the dungeon into a board game" be a relabel pass rather
 * than a rebuild.
 */
public data class Node(
    public val id: NodeId,
    public val type: NodeType,
    public val label: String,
    public val kind: String = "",
    public val cell: Cell,
    public val attributes: Map<String, String> = emptyMap(),
    public val style: Style = Style.DEFAULT,
)

public enum class EdgeType {
    CONTAINS, CONNECTS, KNOWS, FEARS, OWNS, BLOCKS, CUSTOM;

    /** Only [CONNECTS] is symmetric; A→B and B→A are the same edge. */
    public val symmetric: Boolean
        get() = this == CONNECTS
}

public data class Edge(
    public val id: EdgeId,
    public val type: EdgeType,
    public val from: NodeId,
    public val to: NodeId,
    public val label: String = "",
) {
    /** Order-independent key for symmetric edges, so duplicates collapse. */
    public val dedupeKey: Triple<EdgeType, String, String>
        get() = if (type.symmetric && from.value > to.value) {
            Triple(type, to.value, from.value)
        } else {
            Triple(type, from.value, to.value)
        }
}

public enum class Relation { NORTH_OF, SOUTH_OF, EAST_OF, WEST_OF, NEXT_TO }

/**
 * The model states intent; the engine resolves it to a cell. The model never
 * emits coordinates for relative placement.
 *
 * This is the intent document's governing rule at its most literal: if the
 * runtime can determine something deterministically, the model is not asked to
 * infer it.
 */
public sealed interface Placement {
    public data class Relative(
        public val relation: Relation,
        public val ref: NodeRef,
    ) : Placement

    public data class Absolute(public val cell: Cell) : Placement

    public data object Auto : Placement
}

public enum class ArrangeLayout { ROW, COLUMN, GRID, CLUSTER_LEFT, CLUSTER_RIGHT }

/**
 * The window of the board the user is looking at. This is UI state, not board
 * state, but it is an input to context assembly: it decides which nodes the
 * model is told about. See docs/22-context.md §4.
 */
public data class Viewport(
    public val top: Int = -4,
    public val left: Int = -4,
    public val rows: Int = 8,
    public val cols: Int = 8,
) {
    public val rowRange: IntRange get() = top until (top + rows.coerceIn(1, 16))
    public val colRange: IntRange get() = left until (left + cols.coerceIn(1, 16))

    public operator fun contains(cell: Cell): Boolean =
        cell.row in rowRange && cell.col in colRange

    public val centre: Cell
        get() = Cell(top + rows / 2, left + cols / 2)

    public companion object {
        public val DEFAULT: Viewport = Viewport()
    }
}

/** A single cell-level change, used for rendering and for the trace. */
public data class CellDelta(
    public val kind: DeltaKind,
    public val nodeId: NodeId,
    public val before: Cell?,
    public val after: Cell?,
    public val summary: String,
)

public enum class DeltaKind { CREATED, UPDATED, MOVED, DELETED, EDGE_ADDED, EDGE_REMOVED }
