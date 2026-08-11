package dev.droiddoodle.world

import dev.droiddoodle.model.Cell
import dev.droiddoodle.model.CellDelta
import dev.droiddoodle.model.DeltaKind
import dev.droiddoodle.model.Edge
import dev.droiddoodle.model.EdgeId
import dev.droiddoodle.model.EdgeType
import dev.droiddoodle.model.Limits
import dev.droiddoodle.model.Node
import dev.droiddoodle.model.NodeColor
import dev.droiddoodle.model.NodeId
import dev.droiddoodle.model.NodeSize
import dev.droiddoodle.model.NodeType
import dev.droiddoodle.model.Placement
import dev.droiddoodle.model.Res
import dev.droiddoodle.model.Style
import dev.droiddoodle.model.WorldError
import dev.droiddoodle.model.WorldErrorCode
import kotlin.math.ceil
import kotlin.math.sqrt

public data class BoardChange(
    public val board: Board,
    public val diff: List<CellDelta>,
)

/**
 * The complete mutation surface of the world. Every operation is a pure
 * function from a board to a new board.
 *
 * See docs/20-world-model.md §8.
 */
public object BoardOps {

    // ---- nodes ----------------------------------------------------------

    public fun addNode(
        board: Board,
        type: NodeType,
        label: String,
        kind: String = "",
        placement: Placement = Placement.Auto,
        attributes: Map<String, String> = emptyMap(),
        style: Style = Style.DEFAULT,
    ): Res<BoardChange, WorldError> {
        if (board.isFull) {
            return invalid(
                WorldErrorCode.BOARD_FULL,
                "the board already holds ${Limits.BOARD_MAX_NODES} nodes; delete something first",
            )
        }
        val cleanLabel = label.trim()
        Limits.checkLabel(cleanLabel)?.let { return invalid(WorldErrorCode.INVALID_FIELD, it) }
        Limits.checkKind(kind)?.let { return invalid(WorldErrorCode.INVALID_FIELD, it) }

        val cleanAttrs = normalizeAttributes(attributes)
        Limits.checkAttributes(cleanAttrs)?.let { return invalid(WorldErrorCode.INVALID_FIELD, it) }

        val cell = when (val r = PlacementResolver.resolve(board, placement)) {
            is Res.Ok -> r.value
            is Res.Err -> return r
        }

        val id = NodeId.of(board.nextNodeSeq)
        val node = Node(
            id = id,
            type = type,
            label = cleanLabel,
            kind = kind.trim(),
            cell = cell,
            attributes = cleanAttrs,
            style = style,
        )
        return Res.Ok(
            BoardChange(
                board = board.copy(
                    nodes = board.nodes + (id to node),
                    nextNodeSeq = board.nextNodeSeq + 1,
                ),
                diff = listOf(
                    CellDelta(
                        DeltaKind.CREATED, id, null, cell,
                        "created ${type.digestTag} \"$cleanLabel\" at $cell",
                    ),
                ),
            ),
        )
    }

    /**
     * A null argument means "leave unchanged". [setAttributes] merges and
     * [unsetAttributes] removes; there is no whole-map replacement, because
     * replacement makes a small model silently destroy attributes it never
     * mentioned.
     */
    public fun updateNode(
        board: Board,
        id: NodeId,
        label: String? = null,
        kind: String? = null,
        setAttributes: Map<String, String>? = null,
        unsetAttributes: List<String>? = null,
        color: NodeColor? = null,
        size: NodeSize? = null,
    ): Res<BoardChange, WorldError> {
        val node = board.node(id) ?: return unknownNode(id)

        val newLabel = label?.trim() ?: node.label
        if (label != null) {
            Limits.checkLabel(newLabel)?.let { return invalid(WorldErrorCode.INVALID_FIELD, it) }
        }
        val newKind = kind?.trim() ?: node.kind
        if (kind != null) {
            Limits.checkKind(newKind)?.let { return invalid(WorldErrorCode.INVALID_FIELD, it) }
        }

        var attrs = node.attributes
        unsetAttributes?.forEach { attrs = attrs - Limits.normalizeAttrKey(it) }
        setAttributes?.let { attrs = attrs + normalizeAttributes(it) }
        Limits.checkAttributes(attrs)?.let { return invalid(WorldErrorCode.INVALID_FIELD, it) }

        val newStyle = Style(
            color = color ?: node.style.color,
            size = size ?: node.style.size,
        )

        val updated = node.copy(
            label = newLabel,
            kind = newKind,
            attributes = attrs,
            style = newStyle,
        )
        if (updated == node) {
            return Res.Ok(BoardChange(board, emptyList()))
        }
        return Res.Ok(
            BoardChange(
                board = board.copy(nodes = board.nodes + (id to updated)),
                diff = listOf(
                    CellDelta(
                        DeltaKind.UPDATED, id, node.cell, node.cell,
                        describeUpdate(node, updated),
                    ),
                ),
            ),
        )
    }

    /**
     * The node vacates its own cell before placement is resolved. Without that,
     * moving a node NEXT_TO something adjacent to itself can fail against its
     * own occupancy.
     */
    public fun moveNode(
        board: Board,
        id: NodeId,
        placement: Placement,
    ): Res<BoardChange, WorldError> {
        val node = board.node(id) ?: return unknownNode(id)
        val cell = when (val r = PlacementResolver.resolve(board, placement, ignoring = id)) {
            is Res.Ok -> r.value
            is Res.Err -> return r
        }
        if (cell == node.cell) {
            return Res.Ok(BoardChange(board, emptyList()))
        }
        val moved = node.copy(cell = cell)
        return Res.Ok(
            BoardChange(
                board = board.copy(nodes = board.nodes + (id to moved)),
                diff = listOf(
                    CellDelta(
                        DeltaKind.MOVED, id, node.cell, cell,
                        "moved \"${node.label}\" from ${node.cell} to $cell",
                    ),
                ),
            ),
        )
    }

    /**
     * Removes the node, everything it transitively contains, and every edge
     * incident to any of them.
     *
     * Cascading into contained descendants rather than orphaning them matches
     * what a person means by "delete the village", and the confirmation gate in
     * docs/23-agent-runtime.md §7 is what protects against the blast radius
     * being a surprise.
     */
    public fun removeNode(board: Board, id: NodeId): Res<BoardChange, WorldError> {
        val node = board.node(id) ?: return unknownNode(id)
        val targets = linkedSetOf(id) + board.descendantsOf(id)

        val remainingNodes = board.nodes.filterKeys { it !in targets }
        val remainingEdges = board.edges.filterValues { it.from !in targets && it.to !in targets }

        val diff = targets.mapNotNull { target ->
            board.node(target)?.let { n ->
                CellDelta(
                    DeltaKind.DELETED, target, n.cell, null,
                    if (target == id) {
                        "deleted \"${n.label}\""
                    } else {
                        "deleted \"${n.label}\" (contained by \"${node.label}\")"
                    },
                )
            }
        }
        return Res.Ok(
            BoardChange(board.copy(nodes = remainingNodes, edges = remainingEdges), diff),
        )
    }

    /** Every node that [removeNode] would destroy. Used by the confirmation gate. */
    public fun deletionFootprint(board: Board, id: NodeId): Set<NodeId> =
        if (board.node(id) == null) emptySet() else linkedSetOf(id) + board.descendantsOf(id)

    // ---- edges ----------------------------------------------------------

    public fun addEdge(
        board: Board,
        type: EdgeType,
        from: NodeId,
        to: NodeId,
        label: String = "",
    ): Res<BoardChange, WorldError> {
        board.node(from) ?: return unknownNode(from)
        val toNode = board.node(to) ?: return unknownNode(to)

        if (from == to) {
            return invalid(WorldErrorCode.SELF_EDGE, "a node cannot connect to itself")
        }
        Limits.checkEdgeLabel(type, label)?.let { return invalid(WorldErrorCode.INVALID_FIELD, it) }

        val exact = board.edges.values.any { it.type == type && it.from == from && it.to == to }
        if (exact) {
            return invalid(
                WorldErrorCode.DUPLICATE_EDGE,
                "$from and $to are already linked by ${type.name.lowercase()}",
            )
        }
        // A symmetric edge in the opposite direction is the same edge, so
        // creating it again is a no-op rather than an error.
        if (type.symmetric &&
            board.edges.values.any { it.type == type && it.from == to && it.to == from }
        ) {
            return Res.Ok(BoardChange(board, emptyList()))
        }

        if (type == EdgeType.CONTAINS) {
            checkContainment(board, from, to)?.let { return Res.Err(it) }
        }

        val id = EdgeId.of(board.nextEdgeSeq)
        val edge = Edge(id, type, from, to, label.trim())
        return Res.Ok(
            BoardChange(
                board = board.copy(
                    edges = board.edges + (id to edge),
                    nextEdgeSeq = board.nextEdgeSeq + 1,
                ),
                diff = listOf(
                    CellDelta(
                        DeltaKind.EDGE_ADDED, from, null, null,
                        "linked $from ${type.name.lowercase()} $to (\"${toNode.label}\")",
                    ),
                ),
            ),
        )
    }

    private fun checkContainment(board: Board, from: NodeId, to: NodeId): WorldError? {
        if (board.containerOf(to) != null) {
            return WorldError(
                WorldErrorCode.ALREADY_CONTAINED,
                "$to is already inside ${board.containerOf(to)}; remove that first",
            )
        }
        if (from in board.descendantsOf(to)) {
            return WorldError(
                WorldErrorCode.CONTAINMENT_CYCLE,
                "$from is already inside $to, so $to cannot also go inside $from",
            )
        }
        val resultingDepth = board.depthOf(from) + 1 + board.heightOf(to)
        if (resultingDepth > Limits.CONTAINMENT_MAX_DEPTH) {
            return WorldError(
                WorldErrorCode.CONTAINMENT_TOO_DEEP,
                "nesting would be $resultingDepth deep; the limit is " +
                    "${Limits.CONTAINMENT_MAX_DEPTH}",
            )
        }
        return null
    }

    public fun removeEdge(
        board: Board,
        from: NodeId,
        to: NodeId,
        type: EdgeType? = null,
    ): Res<BoardChange, WorldError> {
        val matches = board.edgesBetween(from, to, type)
        if (matches.isEmpty()) {
            return invalid(
                WorldErrorCode.UNKNOWN_EDGE,
                "no ${type?.name?.lowercase() ?: ""} link between $from and $to".replace("  ", " "),
            )
        }
        val ids = matches.map { it.id }.toSet()
        return Res.Ok(
            BoardChange(
                board = board.copy(edges = board.edges.filterKeys { it !in ids }),
                diff = matches.map {
                    CellDelta(
                        DeltaKind.EDGE_REMOVED, it.from, null, null,
                        "unlinked ${it.from} ${it.type.name.lowercase()} ${it.to}",
                    )
                },
            ),
        )
    }

    // ---- arrange --------------------------------------------------------

    /**
     * Atomic. Targets are computed against a board with the whole set lifted
     * out, so members never collide with each other; if any target is blocked by
     * a non-member the entire operation fails and nothing moves.
     */
    public fun arrange(
        board: Board,
        ids: List<NodeId>,
        layout: ArrangeLayoutSpec,
    ): Res<BoardChange, WorldError> {
        if (ids.isEmpty()) {
            return invalid(WorldErrorCode.INVALID_FIELD, "arrange needs at least one node")
        }
        if (ids.size > Limits.ARRANGE_MAX_NODES) {
            return invalid(
                WorldErrorCode.INVALID_FIELD,
                "arrange accepts at most ${Limits.ARRANGE_MAX_NODES} nodes, got ${ids.size}",
            )
        }
        val distinct = ids.distinct()
        val members = distinct.map { board.node(it) ?: return unknownNode(it) }

        val outsiders = board.nodes.keys - distinct.toSet()
        val outsiderCells = outsiders.mapNotNull { board.node(it)?.cell }.toSet()

        val targets = computeTargets(board, members, outsiders, layout)

        for ((index, cell) in targets.withIndex()) {
            if (!cell.inBounds) {
                return invalid(
                    WorldErrorCode.OUT_OF_BOUNDS,
                    "arranging would push ${members[index].label} off the board at $cell",
                )
            }
            if (cell in outsiderCells) {
                return invalid(
                    WorldErrorCode.ARRANGE_BLOCKED,
                    "cell $cell is taken by ${board.nodeAt(cell)}, so the layout cannot be applied",
                )
            }
        }

        var nodes = board.nodes
        val diff = ArrayList<CellDelta>()
        for ((index, node) in members.withIndex()) {
            val cell = targets[index]
            if (cell == node.cell) continue
            nodes = nodes + (node.id to node.copy(cell = cell))
            diff += CellDelta(
                DeltaKind.MOVED, node.id, node.cell, cell,
                "moved \"${node.label}\" to $cell",
            )
        }
        return Res.Ok(BoardChange(board.copy(nodes = nodes), diff))
    }

    private fun computeTargets(
        board: Board,
        members: List<Node>,
        outsiders: Set<NodeId>,
        layout: ArrangeLayoutSpec,
    ): List<Cell> {
        val n = members.size
        val memberCells = members.map { it.cell }
        return when (layout) {
            ArrangeLayoutSpec.ROW -> {
                val row = memberCells.first().row
                val startCol = memberCells.minOf { it.col }
                List(n) { Cell(row, startCol + it) }
            }

            ArrangeLayoutSpec.COLUMN -> {
                val col = memberCells.first().col
                val startRow = memberCells.minOf { it.row }
                List(n) { Cell(startRow + it, col) }
            }

            ArrangeLayoutSpec.GRID -> {
                val width = ceil(sqrt(n.toDouble())).toInt().coerceAtLeast(1)
                val anchorRow = memberCells.minOf { it.row }
                val anchorCol = memberCells.minOf { it.col }
                List(n) { Cell(anchorRow + it / width, anchorCol + it % width) }
            }

            ArrangeLayoutSpec.CLUSTER_LEFT, ArrangeLayoutSpec.CLUSTER_RIGHT -> {
                // Anchored just outside the extent of everything that is not
                // moving, so "put the important ones on the left" cannot collide
                // with the things it is being placed relative to.
                val outsiderCells = outsiders.mapNotNull { board.node(it)?.cell }
                val anchorRow = outsiderCells.minOfOrNull { it.row }
                    ?: memberCells.minOf { it.row }
                val anchorCol = if (layout == ArrangeLayoutSpec.CLUSTER_LEFT) {
                    (outsiderCells.minOfOrNull { it.col } ?: 0) - 2
                } else {
                    (outsiderCells.maxOfOrNull { it.col } ?: 0) + 1
                }
                val rowsPerColumn = ceil(n / 2.0).toInt().coerceAtLeast(1)
                List(n) {
                    Cell(anchorRow + it % rowsPerColumn, anchorCol + it / rowsPerColumn)
                }
            }
        }
    }

    // ---- helpers --------------------------------------------------------

    private fun normalizeAttributes(raw: Map<String, String>): Map<String, String> =
        raw.entries.associate { (k, v) -> Limits.normalizeAttrKey(k) to v }

    private fun describeUpdate(before: Node, after: Node): String {
        val parts = ArrayList<String>(4)
        if (before.label != after.label) parts += "renamed to \"${after.label}\""
        if (before.kind != after.kind) parts += "kind is now \"${after.kind}\""
        val added = after.attributes.filter { (k, v) -> before.attributes[k] != v }
        if (added.isNotEmpty()) parts += added.entries.joinToString(", ") { "${it.key}=${it.value}" }
        val removed = before.attributes.keys - after.attributes.keys
        if (removed.isNotEmpty()) parts += "removed ${removed.joinToString(", ")}"
        if (before.style != after.style) parts += "restyled"
        return "updated \"${after.label}\": " + parts.joinToString("; ").ifEmpty { "no change" }
    }

    private fun invalid(code: WorldErrorCode, message: String): Res<Nothing, WorldError> =
        Res.Err(WorldError(code, message))

    private fun unknownNode(id: NodeId): Res<Nothing, WorldError> =
        Res.Err(WorldError(WorldErrorCode.UNKNOWN_NODE, "no node called $id"))
}

/** Mirrors [dev.droiddoodle.model.ArrangeLayout]; aliased for readability here. */
public typealias ArrangeLayoutSpec = dev.droiddoodle.model.ArrangeLayout
