package dev.droiddoodle.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.droiddoodle.model.NodeColor
import dev.droiddoodle.model.NodeType

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9FD2FF),
    surface = Color(0xFF11151A),
    background = Color(0xFF0B0E12),
    onSurface = Color(0xFFE4E8EE),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1F6FEB),
    surface = Color(0xFFF7F9FC),
    background = Color(0xFFFFFFFF),
    onSurface = Color(0xFF11151A),
)

@Composable
internal fun DroidDoodleTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        content = content,
    )
}

/**
 * Whether a colour reads as dark. Lives here rather than beside the canvas
 * because both the canvas palette and the status colours branch on it.
 */
internal fun Color.luminanceIsDark(): Boolean =
    (red * 0.299f + green * 0.587f + blue * 0.114f) < 0.5f

/**
 * Status colours, theme-aware.
 *
 * These exist because `MaterialTheme.colorScheme` has an `error` role but no
 * "success" or "warning" one, and the obvious workaround -- a hardcoded
 * `Color(0xFF2E7D32)` -- is a mid-dark green that lands at roughly 3:1 against
 * this app's near-black background. Legible on the light theme, failing on the
 * dark one, which is the theme most of this app is looked at in.
 *
 * Both pairs are lightened for dark mode rather than reused across it.
 */
@Composable
internal fun statusSuccess(): Color =
    if (MaterialTheme.colorScheme.background.luminanceIsDark()) {
        Color(0xFF7BD88F)
    } else {
        Color(0xFF1B5E20)
    }

@Composable
internal fun statusWarning(): Color =
    if (MaterialTheme.colorScheme.background.luminanceIsDark()) {
        Color(0xFFFFB86B)
    } else {
        Color(0xFF9A4A00)
    }

/**
 * Node palette. [NodeColor.DEFAULT] resolves per node **type** rather than to a
 * single neutral, so an unstyled board still reads as structured at a glance --
 * which is the whole point of a visual canvas.
 */
internal fun nodeFill(color: NodeColor, type: NodeType, dark: Boolean): Color = when (color) {
    NodeColor.DEFAULT -> when (type) {
        NodeType.PLACE -> if (dark) Color(0xFF243447) else Color(0xFFD9E6F7)
        NodeType.CHARACTER -> if (dark) Color(0xFF3A2A44) else Color(0xFFEBDCF3)
        NodeType.OBJECT -> if (dark) Color(0xFF2C3A2E) else Color(0xFFDDEEDF)
        NodeType.NOTE -> if (dark) Color(0xFF433B23) else Color(0xFFF6EDD3)
        NodeType.GROUP -> if (dark) Color(0xFF1E2A2E) else Color(0xFFE2EEF1)
    }
    NodeColor.RED -> if (dark) Color(0xFF5A2A2E) else Color(0xFFF8D7DA)
    NodeColor.ORANGE -> if (dark) Color(0xFF5A3E23) else Color(0xFFFBE3CD)
    NodeColor.YELLOW -> if (dark) Color(0xFF554B1F) else Color(0xFFFAF3C8)
    NodeColor.GREEN -> if (dark) Color(0xFF224734) else Color(0xFFD4EFDF)
    NodeColor.BLUE -> if (dark) Color(0xFF1F3A5A) else Color(0xFFD6E6FA)
    NodeColor.PURPLE -> if (dark) Color(0xFF3C2A55) else Color(0xFFE7DAF6)
    NodeColor.GRAY -> if (dark) Color(0xFF31363C) else Color(0xFFE6E8EB)
}

internal fun nodeStroke(dark: Boolean): Color =
    if (dark) Color(0xFF5B6672) else Color(0xFF9AA6B2)

internal fun gridLine(dark: Boolean): Color =
    if (dark) Color(0x1AFFFFFF) else Color(0x14000000)

internal fun hullTint(dark: Boolean): Color =
    if (dark) Color(0x2263B3FF) else Color(0x1A1F6FEB)
