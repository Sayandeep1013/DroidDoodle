package dev.droiddoodle.world

import dev.droiddoodle.model.Cell
import dev.droiddoodle.model.Edge
import dev.droiddoodle.model.EdgeId
import dev.droiddoodle.model.EdgeType
import dev.droiddoodle.model.Limits
import dev.droiddoodle.model.Node
import dev.droiddoodle.model.NodeId
import kotlin.math.floor

/**
 * The board is an immutable value; every mutation returns a new instance.
 * Structural sharing makes this cheap at the scale involved, and immutability is
 * what makes undo a matter of holding a reference rather than replaying a log.
 *
 * See docs/20-world-model.md §5.
 */
public data class Board(
    public val nodes: Map<NodeId, Node> = emptyMap(),
    public val edges: Map<EdgeId, Edge> = emptyMap(),
    public val nextNodeSeq: Int = 1,
    public val nextEdgeSeq: Int = 1,
) {

    /**
     * Derived rather than stored. The spec calls this an index maintained in
     * sync; deriving it makes desynchronisation impossible by construction,
     * which is strictly stronger. The invariant left to test is that operations
     * never place two nodes in one cell.
     */
    public val occupancy: Map<Cell, NodeId> by lazy(LazyThreadSafetyMode.NONE) {
        nodes.values.associate { it.cell to it.id }
    }

    public fun node(id: NodeId): Node? = nodes[id]

    public fun nodeAt(cell: Cell): NodeId? = occupancy[cell]

    public fun isFree(cell: Cell, ignoring: NodeId? = null): Boolean {
        if (!cell.inBounds) return false
        val occupant = occupancy[cell] ?: return true
        return occupant == ignoring
    }

    public val size: Int get() = nodes.size

    public val isEmpty: Boolean get() = nodes.isEmpty()

    // ---- containment ----------------------------------------------------

    /** The node that contains [id], if any. Invariant I2 guarantees at most one. */
    public fun containerOf(id: NodeId): NodeId? =
        edges.values.firstOrNull { it.type == EdgeType.CONTAINS && it.to == id }?.from

    public fun childrenOf(id: NodeId): List<NodeId> =
        edges.values
            .filter { it.type == EdgeType.CONTAINS && it.from == id }
            .map { it.to }
            .sortedBy { it.value }

    /** All transitive children. Used by delete cascade and by cycle checks. */
    public fun descendantsOf(id: NodeId): Set<NodeId> {
        val seen = LinkedHashSet<NodeId>()
        val queue = ArrayDeque(childrenOf(id))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (seen.add(next)) queue.addAll(childrenOf(next))
        }
        return seen
    }

    /** 1 for an uncontained node, incrementing for each enclosing container. */
    public fun depthOf(id: NodeId): Int {
        var depth = 1
        var current = containerOf(id)
        val guard = HashSet<NodeId>()
        while (current != null && guard.add(current)) {
            depth++
            current = containerOf(current)
        }
        return depth
    }

    /** 0 when [id] contains nothing, otherwise the deepest chain below it. */
    public fun heightOf(id: NodeId): Int {
        val children = childrenOf(id)
        if (children.isEmpty()) return 0
        return 1 + children.maxOf { heightOf(it) }
    }

    // ---- edges ----------------------------------------------------------

    public fun edgesBetween(from: NodeId, to: NodeId, type: EdgeType? = null): List<Edge> =
        edges.values.filter { edge ->
            val matchesType = type == null || edge.type == type
            val matchesEnds = (edge.from == from && edge.to == to) ||
                (edge.type.symmetric && edge.from == to && edge.to == from)
            matchesType && matchesEnds
        }.sortedBy { it.id.value }

    public fun incidentEdges(id: NodeId): List<Edge> =
        edges.values.filter { it.from == id || it.to == id }

    // ---- geometry -------------------------------------------------------

    public val extent: Extent?
        get() {
            if (nodes.isEmpty()) return null
            val cells = nodes.values.map { it.cell }
            return Extent(
                minRow = cells.minOf { it.row },
                maxRow = cells.maxOf { it.row },
                minCol = cells.minOf { it.col },
                maxCol = cells.maxOf { it.col },
            )
        }

    /**
     * Mean of occupied cells, rounded half-up. Half-up is specified explicitly
     * rather than left to [Math.round] semantics because these coordinates are
     * signed, and "round half away from zero" would make [Placement.Auto]
     * asymmetric about the origin.
     */
    public val centroid: Cell
        get() {
            if (nodes.isEmpty()) return Cell.ORIGIN
            val cells = nodes.values.map { it.cell }
            val row = floor(cells.sumOf { it.row }.toDouble() / cells.size + 0.5).toInt()
            val col = floor(cells.sumOf { it.col }.toDouble() / cells.size + 0.5).toInt()
            return Cell(row, col)
        }

    public val isFull: Boolean get() = nodes.size >= Limits.BOARD_MAX_NODES

    public companion object {
        public val EMPTY: Board = Board()
    }
}

public data class Extent(
    public val minRow: Int,
    public val maxRow: Int,
    public val minCol: Int,
    public val maxCol: Int,
)
