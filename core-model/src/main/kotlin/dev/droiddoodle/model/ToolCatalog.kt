package dev.droiddoodle.model

/**
 * The ten tool schemas.
 *
 * Schemas live here rather than in `:core-agent` so that `:core-grammar` can
 * emit from them without depending on the agent. Execution behaviour is added in
 * `:core-agent`; this is the declaration, and it is the single source of truth
 * behind the grammar, argument validation, and the prompt's tool block.
 *
 * The count is a budget, not a coincidence: every name and argument occupies
 * context on every turn, and a 1B model's selection accuracy degrades as the
 * menu grows. A new tool must justify its permanent token cost.
 *
 * See docs/21-tools.md.
 */
public object ToolCatalog {

    public const val CREATE_NODE: String = "create_node"
    public const val UPDATE_NODE: String = "update_node"
    public const val MOVE_NODE: String = "move_node"
    public const val DELETE_NODE: String = "delete_node"
    public const val CONNECT: String = "connect"
    public const val DISCONNECT: String = "disconnect"
    public const val FIND: String = "find"
    public const val ARRANGE: String = "arrange"
    public const val SET_SETTING: String = "set_setting"
    public const val RESPOND: String = "respond"

    public val ALL: List<ToolSchema> = listOf(
        ToolSchema(
            name = CREATE_NODE,
            description = "add a new thing to the board",
            args = listOf(
                ArgSpec("type", ArgType.NODE_TYPE, true, "PLACE, CHARACTER, OBJECT, NOTE, or GROUP"),
                ArgSpec("label", ArgType.STRING, true, "its name"),
                ArgSpec("kind", ArgType.STRING, false, "a free-text sub-type such as blacksmith"),
                ArgSpec(
                    "at", ArgType.PLACEMENT, false,
                    "{\"rel\":NORTH_OF|SOUTH_OF|EAST_OF|WEST_OF|NEXT_TO,\"ref\":id} or " +
                        "{\"cell\":{\"row\":r,\"col\":c}}; omit for automatic",
                ),
                ArgSpec("attributes", ArgType.ATTR_MAP, false, "extra facts, e.g. {\"secret\":\"vampire\"}"),
                ArgSpec("color", ArgType.COLOR, false, "RED, ORANGE, YELLOW, GREEN, BLUE, PURPLE, or GRAY"),
                ArgSpec("size", ArgType.SIZE, false, "SMALL, MEDIUM, or LARGE"),
            ),
        ),
        ToolSchema(
            name = UPDATE_NODE,
            description = "change something that already exists",
            args = listOf(
                ArgSpec("node", ArgType.NODE_REF, true, "which thing to change"),
                ArgSpec("label", ArgType.STRING, false, "a new name"),
                ArgSpec("kind", ArgType.STRING, false, "a new sub-type"),
                ArgSpec("set", ArgType.ATTR_MAP, false, "facts to add or overwrite, e.g. {\"secret\":\"vampire\"}"),
                ArgSpec("unset", ArgType.STRING_LIST, false, "fact names to remove"),
                ArgSpec("color", ArgType.COLOR, false, "RED, ORANGE, YELLOW, GREEN, BLUE, PURPLE, or GRAY"),
                ArgSpec("size", ArgType.SIZE, false, "SMALL, MEDIUM, or LARGE"),
            ),
        ),
        ToolSchema(
            name = MOVE_NODE,
            description = "move something to a new position",
            args = listOf(
                ArgSpec("node", ArgType.NODE_REF, true, "which thing to move"),
                ArgSpec(
                    "to", ArgType.PLACEMENT, true,
                    "{\"rel\":NORTH_OF|SOUTH_OF|EAST_OF|WEST_OF|NEXT_TO,\"ref\":id} or " +
                        "{\"cell\":{\"row\":r,\"col\":c}} or {\"auto\":true}",
                ),
            ),
        ),
        ToolSchema(
            name = DELETE_NODE,
            description = "remove something and everything inside it",
            args = listOf(
                ArgSpec("node", ArgType.NODE_REF, true, "which thing to remove"),
            ),
        ),
        ToolSchema(
            name = CONNECT,
            description = "create a relationship between two things",
            args = listOf(
                ArgSpec("from", ArgType.NODE_REF, true, "the subject"),
                ArgSpec("to", ArgType.NODE_REF, true, "the object"),
                // Named 'relation' rather than 'type' so it does not collide
                // with create_node's 'type' in the model's attention; small
                // models conflate identically-named arguments across tools.
                ArgSpec(
                    "relation", ArgType.EDGE_TYPE, true,
                    "CONTAINS, CONNECTS, KNOWS, FEARS, OWNS, BLOCKS, or CUSTOM",
                ),
                ArgSpec("label", ArgType.STRING, false, "wording for a CUSTOM relation"),
            ),
        ),
        ToolSchema(
            name = DISCONNECT,
            description = "remove a relationship between two things",
            args = listOf(
                ArgSpec("from", ArgType.NODE_REF, true, "the subject"),
                ArgSpec("to", ArgType.NODE_REF, true, "the object"),
                ArgSpec(
                    "relation", ArgType.EDGE_TYPE, false,
                    "CONTAINS, CONNECTS, KNOWS, FEARS, OWNS, BLOCKS, or CUSTOM; omit to remove all",
                ),
            ),
        ),
        ToolSchema(
            name = FIND,
            description = "look up things that are not currently listed; only as the first step",
            args = listOf(
                ArgSpec("text", ArgType.STRING, false, "words appearing in the name or sub-type"),
                ArgSpec("type", ArgType.NODE_TYPE, false, "restrict to one kind of thing"),
                ArgSpec("kind", ArgType.STRING, false, "restrict to one sub-type"),
                ArgSpec("attribute", ArgType.STRING, false, "a fact name, or name=value"),
            ),
            position = ToolPosition.FIRST_ONLY,
        ),
        ToolSchema(
            name = ARRANGE,
            description = "lay several things out in a pattern",
            args = listOf(
                ArgSpec("nodes", ArgType.NODE_REF_LIST, true, "which things to lay out"),
                ArgSpec(
                    "layout", ArgType.ARRANGE_LAYOUT, true,
                    "ROW, COLUMN, GRID, CLUSTER_LEFT, or CLUSTER_RIGHT",
                ),
            ),
        ),
        ToolSchema(
            name = SET_SETTING,
            description = "change one of your own settings",
            args = listOf(
                // The domain is exactly SettingsRegistry.AGENT_WRITABLE, restated
                // here because these are arbitrary namespaced strings a model has
                // no chance of spelling correctly by guessing -- unlike the other
                // enums above, there is no shorter description that still works.
                ArgSpec(
                    "key", ArgType.SETTING_KEY, true,
                    SettingsRegistry.AGENT_WRITABLE.joinToString(", ") { it.key },
                ),
                ArgSpec("value", ArgType.SETTING_VALUE, true, "the new value"),
            ),
        ),
        ToolSchema(
            name = RESPOND,
            description = "say something to the user; only needed for questions or refusals",
            args = listOf(
                ArgSpec("text", ArgType.STRING, true, "what to say"),
            ),
            position = ToolPosition.LAST_ONLY,
        ),
    )

    public val BY_NAME: Map<String, ToolSchema> = ALL.associateBy { it.name }
}
