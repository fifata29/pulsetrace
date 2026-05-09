package dk.nst.hrvmonitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.nst.hrvmonitor.ui.theme.Accent
import dk.nst.hrvmonitor.ui.theme.OnSurfaceMuted
import dk.nst.hrvmonitor.ui.theme.Pulse
import dk.nst.hrvmonitor.ui.theme.SurfaceDark
import dk.nst.hrvmonitor.ui.theme.SurfaceElev
import dk.nst.hrvmonitor.viewmodel.MeasurementViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(
    report: MeasurementViewModel.Report,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        contentColor = Color.White,
        dragHandle = null
    ) {
        ReportContent(report = report, onDone = onDismiss)
    }
}

@Composable
private fun ReportContent(report: MeasurementViewModel.Report, onDone: () -> Unit) {
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(
            "Recording complete",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
        )
        Text(
            "%.0f s · %.0f Hz · %d valid beats / %d detected"
                .format(report.durationSec, report.sampleRateHz, report.metrics.validBeats, report.metrics.totalBeats),
            color = OnSurfaceMuted,
            style = MaterialTheme.typography.labelLarge
        )
        Spacer(Modifier.height(16.dp))

        InterpretationRow(
            label = "Heart rate",
            value = report.metrics.bpm?.roundToInt()?.toString() ?: "—",
            unit = "BPM",
            interpretation = bpmInterpretation(report.metrics.bpm),
            highlightColor = Pulse
        )
        Spacer(Modifier.height(8.dp))
        InterpretationRow(
            label = "RMSSD",
            value = report.metrics.rmssdMs?.roundToInt()?.toString() ?: "—",
            unit = "ms",
            interpretation = rmssdInterpretation(report.metrics.rmssdMs),
            highlightColor = Accent
        )
        Spacer(Modifier.height(8.dp))
        InterpretationRow(
            label = "SDNN",
            value = report.metrics.sdnnMs?.roundToInt()?.toString() ?: "—",
            unit = "ms",
            interpretation = sdnnInterpretation(report.metrics.sdnnMs),
            highlightColor = Accent
        )
        Spacer(Modifier.height(8.dp))
        InterpretationRow(
            label = "pNN50",
            value = formatPnn(report.metrics.pnn50),
            unit = "%",
            interpretation = pnn50Interpretation(report.metrics.pnn50),
            highlightColor = Accent
        )

        Spacer(Modifier.height(18.dp))
        Text(
            "Beat-to-beat intervals",
            color = OnSurfaceMuted,
            style = MaterialTheme.typography.labelLarge
        )
        Spacer(Modifier.height(8.dp))
        RrTachogram(rrMs = report.rrMs)

        Spacer(Modifier.height(16.dp))
        Text("Quality summary", color = OnSurfaceMuted, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Text(qualitySummary(report), color = Color.White, style = MaterialTheme.typography.bodyMedium)

        if (report.sessionPath != null) {
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceElev)
                    .padding(12.dp)
            ) {
                Text("Raw data saved", color = OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    report.sessionPath,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Pull from your PC with:\nadb pull \"${report.sessionPath}\"",
                    color = OnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Pulse, contentColor = Color.White)
        ) { Text("Done", style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun InterpretationRow(
    label: String,
    value: String,
    unit: String,
    interpretation: String,
    highlightColor: Color
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceElev)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
            Text(label, color = OnSurfaceMuted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 5.dp))
            Spacer(Modifier.weight(1f))
            Text(value, color = highlightColor, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.padding(start = 4.dp))
            Text(unit, color = OnSurfaceMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 6.dp, start = 3.dp))
        }
        Text(interpretation, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatPnn(v: Float?): String = when {
    v == null -> "—"
    v >= 10f -> v.roundToInt().toString()
    else -> "%.1f".format(v)
}

private fun bpmInterpretation(bpm: Float?): String {
    if (bpm == null) return "Not enough data — hold steady longer or recheck finger placement."
    val v = bpm.roundToInt()
    return when {
        v < 50 -> "$v BPM is on the low side (bradycardia range). Common in trained athletes; otherwise worth noting."
        v < 60 -> "$v BPM is below typical resting (good in fit individuals)."
        v < 100 -> "$v BPM sits within the typical adult resting range (60–100)."
        v < 120 -> "$v BPM is slightly elevated — recent activity, stress, or measurement noise can explain this."
        else -> "$v BPM is high — likely measurement noise unless you've been active."
    }
}

private fun rmssdInterpretation(v: Float?): String {
    if (v == null) return "Not enough beats yet for RMSSD. Try a longer, steadier recording."
    val r = v.roundToInt()
    return when {
        r < 15 -> "$r ms is low — could mean reduced parasympathetic tone or that motion noise corrupted the RR series."
        r < 30 -> "$r ms is below the average resting range for adults (~30–50)."
        r < 60 -> "$r ms reflects healthy short-term parasympathetic (vagal) tone."
        else -> "$r ms is high — strong vagal tone, or some movement artifact may have inflated the value."
    }
}

private fun sdnnInterpretation(v: Float?): String {
    if (v == null) return "Need more clean beats."
    val r = v.roundToInt()
    return when {
        r < 30 -> "$r ms — overall variability is reduced for this short window."
        r < 60 -> "$r ms — average overall variability for a 30 s reading."
        r < 100 -> "$r ms — good total variability."
        else -> "$r ms — very high. Plausible at rest, but check the tachogram for outliers."
    }
}

private fun pnn50Interpretation(v: Float?): String {
    if (v == null) return "Need more beats."
    val pct = if (v >= 10f) v.roundToInt().toString() else "%.1f".format(v)
    return when {
        v < 3f -> "$pct% of successive RR diffs exceed 50 ms — low parasympathetic engagement."
        v < 15f -> "$pct% — average for adults at rest."
        v < 30f -> "$pct% — above average parasympathetic activity."
        else -> "$pct% — very high; double-check the tachogram for noise spikes."
    }
}

private fun qualitySummary(r: MeasurementViewModel.Report): String {
    val parts = mutableListOf<String>()
    parts += when {
        r.coverage > 0.9f -> "Strong fingertip contact (${(r.coverage * 100).roundToInt()}% coverage)."
        r.coverage > 0.6f -> "Partial contact — press more firmly next time."
        else -> "Weak contact — finger likely off-centre or not blocking the lens fully."
    }
    val rejectRate = if (r.metrics.totalBeats > 0)
        100 - (100 * r.metrics.validBeats / r.metrics.totalBeats) else 0
    if (rejectRate > 0) parts += "$rejectRate% of detected beats were rejected as outliers."
    parts += "Sample rate ${r.sampleRateHz.roundToInt()} Hz."
    return parts.joinToString(" ")
}
