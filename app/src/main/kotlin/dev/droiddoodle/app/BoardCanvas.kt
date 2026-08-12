package dev.droiddoodle.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.droiddoodle.model.Cell
import dev.droiddoodle.model.EdgeType
import dev.droiddoodle.model.NodeId
import dev.droiddoodle.model.NodeSize
import dev.droiddoodle.model.NodeType
import dev.droiddoodle.world.Board
import androidx.compose.foundation.Canvas
import kotlin.math.roundToInt

internal const val BASE_CELL_DP: Float = 96f

/**
 * Renders the board.
 *
 * Grid-snapped coordinates make this simple in a way a freeform canvas would
 * not be: a cell maps to a rectangle by multiplication, hit-testing is a
 * rounding operation, and a drag resolves to a cell before it ever touches
 * state. That simplicity is a direct dividend of decision D2.
 */
@Composable
internal fun BoardCanvas(
    board: Board,
    selected: NodeId?,
    scale: Float,
    onSelect: (NodeId?) -> Unit,
    onDrag: (NodeId, Cell) -> Unit,
    modifier: Modifier = Modifier,
    /** From `ui.grid_visible`, so the setting has a visible effect. */
    gridVisible: Boolean = true,
) {
    val dark = MaterialTheme.colorScheme.background.luminanceIsDark()
    val measurer = rememberTextMeasurer()
    // Built once in composition; a VectorPainter cannot be created inside a
    // DrawScope, so the draw pass receives them ready-made.
    val typeIcons: Map<NodeType, VectorPainter> = NodeType.entries.associateWith { type ->
        rememberVectorPainter(ImageVector.vectorResource(typeIconRes(type)))
    }
    val density = LocalDensity.current
    val cellPx = with(density) { (BASE_CELL_DP * scale).dp.toPx() }

    var pan by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf<NodeId?>(null) }
    var dragCell by remember { mutableStateOf<Cell?>(null) }

    // Without this the canvas -- which is the entire app -- is a blank
    // rectangle to a screen reader. It cannot convey a spatial layout, but it
    // can say what is on the board and where, which is more than nothing and
    // is derived from the same state that is drawn.
    val description = remember(board, selected) { describeBoard(board, selected) }

    Box(modifier.background(MaterialTheme.colorScheme.background)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = description }
                .pointerInput(board, cellPx) {
                    detectTapGestures { position ->
                        val cell = position.toCell(intCentre(size), pan, cellPx)
                        onSelect(board.nodeAt(cell))
                    }
                }
                .pointerInput(board, cellPx) {
                    detectDragGestures(
                        onDragStart = { position ->
                            val cell = position.toCell(intCentre(size), pan, cellPx)
                            dragging = board.nodeAt(cell)
                            dragCell = cell
                        },
                        onDrag = { change, delta ->
                            change.consume()
                            val held = dragging
                            if (held == null) {
                                // Nothing under the finger, so the gesture pans.
                                pan += delta
                            } else {
                                dragCell = change.position.toCell(intCentre(size), pan, cellPx)
                            }
                        },
                        onDragEnd = {
                            val held = dragging
                            val target = dragCell
                            if (held != null && target != null) onDrag(held, target)
                            dragging = null
                            dragCell = null
                        },
                        onDragCancel = {
                            dragging = null
                            dragCell = null
                        },
                    )
                },
        ) {
            val centre = size.toOffsetCentre()
            if (gridVisible) drawGrid(centre, pan, cellPx, gridLine(dark))
            drawHulls(board, centre, pan, cellPx, hullTint(dark))
            drawEdges(board, centre, pan, cellPx, nodeStroke(dark))
            drawNodes(board, centre, pan, cellPx, selected, dragging, dark, measurer, typeIcons)
            dragCell?.takeIf { dragging != null }?.let { target ->
                drawDropTarget(target, centre, pan, cellPx, board, dragging)
            }
        }
    }
}

// ---- drawing -------------------------------------------------------------

private fun DrawScope.drawGrid(centre: Offset, pan: Offset, cellPx: Float, color: Color) {
    if (cellPx < 24f) return
    val origin = centre + pan
    var x = origin.x % cellPx - cellPx / 2f
    while (x < size.width) {
        drawLine(color, Offset(x, 0f), Offset(x, size.height), 1f)
        x += cellPx
    }
    var y = origin.y % cellPx - cellPx / 2f
    while (y < size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), 1f)
        y += cellPx
    }
}

/**
 * Containment is an edge, not spatial nesting, so the hull is drawn wherever the
 * members happen to be. Nothing about it exists in state.
 */
private fun DrawScope.drawHulls(
    board: Board,
    centre: Offset,
    pan: Offset,
    cellPx: Float,
    tint: Color,
) {
    for (node in board.nodes.values) {
        val members = board.descendantsOf(node.id)
        if (members.isEmpty()) continue
        val cells = (members.mapNotNull { board.node(it)?.cell } + node.cell)
        val minRow = cells.minOf { it.row }
        val maxRow = cells.maxOf { it.row }
        val minCol = cells.minOf { it.col }
        val maxCol = cells.maxOf { it.col }
        val topLeft = Cell(minRow, minCol).toOffset(centre, pan, cellPx) -
            Offset(cellPx * 0.44f, cellPx * 0.44f)
        val bottomRight = Cell(maxRow, maxCol).toOffset(centre, pan, cellPx) +
            Offset(cellPx * 0.44f, cellPx * 0.44f)
        drawRoundRect(
            color = tint,
            topLeft = topLeft,
            size = Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cellPx * 0.2f),
        )
    }
}

private fun DrawScope.drawEdges(
    board: Board,
    centre: Offset,
    pan: Offset,
    cellPx: Float,
    color: Color,
) {
    for (edge in board.edges.values) {
        if (edge.type == EdgeType.CONTAINS) continue // already conveyed by the hull
        val from = board.node(edge.from)?.cell?.toOffset(centre, pan, cellPx) ?: continue
        val to = board.node(edge.to)?.cell?.toOffset(centre, pan, cellPx) ?: continue
        drawLine(color, from, to, strokeWidth = 2f)
    }
}

private fun DrawScope.drawNodes(
    board: Board,
    centre: Offset,
    pan: Offset,
    cellPx: Float,
    selected: NodeId?,
    dragging: NodeId?,
    dark: Boolean,
    measurer: TextMeasurer,
    typeIcons: Map<NodeType, VectorPainter>,
) {
    for (node in board.nodes.values.sortedBy { it.cell.row }) {
        val scale = when (node.style.size) {
            NodeSize.SMALL -> 0.58f
            NodeSize.MEDIUM -> 0.76f
            NodeSize.LARGE -> 0.92f
        }
        val half = cellPx * scale / 2f
        val at = node.cell.toOffset(centre, pan, cellPx)
        if (at.x < -cellPx || at.y < -cellPx || at.x > size.width + cellPx ||
            at.y > size.height + cellPx
        ) {
            continue
        }
        val topLeft = at - Offset(half, half)
        val box = Size(half * 2, half * 2)
        val corner = androidx.compose.ui.geometry.CornerRadius(cellPx * 0.14f)

        drawRoundRect(
            color = nodeFill(node.style.color, node.type, dark)
                .copy(alpha = if (node.id == dragging) 0.45f else 1f),
            topLeft = topLeft,
            size = box,
            cornerRadius = corner,
        )
        drawRoundRect(
            color = if (node.id == selected) {
                if (dark) Color(0xFF9FD2FF) else Color(0xFF1F6FEB)
            } else {
                nodeStroke(dark)
            },
            topLeft = topLeft,
            size = box,
            cornerRadius = corner,
            style = Stroke(width = if (node.id == selected) 3f else 1.5f),
        )

        if (cellPx < 44f) continue

        // The type icon carries what the label cannot: a glance at the board
        // should show its shape -- places, characters, objects -- without
        // reading a word of it. Below 60px there is no room for both, and the
        // label wins because it is the part that is not guessable.
        val iconPainter = typeIcons[node.type].takeIf { cellPx >= 60f }
        var labelCentre = at
        if (iconPainter != null) {
            val iconSize = (half * 0.62f).coerceIn(12f, 34f)
            val iconTop = at.y - half + half * 0.22f
            translate(left = at.x - iconSize / 2f, top = iconTop) {
                with(iconPainter) {
                    draw(
                        size = Size(iconSize, iconSize),
                        alpha = 0.85f,
                        colorFilter = ColorFilter.tint(nodeStroke(dark)),
                    )
                }
            }
            labelCentre = at + Offset(0f, iconSize * 0.42f)
        }

        val label = measurer.measure(
            text = node.label,
            style = TextStyle(
                fontSize = (cellPx * 0.115f).coerceIn(9f, 15f).sp,
                color = if (dark) Color(0xFFE4E8EE) else Color(0xFF11151A),
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            constraints = androidx.compose.ui.unit.Constraints(
                maxWidth = (half * 1.85f).roundToInt().coerceAtLeast(1),
            ),
        )
        drawText(
            textLayoutResult = label,
            topLeft = labelCentre - Offset(label.size.width / 2f, label.size.height / 2f),
        )
    }
}

private fun DrawScope.drawDropTarget(
    cell: Cell,
    centre: Offset,
    pan: Offset,
    cellPx: Float,
    board: Board,
    dragging: NodeId?,
) {
    // Green when the drop is legal, red when the cell is taken -- the same rule
    // the placement resolver applies to the agent.
    val free = board.isFree(cell, ignoring = dragging)
    val at = cell.toOffset(centre, pan, cellPx)
    val half = cellPx * 0.44f
    drawRoundRect(
        color = if (free) Color(0x5533C27A) else Color(0x55E5484D),
        topLeft = at - Offset(half, half),
        size = Size(half * 2, half * 2),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cellPx * 0.14f),
        style = Stroke(width = 3f),
    )
}

// ---- coordinate mapping ---------------------------------------------------

private fun Size.toOffsetCentre(): Offset = Offset(width / 2f, height / 2f)

/** Pointer-input scope reports an [androidx.compose.ui.unit.IntSize], not a [Size]. */
private fun intCentre(size: androidx.compose.ui.unit.IntSize): Offset =
    Offset(size.width / 2f, size.height / 2f)

private fun Cell.toOffset(centre: Offset, pan: Offset, cellPx: Float): Offset =
    centre + pan + Offset(col * cellPx, row * cellPx)

private fun Offset.toCell(centre: Offset, pan: Offset, cellPx: Float): Cell {
    val local = this - centre - pan
    return Cell(
        row = (local.y / cellPx).roundToInt(),
        col = (local.x / cellPx).roundToInt(),
    )
}



/**
 * Icon per node type. Exhaustive `when` on purpose: adding a `NodeType` should
 * fail to compile here rather than silently render an untyped box.
 */
internal fun typeIconRes(type: NodeType): Int = when (type) {
    NodeType.PLACE -> R.drawable.ic_type_place
    NodeType.CHARACTER -> R.drawable.ic_type_character
    NodeType.OBJECT -> R.drawable.ic_type_object
    NodeType.NOTE -> R.drawable.ic_type_note
    NodeType.GROUP -> R.drawable.ic_type_group
}

/**
 * What the canvas says to a screen reader.
 *
 * Deliberately capped: reading out sixty nodes is not accessibility, it is a
 * denial of service. Past the cap it reports the count and stops.
 */
internal fun describeBoard(board: Board, selected: NodeId?): String {
    if (board.isEmpty) return "Empty board. Describe what to create in the message box below."
    val described = board.nodes.values
        .sortedWith(compareBy({ it.cell.row }, { it.cell.col }))
        .take(SPOKEN_NODE_LIMIT)
        .joinToString(". ") { node ->
            val where = "row ${node.cell.row}, column ${node.cell.col}"
            val mark = if (node.id == selected) ", selected" else ""
            "${node.type.name.lowercase()} ${node.label} at $where$mark"
        }
    val overflow = board.size - SPOKEN_NODE_LIMIT
    val tail = if (overflow > 0) ". And $overflow more" else ""
    return "Board with ${board.size} items. $described$tail"
}

private const val SPOKEN_NODE_LIMIT = 12
