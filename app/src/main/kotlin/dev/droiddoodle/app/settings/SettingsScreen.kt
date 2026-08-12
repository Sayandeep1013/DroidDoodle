package dev.droiddoodle.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.droiddoodle.model.SettingDef
import dev.droiddoodle.model.SettingType
import dev.droiddoodle.model.SettingsRegistry
import dev.droiddoodle.model.SettingsSnapshot
import kotlin.math.roundToInt

/**
 * The settings screen, generated entirely from `SettingsRegistry`.
 *
 * There is no per-key code anywhere below: rows come from the registry, the
 * control is chosen from `SettingDef.type`, and bounds come from `min`/`max` or
 * `options`. Adding a key to the registry adds a row here with no edit to this
 * file. That is the acceptance criterion, and it is also the only way the
 * screen can be guaranteed to agree with what `set_setting` will accept.
 */
@Composable
internal fun SettingsScreen(
    snapshot: SettingsSnapshot,
    onChange: (key: String, value: String) -> Unit,
    onResetAll: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Grouped by the key prefix, which is already how the registry is organised.
    val groups = SettingsRegistry.ALL.groupBy { it.key.substringBefore('.') }

    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(
                "Settings",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onResetAll) { Text("Reset all") }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            groups.forEach { (prefix, defs) ->
                item(key = "header-$prefix") {
                    Text(
                        prefix,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    HorizontalDivider()
                }
                items(defs, key = { it.key }) { def ->
                    SettingRow(
                        def = def,
                        value = snapshot.string(def.key),
                        onChange = { onChange(def.key, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    def: SettingDef,
    value: String,
    onChange: (String) -> Unit,
) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(def.key, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                Text(def.description, style = MaterialTheme.typography.bodySmall)
            }
            if (def.type == SettingType.BOOL) {
                Switch(
                    checked = value.equals("true", ignoreCase = true),
                    onCheckedChange = { onChange(it.toString()) },
                )
            }
        }

        // Two badges that carry real meaning, both read from the registry:
        // whether the agent can write this key, and whether a change needs an
        // engine reload to take effect. Showing the second is the difference
        // between a setting that appears broken and one that is honest.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (def.agentWritable) {
                AssistChip(onClick = {}, label = { Text("agent-writable") })
            }
            if (def.requiresReload) {
                AssistChip(onClick = {}, label = { Text("needs reload") })
            }
        }

        when (def.type) {
            SettingType.BOOL -> Unit // handled by the Switch above

            SettingType.ENUM -> if (def.options.isEmpty()) {
                // model.id draws its options from the downloaded-model manifest
                // at runtime, so there is nothing to offer here.
                Text(
                    if (value.isBlank()) "not set — chosen in the model picker" else value,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    def.options.forEach { option ->
                        FilterChip(
                            selected = option == value,
                            onClick = { onChange(option) },
                            label = { Text(option) },
                        )
                    }
                }
            }

            SettingType.INT, SettingType.FLOAT -> {
                val min = (def.min ?: 0.0).toFloat()
                val max = (def.max ?: 1.0).toFloat()
                val current = value.toFloatOrNull() ?: min
                val isInt = def.type == SettingType.INT
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isInt) current.roundToInt().toString() else "%.2f".format(current),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    Slider(
                        value = current.coerceIn(min, max),
                        onValueChange = {
                            onChange(if (isInt) it.roundToInt().toString() else "%.2f".format(it))
                        },
                        valueRange = min..max,
                        // Discrete stops for INT so a slider cannot produce a
                        // value the registry would reject as non-integral.
                        steps = if (isInt) ((max - min).toInt() - 1).coerceAtLeast(0) else 0,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            SettingType.STRING -> OutlinedTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
