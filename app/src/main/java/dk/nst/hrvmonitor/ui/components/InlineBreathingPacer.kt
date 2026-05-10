package dk.nst.hrvmonitor.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.nst.hrvmonitor.ui.theme.Accent
import dk.nst.hrvmonitor.ui.theme.OnSurfaceMuted
import dk.nst.hrvmonitor.ui.theme.PulseSoft
import dk.nst.hrvmonitor.ui.theme.SurfaceElev
import kotlin.math.roundToInt

/**
 * Compact breathing pacer for the measurement screen.
 *
 * Default: resonant 6 BPM (5 s inhale / 5 s exhale). The card has a circle
 * on the left that radially grows on inhale and shrinks on exhale, plus a
 * phase label and countdown on the right. Sized to ~70 dp tall so it fits
 * inline above the camera preview without forcing a layout reshuffle.
 */
@Composable
fun InlineBreathingPacer(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    inhaleSec: Int = 5,
    exhaleSec: Int = 5
) {
    var phaseIdx by remember { mutableIntStateOf(0) }
    var phaseRemaining by remember { mutableIntStateOf(inhaleSec) }
    val radius = remember { Animatable(0.30f) }
    val phases = listOf("Inhale" to inhaleSec, "Exhale" to exhaleSec)

    LaunchedEffect(enabled, inhaleSec, exhaleSec) {
        if (!enabled) {
            radius.snapTo(0.30f)
            return@LaunchedEffect
        }
        phaseIdx = 0
        while (true) {
            val (_, secs) = phases[phaseIdx]
            val target = if (phaseIdx == 0) 1.0f else 0.30f
            phaseRemaining = secs
            radius.animateTo(target, tween(secs * 1000, easing = LinearEasing))
            phaseIdx = (phaseIdx + 1) % phases.size
        }
    }

    // Smooth countdown ticker.
    LaunchedEffect(enabled, phaseIdx) {
        if (!enabled) return@LaunchedEffect
        val secs = phases[phaseIdx].second
        val start = System.nanoTime()
        while (true) {
            val elapsed = (System.nanoTime() - start) / 1e9f
            phaseRemaining = (secs - elapsed.toInt()).coerceAtLeast(0)
            if (elapsed >= secs) break
            kotlinx.coroutines.delay(200)
        }
    }

    Row(
        modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceElev)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(52.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxHeight().width(52.dp)) {
                val maxR = minOf(size.width, size.height) * 0.48f
                val r = radius.value * maxR
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Accent.copy(alpha = 0.40f), Color.Transparent),
                        radius = r * 1.6f
                    ),
                    radius = r * 1.6f,
                    center = Offset(size.width / 2, size.height / 2)
                )
                drawCircle(
                    color = Accent.copy(alpha = 0.55f),
                    radius = r,
                    center = Offset(size.width / 2, size.height / 2)
                )
                drawCircle(
                    color = PulseSoft,
                    radius = r,
                    center = Offset(size.width / 2, size.height / 2),
                    style = Stroke(width = 2f)
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.fillMaxHeight(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
            Text(
                if (enabled) phases[phaseIdx].first else "Pace breathing",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                if (enabled) "${phaseRemaining.coerceAtLeast(0)} s · 6 breaths/min"
                else "Resonant 5-5 (helps HRV signal)",
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
            )
        }
    }
}
