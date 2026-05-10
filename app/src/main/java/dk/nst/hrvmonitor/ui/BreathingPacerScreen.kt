package dk.nst.hrvmonitor.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.nst.hrvmonitor.ui.theme.Accent
import dk.nst.hrvmonitor.ui.theme.OnSurfaceMuted
import dk.nst.hrvmonitor.ui.theme.Pulse
import dk.nst.hrvmonitor.ui.theme.PulseSoft
import dk.nst.hrvmonitor.ui.theme.SurfaceDark
import dk.nst.hrvmonitor.ui.theme.SurfaceElev
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** A single breathing phase: name, duration, and target circle radius (0..1 of the canvas). */
private data class BreathPhase(val label: String, val seconds: Int, val targetRadius: Float)

/** A breathing protocol — list of phases that loop. */
private data class BreathPattern(
    val key: String,
    val name: String,
    val description: String,
    val cycleSeconds: Int,
    val bpm: Float,
    val phases: List<BreathPhase>
)

private val resonant = BreathPattern(
    key = "resonant",
    name = "Resonant 6 BPM",
    description = "5 s inhale / 5 s exhale. Optimal for HRV — maximises respiratory sinus arrhythmia and synchronises the baroreflex. Best choice before a measurement.",
    cycleSeconds = 10,
    bpm = 6f,
    phases = listOf(
        BreathPhase("Inhale", 5, 1.00f),
        BreathPhase("Exhale", 5, 0.30f)
    )
)

private val box = BreathPattern(
    key = "box",
    name = "Box 4-4-4-4",
    description = "Inhale 4 s · hold 4 s · exhale 4 s · hold 4 s. Used by special-forces & meditators for stress reduction and focus.",
    cycleSeconds = 16,
    bpm = 60f / 16,
    phases = listOf(
        BreathPhase("Inhale", 4, 1.00f),
        BreathPhase("Hold", 4, 1.00f),
        BreathPhase("Exhale", 4, 0.30f),
        BreathPhase("Hold", 4, 0.30f)
    )
)

private val relax478 = BreathPattern(
    key = "478",
    name = "Relax 4-7-8",
    description = "Inhale 4 s · hold 7 s · exhale 8 s. Andrew Weil's relaxation breath — slows the nervous system, helpful for sleep onset.",
    cycleSeconds = 19,
    bpm = 60f / 19,
    phases = listOf(
        BreathPhase("Inhale", 4, 1.00f),
        BreathPhase("Hold", 7, 1.00f),
        BreathPhase("Exhale", 8, 0.30f)
    )
)

private val patterns = listOf(resonant, box, relax478)

@Composable
fun BreathingPacerScreen(
    onBack: () -> Unit,
    onContinueToMeasurement: () -> Unit
) {
    var pattern by remember { mutableStateOf(resonant) }
    var totalSec by remember { mutableIntStateOf(60) }
    var running by remember { mutableStateOf(false) }
    var phaseIdx by remember { mutableIntStateOf(0) }
    var phaseElapsed by remember { mutableStateOf(0f) }
    var totalElapsed by remember { mutableStateOf(0f) }
    val radius = remember { Animatable(0.30f) }

    // Single coroutine drives both the phase timer and the radius animation.
    // We tick total elapsed every 100 ms; whenever a phase boundary is crossed,
    // we move to the next phase and start a tween on the radius for that duration.
    LaunchedEffect(running, pattern, totalSec) {
        if (!running) return@LaunchedEffect
        phaseIdx = 0
        phaseElapsed = 0f
        totalElapsed = 0f

        // Kick off the first phase's animation.
        var current = pattern.phases[phaseIdx]
        radius.snapTo(current.targetRadius.let { _ -> 0.30f })
        radius.animateTo(
            current.targetRadius,
            animationSpec = tween(current.seconds * 1000, easing = LinearEasing)
        )
        // animateTo is suspending — when it returns this phase is done.
        phaseElapsed = current.seconds.toFloat()
        totalElapsed += current.seconds
        while (running && totalElapsed < totalSec) {
            phaseIdx = (phaseIdx + 1) % pattern.phases.size
            current = pattern.phases[phaseIdx]
            phaseElapsed = 0f
            radius.animateTo(
                current.targetRadius,
                animationSpec = tween(current.seconds * 1000, easing = LinearEasing)
            )
            phaseElapsed = current.seconds.toFloat()
            totalElapsed += current.seconds
        }
        running = false
        radius.animateTo(0.30f, tween(400))
    }

    // Lightweight 100 ms tick to keep the in-phase countdown smooth.
    LaunchedEffect(running, phaseIdx) {
        if (!running) return@LaunchedEffect
        phaseElapsed = 0f
        while (running) {
            delay(100)
            phaseElapsed += 0.1f
            val phase = pattern.phases[phaseIdx]
            if (phaseElapsed >= phase.seconds) {
                // The outer loop will advance; clamp to avoid showing negative.
                phaseElapsed = phase.seconds.toFloat()
                break
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Button(
                    onClick = { running = !running },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (running) SurfaceElev else Accent,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (running) "Stop" else "Start ${pattern.name}",
                        style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onContinueToMeasurement,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceElev, contentColor = Color.White)
                ) {
                    Text("Continue to measurement", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Header(onBack)
            Spacer(Modifier.height(8.dp))

            ModePicker(patterns, pattern) { pattern = it; running = false }
            Spacer(Modifier.height(8.dp))
            DurationPicker(totalSec) { totalSec = it; running = false }
            Spacer(Modifier.height(12.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width; val h = size.height
                    val maxR = minOf(w, h) * 0.42f
                    val r = radius.value * maxR
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Pulse.copy(alpha = 0.30f), Color.Transparent),
                            radius = r * 1.4f
                        ),
                        radius = r * 1.4f,
                        center = Offset(w / 2, h / 2)
                    )
                    drawCircle(
                        color = Pulse.copy(alpha = 0.45f),
                        radius = r,
                        center = Offset(w / 2, h / 2)
                    )
                    drawCircle(
                        color = PulseSoft,
                        radius = r,
                        center = Offset(w / 2, h / 2),
                        style = Stroke(width = 3f)
                    )
                }
                val phase = pattern.phases.getOrNull(phaseIdx) ?: pattern.phases[0]
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (running) phase.label else "Ready",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 32.sp, fontWeight = FontWeight.Light)
                    )
                    if (running) {
                        Text(
                            "${(phase.seconds - phaseElapsed.toInt()).coerceAtLeast(0)} s",
                            color = OnSurfaceMuted,
                            style = MaterialTheme.typography.titleLarge
                        )
                    } else {
                        Text(
                            "${pattern.bpm.let { "%.1f".format(it) }} breaths / min",
                            color = OnSurfaceMuted,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }

            Text(
                pattern.description,
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            if (running) {
                val remaining by remember { derivedStateOf { (totalSec - totalElapsed).coerceAtLeast(0f) } }
                Text(
                    "${remaining.roundToInt()} s remaining",
                    color = OnSurfaceMuted,
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Spacer(Modifier.width(4.dp))
        Column {
            Text(
                "Breathing pacer",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                "A minute of paced breathing before a measurement gives the cleanest HRV reading.",
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun ModePicker(all: List<BreathPattern>, current: BreathPattern, onPick: (BreathPattern) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        all.forEach { p ->
            val selected = p === current
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) Accent.copy(alpha = 0.22f) else SurfaceElev)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = { onPick(p) }, modifier = Modifier.fillMaxWidth()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            p.name,
                            color = if (selected) Accent else Color.White,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            "${"%.1f".format(p.bpm)} BPM",
                            color = OnSurfaceMuted,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DurationPicker(current: Int, onPick: (Int) -> Unit) {
    val choices = listOf(30, 60, 120, 180)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        choices.forEach { secs ->
            val selected = secs == current
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) Pulse.copy(alpha = 0.18f) else SurfaceElev)
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = { onPick(secs) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "${secs / 60}:${"%02d".format(secs % 60)}",
                        color = if (selected) Pulse else Color.White,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}
