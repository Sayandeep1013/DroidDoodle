package dev.droiddoodle.model

public enum class SettingType { BOOL, INT, FLOAT, ENUM, STRING }

public data class SettingDef(
    public val key: String,
    public val type: SettingType,
    public val default: String,
    public val min: Double? = null,
    public val max: Double? = null,
    public val options: List<String> = emptyList(),
    public val agentWritable: Boolean = false,
    public val requiresReload: Boolean = false,
    public val description: String,
)

public object SettingKeys {
    public const val MODEL_ID: String = "model.id"
    public const val MODEL_TEMPERATURE: String = "model.temperature"
    public const val MODEL_TOP_P: String = "model.top_p"
    public const val MODEL_MAX_TOKENS: String = "model.max_tokens"
    public const val MODEL_CONTEXT_TOKENS: String = "model.context_tokens"
    public const val MODEL_THREADS: String = "model.threads"
    public const val AGENT_LOOP_STRATEGY: String = "agent.loop_strategy"
    public const val AGENT_MAX_STEPS: String = "agent.max_steps"
    public const val AGENT_AUTO_REPAIR: String = "agent.auto_repair"
    public const val AGENT_CONFIRM_THRESHOLD: String = "agent.confirm_threshold"
    public const val AGENT_DIGEST_MAX_NODES: String = "agent.digest_max_nodes"
    public const val AGENT_HISTORY_TURNS: String = "agent.history_turns"
    public const val UI_THEME: String = "ui.theme"
    public const val UI_GRID_VISIBLE: String = "ui.grid_visible"
    public const val UI_CELL_SIZE: String = "ui.cell_size"
    public const val TRACE_ENABLED: String = "trace.enabled"
    public const val TRACE_RETAIN_TURNS: String = "trace.retain_turns"
}

/**
 * Settings are not incidental configuration: they are the world the
 * `set_setting` tool acts on. The registry is the single source of truth for
 * the settings UI, persistence, validation, and the `set_setting` grammar enum.
 *
 * See docs/26-settings.md.
 */
public object SettingsRegistry {

    public val ALL: List<SettingDef> = listOf(
        SettingDef(
            key = SettingKeys.MODEL_ID,
            type = SettingType.ENUM,
            default = "",
            // Options come from the downloaded-model manifest at runtime. Empty
            // is the sentinel that triggers the first-run picker.
            agentWritable = false,
            requiresReload = true,
            description = "Which local model to run",
        ),
        SettingDef(
            key = SettingKeys.MODEL_TEMPERATURE,
            type = SettingType.FLOAT,
            default = "0.3",
            min = 0.0,
            max = 1.5,
            agentWritable = true,
            description = "Sampling temperature; lower is more literal",
        ),
        SettingDef(
            key = SettingKeys.MODEL_TOP_P,
            type = SettingType.FLOAT,
            default = "0.9",
            min = 0.1,
            max = 1.0,
            agentWritable = true,
            description = "Nucleus sampling threshold",
        ),
        SettingDef(
            key = SettingKeys.MODEL_MAX_TOKENS,
            type = SettingType.INT,
            default = "384",
            min = 64.0,
            max = 1024.0,
            description = "Maximum tokens generated per turn",
        ),
        SettingDef(
            key = SettingKeys.MODEL_CONTEXT_TOKENS,
            type = SettingType.INT,
            default = "4096",
            min = 1024.0,
            max = 8192.0,
            requiresReload = true,
            description = "Model context window size",
        ),
        SettingDef(
            key = SettingKeys.MODEL_THREADS,
            type = SettingType.INT,
            default = "0",
            min = 0.0,
            max = 8.0,
            requiresReload = true,
            description = "Inference threads; 0 selects automatically",
        ),
        SettingDef(
            key = SettingKeys.AGENT_LOOP_STRATEGY,
            type = SettingType.ENUM,
            default = "plan_then_execute",
            options = listOf("plan_then_execute", "react", "single_shot"),
            description = "How the agent turns a message into tool calls",
        ),
        SettingDef(
            key = SettingKeys.AGENT_MAX_STEPS,
            type = SettingType.INT,
            default = "8",
            min = 1.0,
            max = 12.0,
            agentWritable = true,
            description = "Maximum tool calls in one plan",
        ),
        SettingDef(
            key = SettingKeys.AGENT_AUTO_REPAIR,
            type = SettingType.BOOL,
            default = "false",
            agentWritable = true,
            description = "Automatically re-plan once after a failed step",
        ),
        SettingDef(
            key = SettingKeys.AGENT_CONFIRM_THRESHOLD,
            type = SettingType.INT,
            default = "3",
            min = 0.0,
            max = 20.0,
            agentWritable = true,
            description = "Ask before deleting more than this many nodes",
        ),
        SettingDef(
            key = SettingKeys.AGENT_DIGEST_MAX_NODES,
            type = SettingType.INT,
            default = "25",
            min = 5.0,
            max = 50.0,
            agentWritable = true,
            description = "Maximum nodes described to the model each turn",
        ),
        SettingDef(
            key = SettingKeys.AGENT_HISTORY_TURNS,
            type = SettingType.INT,
            default = "2",
            min = 0.0,
            max = 6.0,
            agentWritable = true,
            description = "How many previous turns to include in context",
        ),
        SettingDef(
            key = SettingKeys.UI_THEME,
            type = SettingType.ENUM,
            default = "system",
            options = listOf("system", "light", "dark"),
            agentWritable = true,
            description = "Colour theme",
        ),
        SettingDef(
            key = SettingKeys.UI_GRID_VISIBLE,
            type = SettingType.BOOL,
            default = "true",
            agentWritable = true,
            description = "Show grid lines on the canvas",
        ),
        SettingDef(
            key = SettingKeys.UI_CELL_SIZE,
            type = SettingType.ENUM,
            default = "medium",
            options = listOf("small", "medium", "large"),
            agentWritable = true,
            description = "How large each grid cell is drawn",
        ),
        // trace.* is deliberately not agent-writable. An agent able to disable
        // its own observability defeats the project's primary purpose.
        SettingDef(
            key = SettingKeys.TRACE_ENABLED,
            type = SettingType.BOOL,
            default = "true",
            description = "Record a trace for every turn",
        ),
        SettingDef(
            key = SettingKeys.TRACE_RETAIN_TURNS,
            type = SettingType.INT,
            default = "200",
            min = 20.0,
            max = 1000.0,
            description = "How many turns of trace history to keep",
        ),
    )

    public val BY_KEY: Map<String, SettingDef> = ALL.associateBy { it.key }

    /** Exactly the keys the `set_setting` grammar enum is built from. */
    public val AGENT_WRITABLE: List<SettingDef> = ALL.filter { it.agentWritable }

    public val DEFAULTS: Map<String, String> = ALL.associate { it.key to it.default }

    public fun definition(key: String): SettingDef? = BY_KEY[key]

    /**
     * Validation order: key exists, key is agent-writable when the write came
     * from the model, value parses as the declared type, value is in range or
     * among options.
     */
    public fun validate(key: String, value: String, fromAgent: Boolean): Res<String, ToolError> {
        val def = BY_KEY[key]
            ?: return Res.Err(
                ToolError(ToolErrorCode.UNKNOWN_SETTING, "no setting named '$key'"),
            )

        if (fromAgent && !def.agentWritable) {
            return Res.Err(
                ToolError(
                    ToolErrorCode.SETTING_NOT_AGENT_WRITABLE,
                    "'$key' cannot be changed by the agent",
                ),
            )
        }

        return when (def.type) {
            SettingType.BOOL -> when (value.lowercase()) {
                "true", "false" -> Res.Ok(value.lowercase())
                else -> Res.Err(
                    ToolError(
                        ToolErrorCode.SETTING_OUT_OF_RANGE,
                        "'$key' must be true or false, got '$value'",
                    ),
                )
            }

            SettingType.INT -> {
                val n = value.toIntOrNull()
                    ?: return Res.Err(
                        ToolError(
                            ToolErrorCode.SETTING_OUT_OF_RANGE,
                            "'$key' must be a whole number, got '$value'",
                        ),
                    )
                checkRange(def, n.toDouble())?.let { return Res.Err(it) }
                Res.Ok(n.toString())
            }

            SettingType.FLOAT -> {
                val n = value.toDoubleOrNull()
                    ?: return Res.Err(
                        ToolError(
                            ToolErrorCode.SETTING_OUT_OF_RANGE,
                            "'$key' must be a number, got '$value'",
                        ),
                    )
                checkRange(def, n)?.let { return Res.Err(it) }
                Res.Ok(n.toString())
            }

            SettingType.ENUM -> {
                // model.id draws its options from the runtime manifest, so an
                // empty option list means "cannot be validated here".
                if (def.options.isNotEmpty() && value !in def.options) {
                    Res.Err(
                        ToolError(
                            ToolErrorCode.SETTING_OUT_OF_RANGE,
                            "'$key' must be one of ${def.options.joinToString(", ")}, got '$value'",
                        ),
                    )
                } else {
                    Res.Ok(value)
                }
            }

            SettingType.STRING -> Res.Ok(value)
        }
    }

    private fun checkRange(def: SettingDef, n: Double): ToolError? {
        val lo = def.min
        val hi = def.max
        if (lo != null && n < lo) {
            return ToolError(
                ToolErrorCode.SETTING_OUT_OF_RANGE,
                "'${def.key}' must be between ${fmt(lo)} and ${fmt(hi ?: lo)}, got ${fmt(n)}",
            )
        }
        if (hi != null && n > hi) {
            return ToolError(
                ToolErrorCode.SETTING_OUT_OF_RANGE,
                "'${def.key}' must be between ${fmt(lo ?: hi)} and ${fmt(hi)}, got ${fmt(n)}",
            )
        }
        return null
    }

    private fun fmt(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
}

/**
 * An immutable settings view handed to the core modules, per architecture rule
 * R4. A snapshot is taken once at the start of a turn and used for the whole
 * turn, so a `set_setting` step never changes the rules mid-plan.
 */
public class SettingsSnapshot(overrides: Map<String, String> = emptyMap()) {

    public val values: Map<String, String> = SettingsRegistry.DEFAULTS + overrides

    public fun string(key: String): String =
        values[key] ?: SettingsRegistry.BY_KEY[key]?.default.orEmpty()

    public fun int(key: String): Int =
        string(key).toIntOrNull()
            ?: SettingsRegistry.BY_KEY[key]?.default?.toIntOrNull()
            ?: 0

    public fun float(key: String): Float =
        string(key).toFloatOrNull()
            ?: SettingsRegistry.BY_KEY[key]?.default?.toFloatOrNull()
            ?: 0f

    public fun bool(key: String): Boolean = string(key).equals("true", ignoreCase = true)

    public fun with(key: String, value: String): SettingsSnapshot =
        SettingsSnapshot(values + (key to value))

    override fun equals(other: Any?): Boolean =
        other is SettingsSnapshot && other.values == values

    override fun hashCode(): Int = values.hashCode()

    public companion object {
        public val DEFAULTS: SettingsSnapshot = SettingsSnapshot()
    }
}
