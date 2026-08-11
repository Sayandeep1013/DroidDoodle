package dev.droiddoodle.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Table-driven conformance test against the settings table in
 * docs/26-settings.md §2. If the spec table and this table disagree, one of them
 * is wrong and the build says so.
 */
class SettingsRegistryTest {

    private data class Expected(
        val key: String,
        val type: SettingType,
        val default: String,
        val min: Double?,
        val max: Double?,
        val agentWritable: Boolean,
        val requiresReload: Boolean,
    )

    private val spec = listOf(
        Expected("model.id", SettingType.ENUM, "", null, null, false, true),
        Expected("model.temperature", SettingType.FLOAT, "0.3", 0.0, 1.5, true, false),
        Expected("model.top_p", SettingType.FLOAT, "0.9", 0.1, 1.0, true, false),
        Expected("model.max_tokens", SettingType.INT, "384", 64.0, 1024.0, false, false),
        Expected("model.context_tokens", SettingType.INT, "4096", 1024.0, 8192.0, false, true),
        Expected("model.threads", SettingType.INT, "0", 0.0, 8.0, false, true),
        Expected("agent.loop_strategy", SettingType.ENUM, "plan_then_execute", null, null, false, false),
        Expected("agent.max_steps", SettingType.INT, "8", 1.0, 12.0, true, false),
        Expected("agent.auto_repair", SettingType.BOOL, "false", null, null, true, false),
        Expected("agent.confirm_threshold", SettingType.INT, "3", 0.0, 20.0, true, false),
        Expected("agent.digest_max_nodes", SettingType.INT, "25", 5.0, 50.0, true, false),
        Expected("agent.history_turns", SettingType.INT, "2", 0.0, 6.0, true, false),
        Expected("ui.theme", SettingType.ENUM, "system", null, null, true, false),
        Expected("ui.grid_visible", SettingType.BOOL, "true", null, null, true, false),
        Expected("ui.cell_size", SettingType.ENUM, "medium", null, null, true, false),
        Expected("trace.enabled", SettingType.BOOL, "true", null, null, false, false),
        Expected("trace.retain_turns", SettingType.INT, "200", 20.0, 1000.0, false, false),
    )

    @Test
    fun `registry matches the specification table exactly`() {
        assertEquals(spec.size, SettingsRegistry.ALL.size, "registry size differs from spec")
        for (e in spec) {
            val def = SettingsRegistry.definition(e.key)
            assertNotNull(def, "missing setting '${e.key}'")
            assertEquals(e.type, def.type, "${e.key} type")
            assertEquals(e.default, def.default, "${e.key} default")
            assertEquals(e.min, def.min, "${e.key} min")
            assertEquals(e.max, def.max, "${e.key} max")
            assertEquals(e.agentWritable, def.agentWritable, "${e.key} agentWritable")
            assertEquals(e.requiresReload, def.requiresReload, "${e.key} requiresReload")
        }
    }

    @Test
    fun `every setting has a description and a unique key`() {
        assertEquals(SettingsRegistry.ALL.size, SettingsRegistry.BY_KEY.size)
        for (def in SettingsRegistry.ALL) {
            assertTrue(def.description.isNotBlank(), "${def.key} needs a description")
        }
    }

    @Test
    fun `trace settings are never agent writable`() {
        // An agent able to disable its own observability defeats the project's
        // primary purpose. docs/26-settings.md §3 calls this a hard line.
        for (def in SettingsRegistry.ALL.filter { it.key.startsWith("trace.") }) {
            assertFalse(def.agentWritable, "${def.key} must not be agent writable")
        }
    }

    @Test
    fun `reload requiring settings are never agent writable`() {
        // Writing one mid-turn would destroy the engine currently generating.
        for (def in SettingsRegistry.ALL.filter { it.requiresReload }) {
            assertFalse(def.agentWritable, "${def.key} requires reload so must not be agent writable")
        }
    }

    @Test
    fun `agent writable set is exactly the expected keys`() {
        assertEquals(
            listOf(
                "model.temperature",
                "model.top_p",
                "agent.max_steps",
                "agent.auto_repair",
                "agent.confirm_threshold",
                "agent.digest_max_nodes",
                "agent.history_turns",
                "ui.theme",
                "ui.grid_visible",
                "ui.cell_size",
            ),
            SettingsRegistry.AGENT_WRITABLE.map { it.key },
        )
    }

    @Test
    fun `enum settings with options reject values outside them`() {
        assertTrue(SettingsRegistry.validate("ui.theme", "dark", fromAgent = true).isOk)
        val bad = SettingsRegistry.validate("ui.theme", "neon", fromAgent = true)
        assertEquals(ToolErrorCode.SETTING_OUT_OF_RANGE, bad.errorOrNull()?.code)
    }

    @Test
    fun `numeric settings are range checked`() {
        assertTrue(SettingsRegistry.validate("model.temperature", "1.2", fromAgent = true).isOk)
        assertEquals(
            ToolErrorCode.SETTING_OUT_OF_RANGE,
            SettingsRegistry.validate("model.temperature", "2.0", fromAgent = true).errorOrNull()?.code,
        )
        assertEquals(
            ToolErrorCode.SETTING_OUT_OF_RANGE,
            SettingsRegistry.validate("agent.max_steps", "notanumber", fromAgent = true).errorOrNull()?.code,
        )
    }

    @Test
    fun `agent writes to protected keys are refused but user writes are allowed`() {
        assertEquals(
            ToolErrorCode.SETTING_NOT_AGENT_WRITABLE,
            SettingsRegistry.validate("trace.enabled", "false", fromAgent = true).errorOrNull()?.code,
        )
        assertTrue(SettingsRegistry.validate("trace.enabled", "false", fromAgent = false).isOk)
    }

    @Test
    fun `unknown keys are refused`() {
        assertEquals(
            ToolErrorCode.UNKNOWN_SETTING,
            SettingsRegistry.validate("nope.nope", "1", fromAgent = true).errorOrNull()?.code,
        )
    }

    @Test
    fun `snapshot falls back to defaults and applies overrides`() {
        val s = SettingsSnapshot.DEFAULTS
        assertEquals(8, s.int(SettingKeys.AGENT_MAX_STEPS))
        assertEquals(0.3f, s.float(SettingKeys.MODEL_TEMPERATURE))
        assertFalse(s.bool(SettingKeys.AGENT_AUTO_REPAIR))

        val raised = s.with(SettingKeys.AGENT_MAX_STEPS, "3")
        assertEquals(3, raised.int(SettingKeys.AGENT_MAX_STEPS))
        assertEquals(8, s.int(SettingKeys.AGENT_MAX_STEPS), "original snapshot must be unchanged")
    }
}
