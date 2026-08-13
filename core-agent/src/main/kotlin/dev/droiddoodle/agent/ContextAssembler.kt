package dev.droiddoodle.agent

import dev.droiddoodle.model.EdgeType
import dev.droiddoodle.model.Limits
import dev.droiddoodle.model.Node
import dev.droiddoodle.model.NodeColor
import dev.droiddoodle.model.NodeId
import dev.droiddoodle.model.Res
import dev.droiddoodle.model.SettingKeys
import dev.droiddoodle.model.ToolSchema
import dev.droiddoodle.world.Board
import kotlin.math.abs

public data class AssembledPrompt(
    public val text: String,
    public val blockTokens: Map<String, Int>,
    public val shedBlocks: List<String>,
) {
    public val totalTokens: Int get() = blockTokens.values.sum()
}

/**
 * Builds the six-block prompt of docs/22-context.md.
 *
 * A pure function of its inputs: no clock reads, no iteration over unordered
 * collections, no ambient state. Identical inputs must produce a byte-identical
 * prompt, which is what makes snapshot tests a real regression signal.
 */
public class ContextAssembler(
    private val tools: List<ToolSchema>,
    private val tokenCount: (String) -> Int,
) {

    public fun assemble(
        request: TurnRequest,
        maxContextTokens: Int,
        extraBlock: String? = null,
    ): Res<AssembledPrompt, String> {
        val userMessage = request.userMessage.trim()
        if (userMessage.length > Limits.USER_MESSAGE_MAX) {
            // Rejected rather than truncated: a truncated instruction produces a
            // confidently wrong plan.
            return Res.Err(
                "message is ${userMessage.length} characters; the limit is " +
                    "${Limits.USER_MESSAGE_MAX}",
            )
        }

        val system = SYSTEM_RULES
        val toolBlock = tools.joinToString("\n") { it.renderForPrompt() }
        val digestMax = request.settings.int(SettingKeys.AGENT_DIGEST_MAX_NODES)
        var historyTurns = request.settings.int(SettingKeys.AGENT_HISTORY_TURNS)
        var digestLimit = digestMax
        val shed = mutableListOf<String>()

        // Blocks are shed in a fixed priority order. Tool descriptions and
        // system rules are never shed: a model missing part of its tool menu
        // produces confidently wrong calls, which is worse than refusing.
        while (true) {
            val digest = renderDigest(request, digestLimit)
            val refs = request.refs.render()
            val history = renderHistory(request.history, historyTurns)
            val blocks = linkedMapOf(
                "system" to system,
                "tools" to toolBlock,
                "board" to digest,
                "refs" to (refs ?: ""),
                "history" to history,
                "extra" to (extraBlock ?: ""),
                "user" to "> $userMessage",
            )
            val text = blocks.values.filter { it.isNotEmpty() }.joinToString("\n\n")
            val counts = blocks.mapValues { (_, v) -> if (v.isEmpty()) 0 else tokenCount(v) }
            val total = counts.values.sum()

            if (total <= maxContextTokens) {
                return Res.Ok(AssembledPrompt(text, counts, shed.toList()))
            }
            if (historyTurns > 0) {
                historyTurns--
                shed += "history"
                continue
            }
            if (digestLimit > MIN_DIGEST_NODES) {
                digestLimit = (digestLimit - 5).coerceAtLeast(MIN_DIGEST_NODES)
                shed += "board"
                continue
            }
            return Res.Err(
                "context needs $total tokens but only $maxContextTokens are available",
            )
        }
    }

    // ---- block 3: the viewport digest ------------------------------------

    /**
     * A compact line format, not JSON. Braces, quotes and repeated key names
     * cost roughly 40% more tokens for identical information.
     */
    internal fun renderDigest(request: TurnRequest, limit: Int): String {
        val board = request.board
        if (board.isEmpty) return "board: empty"

        val inViewport = board.nodes.values
            .filter { it.cell in request.viewport }
            .sortedWith(compareBy({ it.cell.row }, { it.cell.col }))

        val referenced = request.refs.referencedIds
            .mapNotNull { board.node(it) }
            .filterNot { it.cell in request.viewport }

        val ordered = inViewport + referenced
        val kept = ordered.take(limit)
        val dropped = ordered.size - kept.size

        val lines = kept.map { node ->
            renderNode(node, starred = node.cell !in request.viewport)
        }.toMutableList()

        val keptIds = kept.map { it.id }.toSet()
        renderEdges(board, keptIds)?.let { lines += it }

        if (dropped > 0) {
            lines += "… $dropped more nodes off-view. use find to locate them."
        }
        return lines.joinToString("\n")
    }

    private fun renderNode(node: Node, starred: Boolean): String = buildString {
        append(node.id.value).append(' ').append(node.type.digestTag)
        append(" \"").append(node.label).append('"')
        if (node.kind.isNotBlank()) append(" ~").append(node.kind)
        append(" @").append(node.cell.row).append(',').append(node.cell.col)
        if (node.attributes.isNotEmpty()) {
            append(" {")
            append(
                node.attributes.entries
                    .sortedBy { it.key }
                    .joinToString(",") { "${it.key}=${it.value}" },
            )
            append('}')
        }
        // size is never included: it is presentation-only and the model has no
        // reason to reason about it.
        if (node.style.color != NodeColor.DEFAULT) {
            append(" #").append(node.style.color.name.lowercase())
        }
        if (starred) append(" *")
    }

    /**
     * Only edges with both endpoints present. A dangling half-edge would invite
     * the model to reference an id it cannot see.
     */
    private fun renderEdges(board: Board, visible: Set<NodeId>): String? {
        val rendered = board.edges.values
            .filter { it.from in visible && it.to in visible }
            .sortedBy { it.id.value }
            .map { edge ->
                val arrow = if (edge.type == EdgeType.CONNECTS) "-" else ">"
                val name = if (edge.type == EdgeType.CUSTOM) {
                    edge.label.ifBlank { "custom" }
                } else {
                    edge.type.name.lowercase()
                }
                "${edge.from}$arrow${edge.to} $name"
            }
        return if (rendered.isEmpty()) null else "e " + rendered.joinToString(", ")
    }

    // ---- block 5: recent turns -------------------------------------------

    /**
     * Outcomes are summarised from the executed diff, not from the model's own
     * output. Feeding a model its prior generations back compounds its
     * mistakes; feeding it what actually happened corrects them.
     */
    private fun renderHistory(history: List<TurnSummary>, turns: Int): String {
        if (turns <= 0 || history.isEmpty()) return ""
        return history.takeLast(turns).joinToString("\n") { summary ->
            "> ${summary.userMessage}\n  ${summary.outcomeLine}"
        }
    }

    public companion object {
        internal const val MIN_DIGEST_NODES: Int = 5

        /**
         * Three worked examples, added after the first on-device Prompt Suite
         * runs showed the specific reasoning failure this doc originally
         * deferred on: under grammar-constrained decoding a 1B model reliably
         * produces *syntactically* valid JSON with no example to imitate, but
         * guesses the *shape* wrong almost every time -- {"auto":true} instead
         * of a relative placement, {"attribute":"x","value":"y"} instead of a
         * flat fact map, one create_node step instead of the several a compound
         * request needs. Grammar constraints guarantee the output parses; they
         * say nothing about which of the parseable outputs the model reaches
         * for. See docs/22-context.md §2 and results/README.md.
         */
        internal val SYSTEM_RULES: String = """
            You edit a grid board by emitting a plan of tool calls as JSON.
            Reply with the plan only.
            Use ids exactly as they appear in the board listing below.
            Refer to a node created earlier in the same plan as ${'$'}1, ${'$'}2, and so on,
            numbered by its step position.
            Omit any optional argument you do not want to change.
            A request naming several things is several steps in one plan, not one.
            Only use respond, alone, to ask a question or explain a refusal.

            Example: "create a village with a tavern in it" ->
            {"steps":[
            {"tool":"create_node","args":{"type":"PLACE","label":"Village"}},
            {"tool":"create_node","args":{"type":"PLACE","label":"Tavern","at":{"rel":"NEXT_TO","ref":"${'$'}1"}}},
            {"tool":"connect","args":{"from":"${'$'}1","to":"${'$'}2","relation":"CONTAINS"}}]}
            Example: "make the blacksmith secretly a vampire" ->
            {"steps":[{"tool":"update_node","args":{"node":"n3","set":{"secret":"vampire"}}}]}
            Example: "what's the weather" ->
            {"steps":[{"tool":"respond","args":{"text":"I only edit this board."}}]}
        """.trimIndent()
    }
}

/** Distance from the viewport centre, used when shedding digest entries. */
internal fun Node.distanceFrom(row: Int, col: Int): Int =
    abs(cell.row - row) + abs(cell.col - col)
