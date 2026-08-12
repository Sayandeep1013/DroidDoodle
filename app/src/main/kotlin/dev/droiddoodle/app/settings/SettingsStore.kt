package dev.droiddoodle.app.settings

import android.content.Context
import dev.droiddoodle.model.Res
import dev.droiddoodle.model.SettingsRegistry
import dev.droiddoodle.model.SettingsSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistence for the settings registry.
 *
 * Registry-driven throughout: nothing here names a key. Adding a key to
 * `SettingsRegistry` makes it persist, validate and appear in the UI with no
 * change to this file or to the screen — which is the P9 acceptance criterion,
 * and the same "one source of truth" rule that governs tool schemas and the
 * grammar.
 *
 * Values are stored as strings because the registry validates from strings. A
 * typed preference store would need a second type mapping that could disagree
 * with the registry's.
 */
internal class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _snapshot = MutableStateFlow(load())
    val snapshot: StateFlow<SettingsSnapshot> = _snapshot.asStateFlow()

    private fun load(): SettingsSnapshot {
        // Only keys the registry still declares are read back. A key removed
        // from the registry should stop taking effect immediately, not linger
        // in storage influencing behaviour nothing describes any more.
        val overrides = SettingsRegistry.ALL.mapNotNull { def ->
            prefs.getString(def.key, null)?.let { def.key to it }
        }.toMap()
        return SettingsSnapshot(overrides)
    }

    /**
     * Writes a user-originated change. Validation is the registry's, so the UI
     * cannot store a value the agent would have been refused — the two paths
     * differ only in the `fromAgent` flag.
     */
    fun set(key: String, value: String): Res<String, dev.droiddoodle.model.ToolError> {
        val validated = SettingsRegistry.validate(key, value, fromAgent = false)
        if (validated is Res.Ok) {
            prefs.edit().putString(key, validated.value).apply()
            _snapshot.value = _snapshot.value.with(key, validated.value)
        }
        return validated
    }

    /**
     * Applies writes the agent made during a turn.
     *
     * These arrive already validated — `set_setting` refused anything invalid,
     * and refused any key that is not agent-writable, at execution time.
     * Re-validating here would be duplicated authority; persisting them is the
     * whole job.
     */
    fun applyAgentWrites(writes: List<Pair<String, String>>) {
        if (writes.isEmpty()) return
        val editor = prefs.edit()
        writes.forEach { (key, value) -> editor.putString(key, value) }
        editor.apply()
        var next = _snapshot.value
        writes.forEach { (key, value) -> next = next.with(key, value) }
        _snapshot.value = next
    }

    fun reset(key: String) {
        prefs.edit().remove(key).apply()
        val default = SettingsRegistry.BY_KEY[key]?.default ?: return
        _snapshot.value = _snapshot.value.with(key, default)
    }

    fun resetAll() {
        prefs.edit().clear().apply()
        _snapshot.value = SettingsSnapshot()
    }

    private companion object {
        const val PREFS = "droiddoodle.settings"
    }
}
