package dev.droiddoodle.model

public enum class WorldErrorCode {
    OUT_OF_BOUNDS,
    CELL_OCCUPIED,
    NO_FREE_CELL,
    UNKNOWN_REF,
    UNKNOWN_NODE,
    UNKNOWN_EDGE,
    DUPLICATE_EDGE,
    SELF_EDGE,
    CONTAINMENT_CYCLE,
    ALREADY_CONTAINED,
    CONTAINMENT_TOO_DEEP,
    BOARD_FULL,
    INVALID_FIELD,
    ARRANGE_BLOCKED,
}

/**
 * Errors carry a message as well as a code because the message is fed back to
 * the model verbatim on a repair turn. It is written for a small model to act
 * on -- `"cell r1c2 is taken by n4"` rather than `"CELL_OCCUPIED"`.
 * See docs/20-world-model.md §9.
 */
public data class WorldError(
    public val code: WorldErrorCode,
    public val message: String,
) {
    override fun toString(): String = "${code.name}: $message"
}

public enum class ToolErrorCode {
    INVALID_ARGS,
    UNRESOLVED_STEP_REF,
    UNKNOWN_SETTING,
    SETTING_NOT_AGENT_WRITABLE,
    SETTING_OUT_OF_RANGE,
    CONFIRMATION_REQUIRED,
    RETRIEVAL_EXHAUSTED,
    WORLD_ERROR,
}

public data class ToolError(
    public val code: ToolErrorCode,
    public val message: String,
    public val worldError: WorldError? = null,
) {
    override fun toString(): String = "${code.name}: $message"

    public companion object {
        public fun fromWorld(e: WorldError): ToolError =
            ToolError(ToolErrorCode.WORLD_ERROR, e.message, e)
    }
}
