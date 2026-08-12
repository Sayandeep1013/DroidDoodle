package dev.droiddoodle.app.resources

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import dev.droiddoodle.app.statusWarning
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val HISTORY = 60
private const val INTERVAL_MILLIS = 1000L

/**
 * Live resource use.
 *
 * Sampling runs only while this screen is composed. A background sampler would
 * itself consume the CPU it claims to be measuring, and would keep waking the
 * device during exactly the idle periods that make a latency baseline readable.
 */
@Composable
internal fun ResourceScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sampler = remember(context) { ResourceSampler(context) }
    var sample by remember { mutableStateOf<ResourceSample?>(null) }
    val pssHistory = remember { mutableListOf<Float>() }
    val cpuHistory = remember { mutableListOf<Float>() }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(sampler) {
        while (true) {
            val next = sampler.sample()
            sample = next
            pssHistory.append(next.pssBytes.toFloat())
            cpuHistory.append(next.cpuPercentOfOneCore.toFloat())
            tick++
            delay(INTERVAL_MILLIS)
        }
    }

    val current = sample
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (current == null) {
            Text("Sampling…", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        // The first CPU reading has no previous sample to difference against,
        // so it reads 0 rather than a made-up number.
        val warmingUp = tick <= 1

        MetricCard(
            title = "Memory — this app",
            primary = formatBytes(current.pssBytes),
            caption = "PSS · peak this session ${formatBytes(sampler.peakPssBytes)}",
            history = pssHistory,
            accent = MaterialTheme.colorScheme.primary,
        ) {
            Line("native PSS", formatBytes(current.nativePssBytes))
            Line("native heap", formatBytes(current.nativeHeapBytes))
            Line(
                "JVM heap",
                "${formatBytes(current.jvmHeapUsedBytes)} of ${formatBytes(current.jvmHeapMaxBytes)}",
            )
            Text(
                "The model is mmapped, so it shows in native PSS rather than in " +
                    "the native heap. PSS is the number that answers \"does it fit\".",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        MetricCard(
            title = "CPU — this app",
            primary = if (warmingUp) "—" else "%.0f%%".format(current.cpuPercentOfOneCore),
            caption = "of one core · %.0f%% of all ${current.coreCount} cores".format(
                current.cpuPercentOfDevice,
            ),
            history = cpuHistory,
            accent = statusWarning(),
        ) {
            Line("cores", current.coreCount.toString())
            Line("thermal", current.thermal)
            if (current.thermal !in setOf("none", "unavailable", "unknown")) {
                Text(
                    "The device is throttling. Latency measured now says more " +
                        "about heat than about the model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        val usedFraction = if (current.deviceTotalBytes > 0) {
            1f - current.deviceAvailableBytes.toFloat() / current.deviceTotalBytes
        } else {
            0f
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Memory — whole device", style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(
                    progress = { usedFraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Line("free", formatBytes(current.deviceAvailableBytes))
                Line("total", formatBytes(current.deviceTotalBytes))
                if (current.deviceLowMemory) {
                    Text(
                        "Android has flagged low memory. It may kill this app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    primary: String,
    caption: String,
    history: List<Float>,
    accent: Color,
    detail: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(primary, style = MaterialTheme.typography.headlineMedium)
                Text(
                    caption,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                )
            }
            Sparkline(history, accent, Modifier.fillMaxWidth().height(48.dp))
            detail()
        }
    }
}

/**
 * Scaled to the window's own maximum rather than an absolute ceiling, so the
 * shape of a change is visible even when the absolute numbers are small.
 */
@Composable
private fun Sparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val maximum = values.max().coerceAtLeast(1f)
        val stepX = size.width / (values.size - 1).toFloat()
        var previous = Offset(0f, size.height * (1f - values[0] / maximum))
        for (i in 1 until values.size) {
            val next = Offset(i * stepX, size.height * (1f - values[i] / maximum))
            drawLine(color, previous, next, strokeWidth = 2f)
            previous = next
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

private fun MutableList<Float>.append(value: Float) {
    add(value)
    while (size > HISTORY) removeAt(0)
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}
