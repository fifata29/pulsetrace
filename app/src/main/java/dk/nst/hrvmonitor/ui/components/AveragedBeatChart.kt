package dk.nst.hrvmonitor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import dk.nst.hrvmonitor.ppg.PulseMorphology
import dk.nst.hrvmonitor.ui.theme.Accent
import dk.nst.hrvmonitor.ui.theme.OnSurfaceMuted
import dk.nst.hrvmonitor.ui.theme.Pulse
import dk.nst.hrvmonitor.ui.theme.PulseSoft
import dk.nst.hrvmonitor.ui.theme.SurfaceElev

/**
 * Plots the averaged-beat waveform with the four fiducial points (foot,
 * systolic peak, dicrotic notch, diastolic peak) labelled. Time on the
 * x-axis (seconds), normalised amplitude on the y-axis.
 */
@Composable
fun AveragedBeatChart(
    morphology: PulseMorphology.Result,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceElev)
            .padding(12.dp)
    ) {
        if (!morphology.isAvailable || morphology.averagedBeat.size < 4) {
            Text(
                "Not enough clean beats — need a steadier recording.",
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.Center)
            )
            return@Box
        }

        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            val beat = morphology.averagedBeat
            val time = morphology.averagedBeatTime
            val tMin = time.first()
            val tMax = time.last().coerceAtLeast(tMin + 1e-3f)
            var yMin = beat.min(); var yMax = beat.max()
            val pad = (yMax - yMin) * 0.12f
            yMin -= pad; yMax += pad
            val ySpan = (yMax - yMin).coerceAtLeast(1e-3f)

            fun toX(t: Float) = w * (t - tMin) / (tMax - tMin)
            fun toY(v: Float) = h - h * (v - yMin) / ySpan

            // Zero-line / 1-second grid.
            val grid = Color.White.copy(alpha = 0.06f)
            val secs = (tMax - tMin).toInt() + 1
            for (s in 0..secs) {
                val x = w * s.toFloat() / (tMax - tMin)
                if (x in 0f..w) drawLine(grid, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
            }

            // Beat path.
            val path = Path()
            for (i in beat.indices) {
                val x = toX(time[i])
                val y = toY(beat[i])
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = Pulse,
                style = Stroke(width = 3f, cap = StrokeCap.Round)
            )

            // Fiducial markers.
            val fiducials = listOf(
                Triple(morphology.footIdx, "foot", PulseSoft),
                Triple(morphology.systolicPeakIdx, "systolic", Pulse),
                Triple(morphology.dicroticNotchIdx, "notch", Accent),
                Triple(morphology.diastolicPeakIdx, "diastolic", Accent)
            )
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 22f
                isAntiAlias = true
            }
            for ((idx, label, color) in fiducials) {
                if (idx < 0 || idx >= beat.size) continue
                val x = toX(time[idx])
                val y = toY(beat[idx])
                drawCircle(color, radius = 5f, center = Offset(x, y))
                drawCircle(Color.White, radius = 5f, center = Offset(x, y), style = Stroke(width = 1.5f))
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    (x - label.length * 5f).coerceIn(0f, w - label.length * 10f),
                    (y - 12f).coerceAtLeast(20f),
                    textPaint
                )
            }
        }
    }
}
