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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dk.nst.hrvmonitor.ppg.SignalProcessor
import dk.nst.hrvmonitor.ui.theme.Accent
import dk.nst.hrvmonitor.ui.theme.OnSurfaceMuted
import dk.nst.hrvmonitor.ui.theme.Pulse
import dk.nst.hrvmonitor.ui.theme.PulseSoft
import dk.nst.hrvmonitor.ui.theme.SurfaceElev

/**
 * Live PPG chart. Plots filtered signal as the primary line, raw (detrended) as a faint
 * background, and detected systolic peaks as dots — that's the "alignment" trace the
 * user can use to verify peak detection against the raw waveform.
 */
@Composable
fun SignalChart(
    samples: List<SignalProcessor.Sample>,
    peaks: List<SignalProcessor.Peak>,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp)
    ) {
        if (samples.size < 4) {
            Text(
                "Waiting for signal…",
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center)
            )
            return@Box
        }

        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            val tStart = samples.first().tSec
            val tEnd = samples.last().tSec
            val tSpan = (tEnd - tStart).coerceAtLeast(0.001f)

            // Center & scale by filtered min/max for stable plotting.
            var minF = Float.POSITIVE_INFINITY
            var maxF = Float.NEGATIVE_INFINITY
            for (s in samples) {
                if (s.filtered < minF) minF = s.filtered
                if (s.filtered > maxF) maxF = s.filtered
            }
            val pad = (maxF - minF).coerceAtLeast(0.5f) * 0.15f
            minF -= pad; maxF += pad
            val ySpan = (maxF - minF).coerceAtLeast(0.001f)

            // Gridlines (1 second).
            val gridStroke = Stroke(
                width = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f), 0f)
            )
            val seconds = tSpan.toInt()
            for (s in 0..seconds) {
                val x = w * (s / tSpan).coerceIn(0f, 1f)
                drawLine(
                    color = Color.White.copy(alpha = 0.06f),
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = gridStroke.width,
                    pathEffect = gridStroke.pathEffect
                )
            }
            drawLine(
                color = Color.White.copy(alpha = 0.08f),
                start = Offset(0f, h / 2),
                end = Offset(w, h / 2),
                strokeWidth = 1f
            )

            // Raw (detrended) trace as a faint backdrop. Reuse same y-mapping by re-fitting.
            var minR = Float.POSITIVE_INFINITY; var maxR = Float.NEGATIVE_INFINITY
            for (s in samples) {
                if (s.raw < minR) minR = s.raw
                if (s.raw > maxR) maxR = s.raw
            }
            val rawSpan = (maxR - minR).coerceAtLeast(0.001f)
            val rawPath = Path()
            samples.forEachIndexed { i, s ->
                val x = w * ((s.tSec - tStart) / tSpan)
                val y = h - h * ((s.raw - minR) / rawSpan)
                if (i == 0) rawPath.moveTo(x, y) else rawPath.lineTo(x, y)
            }
            drawPath(
                path = rawPath,
                color = Accent.copy(alpha = 0.30f),
                style = Stroke(width = 1.5f, cap = StrokeCap.Round)
            )

            // Filtered PPG trace.
            val filtPath = Path()
            samples.forEachIndexed { i, s ->
                val x = w * ((s.tSec - tStart) / tSpan)
                val y = h - h * ((s.filtered - minF) / ySpan)
                if (i == 0) filtPath.moveTo(x, y) else filtPath.lineTo(x, y)
            }
            drawPath(
                path = filtPath,
                color = Pulse,
                style = Stroke(width = 3f, cap = StrokeCap.Round)
            )

            // Peaks aligned to filtered trace.
            for (p in peaks) {
                val x = w * ((p.tSec - tStart) / tSpan)
                val y = h - h * ((p.value - minF) / ySpan)
                drawCircle(PulseSoft, radius = 5f, center = Offset(x, y))
                drawCircle(Pulse, radius = 3f, center = Offset(x, y))
            }
        }
    }
}

/**
 * RR tachogram: each beat-to-beat interval as a dot connected by a line.
 * Shows HRV at a glance — flat = low HRV, spiky = high HRV.
 */
@Composable
fun RrTachogram(
    rrMs: List<Float>,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceElev)
            .padding(12.dp)
    ) {
        if (rrMs.size < 2) {
            Text(
                "Need a few beats first…",
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center)
            )
            return@Box
        }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val minRr = (rrMs.min() - 30f).coerceAtLeast(300f)
            val maxRr = (rrMs.max() + 30f).coerceAtMost(2000f)
            val ySpan = (maxRr - minRr).coerceAtLeast(50f)

            val path = Path()
            rrMs.forEachIndexed { i, v ->
                val x = if (rrMs.size == 1) w / 2 else w * i / (rrMs.size - 1f)
                val y = h - h * ((v - minRr) / ySpan)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = Accent,
                style = Stroke(width = 2.5f, cap = StrokeCap.Round)
            )
            rrMs.forEachIndexed { i, v ->
                val x = if (rrMs.size == 1) w / 2 else w * i / (rrMs.size - 1f)
                val y = h - h * ((v - minRr) / ySpan)
                drawCircle(Accent, radius = 4f, center = Offset(x, y))
            }
        }
    }
}
