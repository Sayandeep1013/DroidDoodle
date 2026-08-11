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
                ArgSpec("type", ArgType.NODE_TYPE, true, "what kind of thing it is"),
                ArgSpec("label", ArgType.STRING, true, "its name"),
                ArgSpec("kind", ArgType.STRING, false, "a free-text sub-type such as blacksmith"),
                ArgSpec("at", ArgType.PLACEMENT, false, "where to put it; defaults to automatic"),
                ArgSpec("attributes", ArgType.ATTR_MAP, false, "extra facts about it"),
                ArgSpec("color", ArgType.COLOR, false, "its colour"),
                ArgSpec("size", ArgType.SIZE, false, "its size"),
            ),
        ),
        ToolSchema(
            name = UPDATE_NODE,
            description = "change something that already exists",
            args = listOf(
                ArgSpec("node", ArgType.NODE_REF, true, "which thing to change"),
                ArgSpec("label", ArgType.STRING, false, "a new name"),
                ArgSpec("kind", ArgType.STRING, false, "a new sub-type"),
                ArgSpec("set", ArgType.ATTR_MAP, false, "facts to add or overwrite"),
                ArgSpec("unset", ArgType.STRING_LIST, false, "fact names to remove"),
                ArgSpec("color", ArgType.COLOR, false, "a new colour"),
                ArgSpec("size", ArgType.SIZE, false, "a new size"),
            ),
        ),
        ToolSchema(
            name = MOVE_NODE,
            description = "move something to a new position",
            args = listOf(
                ArgSpec("node", ArgType.NODE_REF, true, "which thing to move"),
                ArgSpec("to", ArgType.PLACEMENT, true, "where to move it"),
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
                ArgSpec("relation", ArgType.EDGE_TYPE, true, "how they are related"),
                ArgSpec("label", ArgType.STRING, false, "wording for a CUSTOM relation"),
            ),
        ),
        ToolSchema(
            name = DISCONNECT,
            description = "remove a relationship between two things",
            args = listOf(
                ArgSpec("from", ArgType.NODE_REF, true, "the subject"),
                ArgSpec("to", ArgType.NODE_REF, true, "the object"),
                ArgSpec("relation", ArgType.EDGE_TYPE, false, "which relation; omit to remove all"),
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
                ArgSpec("layout", ArgType.ARRANGE_LAYOUT, true, "the pattern to use"),
            ),
        ),
        ToolSchema(
            name = SET_SETTING,
            description = "change one of your own settings",
            args = listOf(
                ArgSpec("key", ArgType.SETTING_KEY, true, "which setting"),
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
