package dev.droiddoodle.agent

import dev.droiddoodle.grammar.PlanStep
import dev.droiddoodle.model.ArrangeLayout
import dev.droiddoodle.model.Cell
import dev.droiddoodle.model.CellDelta
import dev.droiddoodle.model.EdgeType
import dev.droiddoodle.model.NodeColor
import dev.droiddoodle.model.NodeId
import dev.droiddoodle.model.NodeRef
import dev.droiddoodle.model.NodeSize
import dev.droiddoodle.model.NodeType
import dev.droiddoodle.model.Placement
import dev.droiddoodle.model.Relation
import dev.droiddoodle.model.Res
import dev.droiddoodle.model.SettingsRegistry
import dev.droiddoodle.model.SettingsSnapshot
import dev.droiddoodle.model.Style
import dev.droiddoodle.model.ToolCatalog
import dev.droiddoodle.model.ToolError
import dev.droiddoodle.model.ToolErrorCode
import dev.droiddoodle.model.ToolSchema
import dev.droiddoodle.world.Board
import dev.droiddoodle.world.BoardOps
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

public data class ExecContext(
    public val board: Board,
    public val settings: SettingsSnapshot,
    /** Node created by each earlier step, keyed by 1-based step number. */
    public val createdByStep: Map<Int, NodeId>,
    /** 1-based position of the step being executed. */
    public val stepIndex: Int,
)

public data class ToolEffect(
    public val board: Board,
    public val diff: List<CellDelta> = emptyList(),
    public val createdNode: NodeId? = null,
    public val respondText: String? = null,
    public val settingWrite: Pair<String, String>? = null,
    public val findResults: List<NodeId>? = null,
    /** What each `$k` actually resolved to, recorded into the trace. */
    public val resolvedRefs: Map<String, String> = emptyMap(),
)

/**
 * Executes a validated plan step against a working board.
 *
 * The registry owns argument extraction and reference resolution; the world
 * layer owns all state rules. Nothing here re-implements a world invariant.
 */
public class ToolRegistry(
    public val schemas: List<ToolSchema> = ToolCatalog.ALL,
) {
    public val names: List<String> = schemas.map { it.name }

    public fun execute(step: PlanStep, ctx: ExecContext): Res<ToolEffect, ToolError> {
        val args = step.args
        return when (step.tool) {
            ToolCatalog.CREATE_NODE -> createNode(args, ctx)
            ToolCatalog.UPDATE_NODE -> updateNode(args, ctx)
            ToolCatalog.MOVE_NODE -> moveNode(args, ctx)
            ToolCatalog.DELETE_NODE -> deleteNode(args, ctx)
            ToolCatalog.CONNECT -> connect(args, ctx)
            ToolCatalog.DISCONNECT -> disconnect(args, ctx)
            ToolCatalog.FIND -> find(args, ctx)
            ToolCatalog.ARRANGE -> arrange(args, ctx)
            ToolCatalog.SET_SETTING -> setSetting(args, ctx)
            ToolCatalog.RESPOND -> respond(args, ctx)
            else -> Res.Err(
                ToolError(ToolErrorCode.INVALID_ARGS, "no tool called '${step.tool}'"),
            )
        }
    }

    // ---- tools ----------------------------------------------------------

    private fun createNode(args: JsonObject, ctx: ExecContext): Res<ToolEffect, ToolError> {
        val type = args.enumValue("type", NodeType.entries) { it.name }
            ?: return missing("type")
        val label = args.string("label") ?: return missing("label")

        val refs = mutableMapOf<String, String>()
        val placement = when (val p = parsePlacement(args["at"], ctx, refs)) {
            is Res.Ok -> p.value
            is Res.Err -> return p
        }

        return BoardOps.addNode(
            board = ctx.board,
            type = type,
            label = label,
            kind = args.string("kind") ?: "",
            placement = placement,
            attributes = args.attrMap("attributes") ?: emptyMap(),
            style = Style(
                color = args.enumValue("color", NodeColor.entries) { it.name } ?: NodeColor.DEFAULT,
                size = args.enumValue("size", NodeSize.entries) { it.name } ?: NodeSize.MEDIUM,
            ),
        ).toEffect(refs) { change ->
            change.diff.firstOrNull()?.nodeId
        }
    }

    private fun updateNode(args: JsonObject, ctx: ExecContext): Res<ToolEffect, ToolError> {
        val refs = mutableMapOf<String, String>()
        val node = when (val r = args.nodeRef("node", ctx, refs)) {
            is Res.Ok -> r.value
            is Res.Err -> return r
        }
        return BoardOps.updateNode(
            board = ctx.board,
            id = node,
            label = args.string("label"),
            kind = args.string("kind"),
            setAttributes = args.attrMap("set"),
            unsetAttributes = args.stringList("unset"),
            color = args.enumValue("color", NodeColor.entries) { it.name },
            size = args.enumValue("size", NodeSize.entries) { it.name },
        ).toEffect(refs)
    }

    private fun moveNode(args: JsonObject, ctx: ExecContext): Res<ToolEffect, ToolError> {
        val refs = mutableMapOf<String, String>()
        val node = when (val r = args.nodeRef("node", ctx, refs)) {
            is Res.Ok -> r.value
            is Res.Err -> return r
        }
        val to = args["to"] ?: return missing("to")
        val placement = when (val p = parsePlacement(to, ctx, refs)) {
            is Res.Ok -> p.value
            is Res.Err -> return p
        }
        return BoardOps.moveNode(ctx.board, node, placement).toEffect(refs)
    }

    private fun deleteNode(args: JsonObject, ctx: ExecContext): Res<ToolEffect, ToolError> {
        val refs = mutableMapOf<String, String>()
        val node = when (val r = args.nodeRef("node", ctx, refs)) {
            is Res.Ok -> r.value
            is Res.Err -> return r
        }
        return BoardOps.removeNode(ctx.board, node).toEffect(refs)
    }

    private fun connect(args: JsonObject, ctx: ExecContext): Res<ToolEffect, ToolError> {
        val refs = mutableMapOf<String, String>()
        val from = when (val r = args.nodeRef("from", ctx, refs)) {
            is Res.Ok -> r.value
            is Res.Err -> return r
        }
        val to = when (val r = args.nodeRef("to", ctx, refs)) {
            is Res.Ok -> r.value
            is Res.Err -> return r
        }
        val relation = args.enumValue("relation", EdgeType.entries) { it.name }
            ?: return missing("relation")
        return BoardOps.addEdge(ctx.board, relation, from, to, args.string("label") ?: "")
            .toEffect(refs)
    }

    private fun disconnect(args: JsonObject, ctx: ExecContext): Res<ToolEffect, ToolError> {
        val refs = mutableMapOf<String, String>()
        val from = when (val r = args.nodeRef("from", ctx, refs)) {
            is Res.Ok -> r.value
            is Res.Err -> return r
        }
        val to = when (val r = args.nodeRef("to", ctx, refs)) {
            is Res.Ok -> r.value
            is Res.Err -> return r
        }
        return BoardOps.removeEdge(
            ctx.board, from, to,
            args.enumValue("relation", EdgeType.entries) { it.name },
        ).toEffect(refs)
    }

    /**
     * Retrieval. The runtime lifts this into a bounded pre-pass because
     * plan-then-execute has no observation feedback -- see
     * docs/23-agent-runtime.md §2, phase 5. Executing it never mutates the board.
     */
    private fun find(args: JsonObject, ctx: ExecContext): Res<ToolEffect, ToolError> {
        val text = args.string("text")?.lowercase()
        val type = args.enumValue("type", NodeType.entries) { it.name }
        val kind = args.string("kind")?.lowercase()
        val attribute = args.string("attribute")

        if (text == null && type == null && kind == null && attribute == null) {
            return Res.Err(
                ToolError(ToolErrorCode.INVALID_ARGS, "find needs at least one search argument"),
            )
        }

        val attrKey = attribute?.substringBefore('=')?.trim()?.lowercase()
        val attrValue = attribute?.takeIf { it.contains('=') }?.substringAfter('=')?.trim()

        val hits = ctx.board.nodes.values
            .filter { node ->
                (text == null ||
                    node.label.lowercase().contains(text) ||
                    node.kind.lowercase().contains(text)) &&
                    (type == null || node.type == type) &&
                    (kind == null || node.kind.equals(kind, ignoreCase = true)) &&
                    (attrKey == null || node.attributes[attrKey]
                        ?.let { attrValue == null || it.equals(attrValue, ignoreCase = true) }
                        ?: false)
            }
            .sortedWith(compareBy({ it.cell.row }, { it.cell.col }))
            .map { it.id }

        return Res.Ok(ToolEffect(board = ctx.board, findResults = hits))
    }

    private fun arrange(args: JsonObject, ctx: ExecContext): Res<ToolEffect, ToolError> {
        val refs = mutableMapOf<String, String>()
        val raw = args["nodes"] as? JsonArray ?: return missing("nodes")
        val ids = ArrayList<NodeId>(raw.size)
        for (element in raw) {
            val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return Res.Err(
                    ToolError(ToolErrorCode.INVALID_ARGS, "every entry of 'nodes' must be a node id"),
                )
            when (val r = resolveRef(value, ctx, refs)) {
                is Res.Ok -> ids += r.value
                is Res.Err -> return r
            }
        }
        val layout = args.enumValue("layout", ArrangeLayout.entries) { it.name }
            ?: return missing("layout")
        return BoardOps.arrange(ctx.board, ids, layout).toEffect(refs)
    }

    private fun setSetting(args: JsonObject, ctx: ExecContext): Res<ToolEffect, ToolError> {
        val key = args.string("key") ?: return missing("key")
        val value = args.string("value") ?: return missing("value")
        return when (val v = SettingsRegistry.validate(key, value, fromAgent = true)) {
            is Res.Ok -> Res.Ok(ToolEffect(board = ctx.board, settingWrite = key to v.value))
            is Res.Err -> v
        }
    }

    private fun respond(args: JsonObject, ctx: ExecContext): Res<ToolEffect, ToolError> {
        val text = args.string("text") ?: return missing("text")
        dev.droiddoodle.model.Limits.checkRespondText(text)?.let {
            return Res.Err(ToolError(ToolErrorCode.INVALID_ARGS, it))
        }
        return Res.Ok(ToolEffect(board = ctx.board, respondText = text.trim()))
    }

    // ---- argument helpers ------------------------------------------------

    private fun missing(name: String): Res<Nothing, ToolError> =
        Res.Err(ToolError(ToolErrorCode.INVALID_ARGS, "missing required argument '$name'"))

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun <E> JsonObject.enumValue(name: String, values: List<E>, nameOf: (E) -> String): E? {
        val raw = string(name) ?: return null
        return values.firstOrNull { nameOf(it) == raw }
    }

    private fun JsonObject.attrMap(name: String): Map<String, String>? {
        val obj = this[name] as? JsonObject ?: return null
        return obj.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: "" }
    }

    private fun JsonObject.stringList(name: String): List<String>? {
        val array = this[name] as? JsonArray ?: return null
        return array.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    }

    private fun JsonObject.nodeRef(
        name: String,
        ctx: ExecContext,
        refs: MutableMap<String, String>,
    ): Res<NodeId, ToolError> {
        val raw = string(name)
            ?: return Res.Err(
                ToolError(ToolErrorCode.INVALID_ARGS, "missing required argument '$name'"),
            )
        return resolveRef(raw, ctx, refs)
    }

    /**
     * `$k` is valid only when k is strictly less than the current step and that
     * step was a create_node that succeeded.
     */
    private fun resolveRef(
        raw: String,
        ctx: ExecContext,
        refs: MutableMap<String, String>,
    ): Res<NodeId, ToolError> = when (val ref = NodeRef.parseOrNull(raw)) {
        null -> Res.Err(
            ToolError(ToolErrorCode.INVALID_ARGS, "'$raw' is not a node reference"),
        )

        is NodeRef.Existing -> Res.Ok(ref.id)

        is NodeRef.Step -> when {
            ref.step >= ctx.stepIndex -> Res.Err(
                ToolError(
                    ToolErrorCode.UNRESOLVED_STEP_REF,
                    "step ${ctx.stepIndex} refers to \$${ref.step}, which has not run yet",
                ),
            )

            else -> ctx.createdByStep[ref.step]?.let {
                refs[raw] = it.value
                Res.Ok(it)
            } ?: Res.Err(
                ToolError(
                    ToolErrorCode.UNRESOLVED_STEP_REF,
                    "\$${ref.step} did not create a node",
                ),
            )
        }
    }

    private fun parsePlacement(
        element: kotlinx.serialization.json.JsonElement?,
        ctx: ExecContext,
        refs: MutableMap<String, String>,
    ): Res<Placement, ToolError> {
        if (element == null) return Res.Ok(Placement.Auto)
        val obj = element as? JsonObject
            ?: return Res.Err(
                ToolError(ToolErrorCode.INVALID_ARGS, "placement must be an object"),
            )
        return when {
            "rel" in obj -> {
                val relationName = (obj["rel"] as? JsonPrimitive)?.content
                val relation = Relation.entries.firstOrNull { it.name == relationName }
                    ?: return Res.Err(
                        ToolError(ToolErrorCode.INVALID_ARGS, "'$relationName' is not a direction"),
                    )
                val refRaw = (obj["ref"] as? JsonPrimitive)?.content
                    ?: return Res.Err(
                        ToolError(ToolErrorCode.INVALID_ARGS, "placement is missing 'ref'"),
                    )
                when (val r = resolveRef(refRaw, ctx, refs)) {
                    is Res.Ok -> Res.Ok(Placement.Relative(relation, NodeRef.Existing(r.value)))
                    is Res.Err -> r
                }
            }

            "cell" in obj -> {
                val cell = obj["cell"] as? JsonObject
                    ?: return Res.Err(
                        ToolError(ToolErrorCode.INVALID_ARGS, "'cell' must be an object"),
                    )
                val row = (cell["row"] as? JsonPrimitive)?.content?.toIntOrNull()
                val col = (cell["col"] as? JsonPrimitive)?.content?.toIntOrNull()
                if (row == null || col == null) {
                    Res.Err(
                        ToolError(ToolErrorCode.INVALID_ARGS, "'cell' needs integer row and col"),
                    )
                } else {
                    Res.Ok(Placement.Absolute(Cell(row, col)))
                }
            }

            "auto" in obj -> Res.Ok(Placement.Auto)

            else -> Res.Err(
                ToolError(
                    ToolErrorCode.INVALID_ARGS,
                    "placement must be one of {rel,ref}, {cell}, or {auto}",
                ),
            )
        }
    }

    private fun Res<dev.droiddoodle.world.BoardChange, dev.droiddoodle.model.WorldError>.toEffect(
        refs: Map<String, String>,
        createdNode: (dev.droiddoodle.world.BoardChange) -> NodeId? = { null },
    ): Res<ToolEffect, ToolError> = when (this) {
        is Res.Ok -> Res.Ok(
            ToolEffect(
                board = value.board,
                diff = value.diff,
                createdNode = createdNode(value),
                resolvedRefs = refs,
            ),
        )
        is Res.Err -> Res.Err(ToolError.fromWorld(error))
    }
}
