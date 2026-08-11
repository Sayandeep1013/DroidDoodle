package dev.droiddoodle.model

/**
 * Field limits from docs/20-world-model.md §2, chosen to bound the context
 * budget in docs/22-context.md.
 *
 * Exceeding a limit is always a validation error, never a silent truncation.
 * Truncating would make the trace lie about what happened, which defeats the
 * point of having a trace.
 */
public object Limits {
    public const val LABEL_MIN: Int = 1
    public const val LABEL_MAX: Int = 48
    public const val KIND_MAX: Int = 32
    public const val ATTRS_MAX: Int = 12
    public const val ATTR_KEY_MIN: Int = 1
    public const val ATTR_KEY_MAX: Int = 24
    public const val ATTR_VALUE_MAX: Int = 96
    public const val EDGE_LABEL_MIN: Int = 1
    public const val EDGE_LABEL_MAX: Int = 32
    public const val RESPOND_MAX: Int = 280
    public const val USER_MESSAGE_MAX: Int = 512
    public const val BOARD_MAX_NODES: Int = 200
    public const val ARRANGE_MAX_NODES: Int = 50
    public const val UNDO_DEPTH: Int = 20
    public const val CONTAINMENT_MAX_DEPTH: Int = 4
    public const val RELATIVE_MAX_DISTANCE: Int = 4
    public const val AUTO_MAX_RADIUS: Int = 8

    private val ATTR_KEY_PATTERN = Regex("^[a-z0-9_]+$")

    /** Attribute keys are normalised on write, so `"Secret Identity"` becomes `"secret_identity"`. */
    public fun normalizeAttrKey(raw: String): String =
        raw.trim().lowercase().replace(Regex("[\\s-]+"), "_")

    /** Returns a human-readable problem description, or null when valid. */
    public fun checkLabel(label: String): String? {
        val trimmed = label.trim()
        return when {
            trimmed.length < LABEL_MIN -> "label must not be blank"
            trimmed.length > LABEL_MAX -> "label must be at most $LABEL_MAX characters, was ${trimmed.length}"
            else -> null
        }
    }

    public fun checkKind(kind: String): String? =
        if (kind.length > KIND_MAX) {
            "kind must be at most $KIND_MAX characters, was ${kind.length}"
        } else {
            null
        }

    public fun checkEdgeLabel(type: EdgeType, label: String): String? {
        val trimmed = label.trim()
        return when {
            type == EdgeType.CUSTOM && trimmed.length < EDGE_LABEL_MIN ->
                "a CUSTOM relation requires a label"
            trimmed.length > EDGE_LABEL_MAX ->
                "relation label must be at most $EDGE_LABEL_MAX characters, was ${trimmed.length}"
            else -> null
        }
    }

    public fun checkAttributes(attributes: Map<String, String>): String? {
        if (attributes.size > ATTRS_MAX) {
            return "at most $ATTRS_MAX attributes allowed, was ${attributes.size}"
        }
        for ((key, value) in attributes) {
            if (key.length < ATTR_KEY_MIN || key.length > ATTR_KEY_MAX) {
                return "attribute key '$key' must be $ATTR_KEY_MIN-$ATTR_KEY_MAX characters"
            }
            if (!ATTR_KEY_PATTERN.matches(key)) {
                return "attribute key '$key' must contain only lowercase letters, digits and underscores"
            }
            if (value.length > ATTR_VALUE_MAX) {
                return "attribute '$key' value must be at most $ATTR_VALUE_MAX characters, was ${value.length}"
            }
        }
        return null
    }

    public fun checkRespondText(text: String): String? {
        val trimmed = text.trim()
        return when {
            trimmed.isEmpty() -> "respond text must not be blank"
            trimmed.length > RESPOND_MAX -> "respond text must be at most $RESPOND_MAX characters, was ${trimmed.length}"
            else -> null
        }
    }
}
