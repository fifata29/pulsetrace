package dk.nst.hrvmonitor.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.nst.hrvmonitor.ui.theme.Accent
import dk.nst.hrvmonitor.ui.theme.Bad
import dk.nst.hrvmonitor.ui.theme.Good
import dk.nst.hrvmonitor.ui.theme.OnSurfaceMuted
import dk.nst.hrvmonitor.ui.theme.Pulse
import dk.nst.hrvmonitor.ui.theme.PulseSoft
import dk.nst.hrvmonitor.ui.theme.SurfaceElev
import dk.nst.hrvmonitor.ui.theme.Warn
import kotlin.math.roundToInt

@Composable
fun BpmHero(
    bpm: Float?,
    isMeasuring: Boolean,
    elapsedSec: Float,
    targetSec: Float,
    progress: Float,
    modifier: Modifier = Modifier
) {
    val animated by animateFloatAsState(
        targetValue = bpm ?: 0f,
        animationSpec = tween(durationMillis = 400),
        label = "bpm"
    )
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(listOf(Pulse.copy(alpha = 0.18f), Accent.copy(alpha = 0.08f)))
            )
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (isMeasuring) Pulse else OnSurfaceMuted))
            Spacer(Modifier.width(8.dp))
            Text(
                if (isMeasuring) "Live" else "Idle",
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${formatTime(elapsedSec)} / ${formatTime(targetSec)}",
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.labelLarge
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = if (bpm == null) "—" else animated.roundToInt().toString(),
                fontSize = 64.sp,
                fontWeight = FontWeight.Light,
                color = Color.White,
                style = MaterialTheme.typography.displayMedium.copy(letterSpacing = (-2).sp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "BPM",
                color = PulseSoft,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = Pulse,
            trackColor = Color.White.copy(alpha = 0.10f)
        )
    }
}

private fun formatTime(sec: Float): String {
    val s = sec.coerceAtLeast(0f).toInt()
    val m = s / 60
    val r = s % 60
    return "%d:%02d".format(m, r)
}

@Composable
fun HrvMetricsRow(
    rmssd: Float?,
    sdnn: Float?,
    pnn50: Float?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MetricCard("RMSSD", formatMs(rmssd), "ms", Modifier.weight(1f))
        MetricCard("SDNN", formatMs(sdnn), "ms", Modifier.weight(1f))
        MetricCard("pNN50", formatPct(pnn50), "%", Modifier.weight(1f))
    }
}

@Composable
private fun MetricCard(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceElev)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(label, color = OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = Color.White, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 22.sp))
            Spacer(Modifier.width(3.dp))
            Text(unit, color = OnSurfaceMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

@Composable
fun QualityBar(coverage: Float, sampleRateHz: Float, modifier: Modifier = Modifier) {
    val color = when {
        coverage > 0.9f -> Good
        coverage > 0.6f -> Warn
        else -> Bad
    }
    val label = when {
        coverage > 0.9f -> "Good signal"
        coverage > 0.6f -> "Press more firmly"
        else -> "No fingertip detected"
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceElev)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text("${sampleRateHz.roundToInt()} Hz", color = OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
    }
}

private fun formatMs(v: Float?): String =
    if (v == null) "—" else v.roundToInt().toString()

private fun formatPct(v: Float?): String = when {
    v == null -> "—"
    v >= 10f -> v.roundToInt().toString()
    else -> "%.1f".format(v)
}
