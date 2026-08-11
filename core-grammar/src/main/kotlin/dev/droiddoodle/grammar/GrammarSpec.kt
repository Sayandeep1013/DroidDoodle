package dev.droiddoodle.grammar

import dev.droiddoodle.model.ArgSpec
import dev.droiddoodle.model.NodeId
import dev.droiddoodle.model.ToolSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Everything the grammar depends on for a single turn.
 *
 * [existingIds] changes every turn, which is why the grammar is rebuilt every
 * turn rather than cached: enumerating the live ids as literal alternatives is
 * what makes a hallucinated node id unrepresentable rather than merely rejected.
 * See docs/21-tools.md §2.
 */
public data class GrammarSpec(
    public val tools: List<ToolSchema>,
    public val existingIds: List<NodeId>,
    public val maxSteps: Int,
    public val agentWritableSettingKeys: List<String>,
) {
    /**
     * Required arguments first, then optional ones, each preserving declared
     * order. Optional arguments are emitted as `("," …)?` groups, which is only
     * expressible when they all follow the required ones.
     */
    public fun orderedArgs(tool: ToolSchema): List<ArgSpec> =
        tool.args.filter { it.required } + tool.args.filter { !it.required }
}

@Serializable
public data class PlanEnvelope(
    public val steps: List<PlanStep>,
)

@Serializable
public data class PlanStep(
    public val tool: String,
    public val args: JsonObject = JsonObject(emptyMap()),
)
