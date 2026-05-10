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

private enum class Metric { Bpm, Rmssd, Sdnn, Pnn50, Quality, CrestTime, Aix, Ri, AGI, VascularAge }

/**
 * Cardiac Snapshot view — fingertip + forearm reports composed into one report
 * that surfaces each biomarker from its most reliable site:
 *   - HR / HRV (BPM, RMSSD, SDNN, pNN50) from the fingertip stage (red channel,
 *     no saturation in the bandpass output, ERMA peak detection works cleanly).
 *   - Pulse morphology (Crest Time, RI, AIx, AGI) from the forearm stage (green
 *     channel, dicrotic notch is anatomically present, no red saturation).
 * The signal chart and ROI image come from the forearm stage; the RR tachogram
 * comes from the fingertip stage (since it's driven by the timing source).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompositeReportSheet(
    composite: MeasurementViewModel.CompositeReport,
    onDismiss: () -> Unit,
    onTagSelect: ((StateTag) -> Unit)? = null
) {
    // Merge: forearm carries the signal trace and morphology, fingertip carries
    // the timing-based metrics. sessionPath stays on the forearm (= most recent
    // session); both stages are still saved as their own session files on disk.
    val merged = composite.forearm.copy(
        metrics = composite.fingertip.metrics,
        rrMs = composite.fingertip.rrMs,
        peaks = composite.fingertip.peaks,
        spectralBpm = composite.fingertip.spectralBpm,
        bpmConfident = composite.fingertip.bpmConfident,
        morphology = composite.forearm.morphology,
        site = MeasurementViewModel.Site.Forearm
    )
    ReportSheet(
        report = merged,
        onDismiss = onDismiss,
        onTagSelect = onTagSelect,
        headerOverride = "Cardiac Snapshot — fingertip + forearm"
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(
    report: MeasurementViewModel.Report,
    onDismiss: () -> Unit,
    onTagSelect: ((StateTag) -> Unit)? = null,
    headerOverride: String? = null
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
            onDone = onDismiss,
            headerOverride = headerOverride
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
    onDone: () -> Unit,
    headerOverride: String? = null
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
                    headerOverride
                        ?: if (report.timedOut) "Recording incomplete" else "Recording complete",
                    color = if (report.timedOut && headerOverride == null) Warn else Color.White,
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
            tag = if (report.bpmConfident) "BPM" else "BPM · low confidence",
            value = report.metrics.bpm?.roundToInt()?.toString() ?: "—",
            unit = "BPM",
            interpretation = if (report.bpmConfident) bpmInterpretation(report.metrics.bpm)
                else "Peak detector and spectral cross-check disagree by more than 20 %. Take this BPM with skepticism and re-measure — the HRV numbers below inherit the same uncertainty.",
            highlightColor = if (report.bpmConfident) Pulse else Color(0xFFFFC078),  // amber
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

        // Pulse-wave morphology (AIx, Crest Time, etc.) — only when enough clean beats.
        if (report.morphology != null && report.morphology.isAvailable) {
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Pulse morphology",
                    color = OnSurfaceMuted,
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "${report.morphology.nBeats} clean beats averaged",
                    color = OnSurfaceMuted.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(Modifier.height(8.dp))
            AveragedBeatChart(morphology = report.morphology)
            Spacer(Modifier.height(10.dp))
            MorphologyMetricRow("Crest Time", report.morphology.crestTimeMs?.let { "${it.toInt()}" } ?: "—",
                "ms", "How quickly the systolic upstroke peaks. Shorter = quicker ejection.",
                onInfo = { onShowExplainer(Metric.CrestTime) })
            Spacer(Modifier.height(6.dp))
            MorphologyMetricRow("Augmentation Index", formatAix(report.morphology.augmentationIndex),
                "%", "Wave reflection from the periphery. Higher = stiffer arteries / older vasculature.",
                onInfo = { onShowExplainer(Metric.Aix) })
            Spacer(Modifier.height(6.dp))
            MorphologyMetricRow("Reflection Index", formatPct1(report.morphology.reflectionIndex),
                "%", "Strength of the diastolic peak relative to systolic. Reflects peripheral resistance.",
                onInfo = { onShowExplainer(Metric.Ri) })
            Spacer(Modifier.height(6.dp))
            MorphologyMetricRow("Aging Index", formatAgi(report.morphology.agingIndex),
                "research", "Composite from the 2nd derivative of the pulse waveform (Takazawa). Uncalibrated on this device — research metric only.",
                onInfo = { onShowExplainer(Metric.AGI) })
            Spacer(Modifier.height(6.dp))
            // Vascular age computed from AGI via the literature regression. Our AGI
            // normalisation does not match Takazawa 1998's |PPG''|-based one, so
            // the years value would be misleading. Hidden until calibrated.
            MorphologyMetricRow("Vascular age", "—",
                "uncalibrated", "Hidden until the Aging Index is calibrated against ≥50 paired measurements vs a validated device. Don't infer a years number from this.",
                onInfo = { onShowExplainer(Metric.VascularAge) })
        }

        // ------------ Ventricular function (research) -----------------------
        if (report.morphology?.isAvailable == true) {
            val m = report.morphology
            val anyVent = m.lvetMs != null || m.maxUpstrokePerSec != null || m.pulseAmpVarPct != null
            if (anyVent) {
                Spacer(Modifier.height(18.dp))
                Text(
                    "Ventricular function (research)",
                    color = OnSurfaceMuted,
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.height(6.dp))
                MorphologyMetricRow("LVET",
                    m.lvetMs?.let { "${it.toInt()}" } ?: "—",
                    "ms",
                    "Left ventricular ejection time — duration the LV is actively ejecting blood (foot to dicrotic notch). Normal 250–350 ms at rest; shorter values can reflect reduced contractility or low stroke volume. Site-dependent — use as within-self trend."
                )
                Spacer(Modifier.height(6.dp))
                MorphologyMetricRow("Max upstroke",
                    m.maxUpstrokePerSec?.let { "%.1f".format(it) } ?: "—",
                    "1/s",
                    "Peak rate of the systolic rise, normalised by pulse amplitude. Proxy for ventricular contractility (peripheral dP/dt). Higher = more forceful ejection. Within-self trend metric."
                )
                Spacer(Modifier.height(6.dp))
                MorphologyMetricRow("Amplitude variability",
                    m.pulseAmpVarPct?.let { "%.1f".format(it) } ?: "—",
                    "%",
                    "Coefficient of variation of beat-to-beat pulse amplitude. Reflects stroke-volume variability and respiratory amplitude modulation. Healthy resting typically 5–15 %; higher with irregular rhythm or large breaths."
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
                Metric.CrestTime -> ExplainerBlock(
                    title = "Crest Time",
                    subtitle = "Time from foot to systolic peak (ms)",
                    body = listOf(
                        "Measures how quickly the pressure wave rises from its baseline (foot) to its peak (systole). A faster upstroke means lower peripheral resistance and more elastic vessels.",
                        "Typical adult range at rest: 100–250 ms. Long crest times can suggest stiffer or more resistant vasculature; short ones suggest the opposite.",
                        "Interpret on yourself — within-person trends are far more meaningful than absolute numbers because phone PPG smooths the upstroke slightly compared to dedicated sensors."
                    )
                )
                Metric.Aix -> ExplainerBlock(
                    title = "Augmentation Index (AIx)",
                    subtitle = "Wave reflection / arterial stiffness proxy",
                    body = listOf(
                        "AIx is the ratio of the secondary wave amplitude (reflected from the periphery) to the primary systolic peak. Reported as a percentage.",
                        "Negative AIx is typical in young, elastic vasculature (the reflected wave arrives during diastole, lifting the diastolic baseline without augmenting the systolic peak).",
                        "AIx rises with age, hypertension, smoking, and other cardiovascular stressors. Smartphone-PPG-derived AIx correlates r ≈ 0.7–0.85 with the clinical gold-standard tonometry.",
                        "AIx is heart-rate dependent — published AIx values are usually corrected to 75 BPM (AIx@75). We don't apply that correction here, so use AIx as a within-yourself trend rather than a clinical number."
                    )
                )
                Metric.Ri -> ExplainerBlock(
                    title = "Reflection Index (RI)",
                    subtitle = "Diastolic vs systolic peak amplitude",
                    body = listOf(
                        "Ratio of the diastolic peak amplitude to the systolic peak amplitude, expressed as a percentage.",
                        "Captures how much pressure-wave energy is reflected back from peripheral vessels. Goes up with peripheral resistance.",
                        "Like AIx, most useful for tracking your own trends rather than absolute clinical thresholds."
                    )
                )
                Metric.AGI -> ExplainerBlock(
                    title = "Aging Index (AGI)",
                    subtitle = "Takazawa's vascular-age composite from the 2nd derivative of the pulse",
                    body = listOf(
                        "AGI is computed from five named extrema (a, b, c, d, e) on the second derivative of the pulse waveform. Formula: AGI = (b − c − d − e) / a.",
                        "Validated by Takazawa et al. (1998 onward) to correlate r ≈ 0.8 with chronological age and to predict cardiovascular mortality in long-term cohort studies.",
                        "Lower AGI generally corresponds to a younger / more elastic vasculature; higher AGI to stiffer / older.",
                        "Single-shot smartphone measurements are noisier than dedicated PPG sensors — track trends in yourself rather than reading absolute values."
                    )
                )
                Metric.VascularAge -> ExplainerBlock(
                    title = "Vascular age estimate",
                    subtitle = "Years",
                    body = listOf(
                        "Rough estimate of how 'old' your arteries look, derived from the Aging Index using a published linear regression of AGI vs chronological age.",
                        "Treat this as an indicator, not a diagnosis. Single recordings vary; trends over weeks are more meaningful.",
                        "Lifestyle factors that lower vascular age: aerobic exercise, sleep quality, low salt, no smoking, controlled blood pressure."
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

private fun formatPct1(v: Float?): String = when {
    v == null -> "—"
    else -> "%.1f".format(v)
}

private fun formatAix(v: Float?): String = when {
    v == null -> "—"
    else -> if (v >= 0f) "+${"%.1f".format(v)}" else "%.1f".format(v)
}

private fun formatAgi(v: Float?): String = when {
    v == null -> "—"
    else -> "%.2f".format(v)
}

@Composable
private fun MorphologyMetricRow(
    label: String,
    value: String,
    unit: String,
    summary: String,
    onInfo: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceElev)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onInfo, modifier = Modifier.size(20.dp)) {
                    Icon(
                        Icons.Outlined.Info, contentDescription = "About $label",
                        tint = OnSurfaceMuted, modifier = Modifier.size(14.dp)
                    )
                }
            }
            Text(summary, color = OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = Accent, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(
                    unit,
                    color = OnSurfaceMuted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
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
