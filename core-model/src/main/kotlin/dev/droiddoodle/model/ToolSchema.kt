package dev.droiddoodle.model

public enum class ArgType {
    STRING,
    NODE_REF,
    NODE_REF_LIST,
    NODE_TYPE,
    EDGE_TYPE,
    COLOR,
    SIZE,
    PLACEMENT,
    ARRANGE_LAYOUT,
    ATTR_MAP,
    STRING_LIST,
    SETTING_KEY,
    SETTING_VALUE,
}

/**
 * Where a tool may appear in a plan.
 *
 * This is not decoration: it is compiled directly into the grammar, so `find`
 * anywhere but first and `respond` anywhere but last are unrepresentable rather
 * than merely rejected. See docs/25-inference.md §3.
 */
public enum class ToolPosition { ANY, FIRST_ONLY, LAST_ONLY }

public data class ArgSpec(
    public val name: String,
    public val type: ArgType,
    public val required: Boolean,
    public val description: String,
)

/**
 * The single source of truth for a tool.
 *
 * Three artefacts are derived from this and none is hand-written: the GBNF
 * grammar, runtime argument validation, and the tool descriptions rendered into
 * the prompt. A hand-maintained grammar drifts from its tool the first time an
 * argument is renamed, and the resulting failure -- a model emitting a
 * valid-looking call the executor rejects -- is slow and confusing to diagnose.
 *
 * This is intent criterion L5. See docs/21-tools.md §5.
 */
public data class ToolSchema(
    public val name: String,
    public val description: String,
    public val args: List<ArgSpec>,
    public val position: ToolPosition = ToolPosition.ANY,
) {
    public fun arg(name: String): ArgSpec? = args.firstOrNull { it.name == name }

    /**
     * One line per tool plus one line per argument, for prompt block 2.
     *
     * Enum domains are deliberately omitted: the grammar already makes
     * out-of-domain values impossible to emit, and restating them would roughly
     * double the block. See docs/22-context.md §3.
     */
    public fun renderForPrompt(): String = buildString {
        append(name).append(": ").append(description)
        for (a in args) {
            append("\n  ").append(a.name)
            if (!a.required) append("?")
            append(" - ").append(a.description)
        }
    }
}
