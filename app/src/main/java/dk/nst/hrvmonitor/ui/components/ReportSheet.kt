package dk.nst.hrvmonitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.nst.hrvmonitor.data.StateTag
import dk.nst.hrvmonitor.ppg.QualityScorer
import dk.nst.hrvmonitor.ui.theme.Accent
import dk.nst.hrvmonitor.ui.theme.Bad
import dk.nst.hrvmonitor.ui.theme.Good
import dk.nst.hrvmonitor.ui.theme.OnSurfaceMuted
import dk.nst.hrvmonitor.ui.theme.Pulse
import dk.nst.hrvmonitor.ui.theme.SurfaceDark
import dk.nst.hrvmonitor.ui.theme.SurfaceElev
import dk.nst.hrvmonitor.ui.theme.Warn
import dk.nst.hrvmonitor.viewmodel.MeasurementViewModel
import kotlin.math.roundToInt

private enum class Metric { Bpm, Rmssd, Sdnn, Pnn50, Quality }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(
    report: MeasurementViewModel.Report,
    onDismiss: () -> Unit,
    onTagSelect: ((StateTag) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var explainer by remember { mutableStateOf<Metric?>(null) }
    val score = remember(report) { QualityScorer.score(report) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        contentColor = Color.White,
        dragHandle = null
    ) {
        ReportContent(
            report = report,
            score = score,
            onShowExplainer = { explainer = it },
            onTagSelect = onTagSelect,
            onDone = onDismiss
        )
    }
    explainer?.let {
        MetricExplainerSheet(metric = it, score = score, onDismiss = { explainer = null })
    }
}

@Composable
private fun ReportContent(
    report: MeasurementViewModel.Report,
    score: QualityScorer.Score,
    onShowExplainer: (Metric) -> Unit,
    onTagSelect: ((StateTag) -> Unit)?,
    onDone: () -> Unit
) {
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (report.timedOut) "Recording incomplete" else "Recording complete",
                    color = if (report.timedOut) Warn else Color.White,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    "%.0f s good · %.0f Hz · %d/%d beats"
                        .format(report.goodSec, report.sampleRateHz,
                            report.metrics.validBeats, report.metrics.totalBeats),
                    color = OnSurfaceMuted,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            ConfidenceChip(score) { onShowExplainer(Metric.Quality) }
        }

        if (report.timedOut) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Wall-clock cap reached before reaching ${report.targetGoodSec.toInt()} s of clean signal. " +
                    "Interpret with caution.",
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(14.dp))

        InterpretationRow(
            label = "Heart rate",
            tag = "BPM",
            value = report.metrics.bpm?.roundToInt()?.toString() ?: "—",
            unit = "BPM",
            interpretation = bpmInterpretation(report.metrics.bpm),
            highlightColor = Pulse,
            onInfo = { onShowExplainer(Metric.Bpm) }
        )
        Spacer(Modifier.height(8.dp))
        InterpretationRow(
            label = "RMSSD",
            tag = "Parasympathetic",
            value = report.metrics.rmssdMs?.roundToInt()?.toString() ?: "—",
            unit = "ms",
            interpretation = rmssdInterpretation(report.metrics.rmssdMs),
            highlightColor = Accent,
            onInfo = { onShowExplainer(Metric.Rmssd) }
        )
        Spacer(Modifier.height(8.dp))
        InterpretationRow(
            label = "SDNN",
            tag = "Total HRV",
            value = report.metrics.sdnnMs?.roundToInt()?.toString() ?: "—",
            unit = "ms",
            interpretation = sdnnInterpretation(report.metrics.sdnnMs),
            highlightColor = Accent,
            onInfo = { onShowExplainer(Metric.Sdnn) }
        )
        Spacer(Modifier.height(8.dp))
        InterpretationRow(
            label = "pNN50",
            tag = "Parasympathetic",
            value = formatPnn(report.metrics.pnn50),
            unit = "%",
            interpretation = pnn50Interpretation(report.metrics.pnn50),
            highlightColor = Accent,
            onInfo = { onShowExplainer(Metric.Pnn50) }
        )

        // State tag chips — only enabled when the report belongs to the latest measurement.
        if (onTagSelect != null) {
            Spacer(Modifier.height(14.dp))
            Text("Tag this measurement", color = OnSurfaceMuted, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            StateTagRow(selected = report.tag, onSelect = onTagSelect)
        } else if (report.tag != null) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tag:", color = OnSurfaceMuted, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Text(
                    report.tag.displayName,
                    color = Accent,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StateTagRow(selected: StateTag?, onSelect: (StateTag) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        StateTag.entries.forEach { tag ->
            val isSel = tag == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isSel) Accent.copy(alpha = 0.22f) else SurfaceElev)
                    .clickable { onSelect(tag) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    tag.displayName,
                    color = if (isSel) Accent else Color.White,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun ConfidenceChip(score: QualityScorer.Score, onClick: () -> Unit) {
    val color = when (score.tier) {
        QualityScorer.Tier.Excellent -> Good
        QualityScorer.Tier.Good -> Good
        QualityScorer.Tier.Fair -> Warn
        QualityScorer.Tier.Low -> Bad
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                "${score.tier.label} · ${score.total}/100",
                color = color,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
            )
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Outlined.Info, contentDescription = "Quality details", tint = color)
        }
    }
}

@Composable
private fun InterpretationRow(
    label: String,
    tag: String,
    value: String,
    unit: String,
    interpretation: String,
    highlightColor: Color,
    onInfo: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceElev)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = OnSurfaceMuted, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onInfo, modifier = Modifier.size(20.dp)) {
                        Icon(
                            Icons.Outlined.Info, contentDescription = "About $label",
                            tint = OnSurfaceMuted, modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Text(
                    tag,
                    color = OnSurfaceMuted.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    color = highlightColor,
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    unit,
                    color = OnSurfaceMuted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(interpretation, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetricExplainerSheet(
    metric: Metric,
    score: QualityScorer.Score,
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
        val scroll = rememberScrollState()
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            when (metric) {
                Metric.Bpm -> ExplainerBlock(
                    title = "Heart rate (BPM)",
                    subtitle = "Beats per minute",
                    body = listOf(
                        "Your average heart rate over the recording — counted from peak-to-peak intervals in the bandpassed PPG signal.",
                        "Typical adult resting range: 60–100 BPM. Trained athletes can be lower (40–60). Above 100 at rest is called tachycardia.",
                        "Heart rate is naturally variable: caffeine, stress, posture, and recent activity can swing it 5–15 BPM in either direction within minutes."
                    )
                )
                Metric.Rmssd -> ExplainerBlock(
                    title = "RMSSD",
                    subtitle = "Root mean square of successive differences (parasympathetic)",
                    body = listOf(
                        "Computed as the square root of the average squared difference between consecutive heartbeat intervals (RR intervals).",
                        "RMSSD reflects your **parasympathetic** nervous system — the 'rest and digest' branch. When your body is recovered, well-rested, and not stressed, the gap between beats varies more, and RMSSD is higher.",
                        "Typical adult resting values are 20–50 ms. Above 60 ms suggests strong vagal tone (often seen in well-trained / well-rested individuals). Below 15 ms can indicate stress, illness, overtraining, or poor sleep — though noise can also cause low readings.",
                        "The clinical reference window is 5 minutes. For 50-second recordings like ours, the value is reliable for tracking your own day-to-day trends — less so for comparing absolute numbers across people."
                    )
                )
                Metric.Sdnn -> ExplainerBlock(
                    title = "SDNN",
                    subtitle = "Standard deviation of NN intervals (total HRV)",
                    body = listOf(
                        "Standard deviation of every heartbeat interval in the recording. It captures total variability across the whole window.",
                        "Where RMSSD is dominated by fast (parasympathetic) swings, SDNN includes both the fast and slow oscillations — so it also reflects **sympathetic** ('fight-or-flight') activity and broader autonomic balance.",
                        "Typical adult resting values are 30–100 ms. Higher = more variability = generally healthier autonomic regulation.",
                        "Like RMSSD, SDNN is most meaningful when tracked over time within the same person, in similar conditions."
                    )
                )
                Metric.Pnn50 -> ExplainerBlock(
                    title = "pNN50",
                    subtitle = "Percentage of consecutive RR intervals differing by > 50 ms (parasympathetic)",
                    body = listOf(
                        "Counts pairs of consecutive heartbeats whose interval changes by more than 50 ms, expressed as a percentage of all such pairs.",
                        "Like RMSSD, pNN50 is a **parasympathetic** marker — it goes up when your vagal tone is strong (relaxed, well-rested).",
                        "Typical adult ranges: < 3 % low, 3–15 % average, > 15 % above-average parasympathetic activity.",
                        "Easier to interpret than RMSSD because the units (%) are intuitive, but more affected by short recording windows."
                    )
                )
                Metric.Quality -> QualityExplainer(score)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "PulseTrace is a personal/wellness tool. These numbers aren't validated for medical diagnosis.",
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceElev, contentColor = Color.White)
            ) { Text("Close") }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ExplainerBlock(title: String, subtitle: String, body: List<String>) {
    Text(
        title,
        color = Color.White,
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
    )
    Text(subtitle, color = OnSurfaceMuted, style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(12.dp))
    body.forEachIndexed { i, paragraph ->
        if (i > 0) Spacer(Modifier.height(10.dp))
        Text(paragraph, color = Color.White.copy(alpha = 0.92f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun QualityExplainer(score: QualityScorer.Score) {
    val color = when (score.tier) {
        QualityScorer.Tier.Excellent, QualityScorer.Tier.Good -> Good
        QualityScorer.Tier.Fair -> Warn
        QualityScorer.Tier.Low -> Bad
    }
    Text(
        "Quality score",
        color = Color.White,
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
    )
    Text(
        "${score.tier.label} · ${score.total}/100",
        color = color,
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(Modifier.height(6.dp))
    Text(score.tier.advice, color = OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(14.dp))
    Text(
        "How it's computed",
        color = OnSurfaceMuted,
        style = MaterialTheme.typography.labelLarge
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "Five independent quality checks, weighted by how much each one tells us about reliability:",
        color = Color.White.copy(alpha = 0.92f),
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(Modifier.height(8.dp))
    score.components.forEach { c ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(c.name, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Text(c.note, color = OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                "${c.score}/${c.maxScore}",
                color = if (c.score >= c.maxScore * 0.7) Good
                    else if (c.score >= c.maxScore * 0.4) Warn
                    else Bad,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            )
        }
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
        r < 60 -> "$r ms — average overall variability for a 50 s reading."
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
    val efficiency = if (r.durationSec > 0f) (100f * r.goodSec / r.durationSec).roundToInt() else 0
    parts += "Captured ${r.goodSec.roundToInt()} s of clean signal in ${r.durationSec.roundToInt()} s wall-clock ($efficiency% efficient)."
    val rejectRate = if (r.metrics.totalBeats > 0)
        100 - (100 * r.metrics.validBeats / r.metrics.totalBeats) else 0
    if (rejectRate > 0) parts += "$rejectRate% of detected beats were rejected as outliers."
    parts += "Sample rate ${r.sampleRateHz.roundToInt()} Hz."
    if (r.spectralBpm > 1f && r.metrics.bpm != null) {
        parts += "Spectral cross-check: ${r.spectralBpm.roundToInt()} BPM."
    }
    return parts.joinToString(" ")
}
