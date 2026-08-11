package dev.droiddoodle.grammar

import dev.droiddoodle.model.ArgType
import dev.droiddoodle.model.ArrangeLayout
import dev.droiddoodle.model.EdgeType
import dev.droiddoodle.model.NodeColor
import dev.droiddoodle.model.NodeId
import dev.droiddoodle.model.NodeRef
import dev.droiddoodle.model.NodeSize
import dev.droiddoodle.model.NodeType
import dev.droiddoodle.model.Relation
import dev.droiddoodle.model.Res
import dev.droiddoodle.model.ToolPosition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Verifies that a plan envelope is one the grammar could have produced.
 *
 * Deliberately **not** a GBNF interpreter. It checks the envelope against the
 * same [GrammarSpec] the grammar is emitted from -- same source of truth, same
 * verdict for every plan we care about, at a small fraction of the cost of
 * writing a grammar engine.
 *
 * The gap this leaves is real and is closed on device in P8: this proves the
 * schema forbids a construct, not that llama.cpp's sampler does.
 * See docs/25-inference.md §2.
 */
public class PlanEnvelopeChecker(private val spec: GrammarSpec) {

    private val json = Json { ignoreUnknownKeys = false; isLenient = false }
    private val toolsByName = spec.tools.associateBy { it.name }

    public fun check(output: String): Res<PlanEnvelope, String> {
        val envelope = try {
            json.decodeFromString(PlanEnvelope.serializer(), output)
        } catch (e: Exception) {
            return Res.Err("not a valid plan envelope: ${e.message}")
        }

        if (envelope.steps.isEmpty()) return Res.Err("a plan must contain at least one step")

        val lastIndex = envelope.steps.lastIndex
        for ((index, step) in envelope.steps.withIndex()) {
            val tool = toolsByName[step.tool]
                ?: return Res.Err("step ${index + 1}: no tool called '${step.tool}'")

            when (tool.position) {
                ToolPosition.FIRST_ONLY -> if (index != 0) {
                    return Res.Err("step ${index + 1}: '${tool.name}' may only be the first step")
                }
                ToolPosition.LAST_ONLY -> if (index != lastIndex) {
                    return Res.Err("step ${index + 1}: '${tool.name}' may only be the last step")
                }
                ToolPosition.ANY -> Unit
            }

            checkArgs(index, tool.name, step)?.let { return Res.Err(it) }
        }

        return Res.Ok(envelope)
    }

    private fun checkArgs(index: Int, toolName: String, step: PlanStep): String? {
        val tool = toolsByName.getValue(toolName)
        val where = "step ${index + 1} ($toolName)"

        for (arg in tool.args) {
            if (arg.required && arg.name !in step.args) {
                return "$where: missing required argument '${arg.name}'"
            }
        }
        for (name in step.args.keys) {
            val arg = tool.arg(name) ?: return "$where: unknown argument '$name'"
            val problem = checkValue(arg.type, step.args.getValue(name))
            if (problem != null) return "$where: argument '$name' $problem"
        }
        // Mirrors the grammar's omission of the empty branch for all-optional
        // tools: at least one argument must be present.
        if (tool.args.isNotEmpty() && tool.args.none { it.required } && step.args.isEmpty()) {
            return "$where: needs at least one argument"
        }
        return null
    }

    private fun checkValue(type: ArgType, value: JsonElement): String? = when (type) {
        ArgType.STRING, ArgType.SETTING_VALUE -> requireString(value)

        ArgType.NODE_REF -> requireString(value) ?: checkNodeRef(asString(value))

        ArgType.NODE_REF_LIST -> {
            val array = value as? JsonArray ?: return "must be a list"
            if (array.isEmpty()) {
                "must not be empty"
            } else {
                array.firstNotNullOfOrNull { element ->
                    requireString(element) ?: checkNodeRef(asString(element))
                }
            }
        }

        ArgType.NODE_TYPE -> requireEnum(value, NodeType.entries.map { it.name })
        ArgType.EDGE_TYPE -> requireEnum(value, EdgeType.entries.map { it.name })
        ArgType.COLOR -> requireEnum(value, NodeColor.entries.map { it.name })
        ArgType.SIZE -> requireEnum(value, NodeSize.entries.map { it.name })
        ArgType.ARRANGE_LAYOUT -> requireEnum(value, ArrangeLayout.entries.map { it.name })
        ArgType.SETTING_KEY -> requireEnum(value, spec.agentWritableSettingKeys)

        ArgType.PLACEMENT -> checkPlacement(value)

        ArgType.ATTR_MAP -> {
            val obj = value as? JsonObject ?: return "must be an object"
            obj.values.firstNotNullOfOrNull { requireString(it) }
        }

        ArgType.STRING_LIST -> {
            val array = value as? JsonArray ?: return "must be a list"
            array.firstNotNullOfOrNull { requireString(it) }
        }
    }

    private fun checkPlacement(value: JsonElement): String? {
        val obj = value as? JsonObject ?: return "must be a placement object"
        return when {
            "rel" in obj -> {
                val rel = obj["rel"] ?: return "is missing 'rel'"
                val ref = obj["ref"] ?: return "is missing 'ref'"
                requireEnum(rel, Relation.entries.map { it.name })
                    ?: requireString(ref)
                    ?: checkNodeRef(asString(ref))
            }

            "cell" in obj -> {
                val cell = obj["cell"] as? JsonObject ?: return "'cell' must be an object"
                val row = (cell["row"] as? JsonPrimitive)?.content?.toIntOrNull()
                val col = (cell["col"] as? JsonPrimitive)?.content?.toIntOrNull()
                if (row == null || col == null) "'cell' needs integer row and col" else null
            }

            "auto" in obj -> null

            else -> "must be one of {rel,ref}, {cell}, or {auto}"
        }
    }

    private fun checkNodeRef(raw: String): String? =
        when (val ref = NodeRef.parseOrNull(raw)) {
            null -> "'$raw' is not a node reference"
            is NodeRef.Existing -> if (ref.id in spec.existingIds) {
                null
            } else {
                "'$raw' is not a node on the board"
            }
            is NodeRef.Step -> if (ref.step in 1 until spec.maxSteps.coerceAtLeast(2)) {
                null
            } else {
                "'$raw' is outside the step range"
            }
        }

    private fun requireString(value: JsonElement): String? {
        val primitive = value as? JsonPrimitive
        return if (primitive == null || !primitive.isString) "must be a string" else null
    }

    private fun requireEnum(value: JsonElement, allowed: List<String>): String? {
        requireString(value)?.let { return it }
        val content = asString(value)
        return if (content in allowed) null else "'$content' is not one of ${allowed.joinToString(", ")}"
    }

    private fun asString(value: JsonElement): String = (value as JsonPrimitive).content
}
