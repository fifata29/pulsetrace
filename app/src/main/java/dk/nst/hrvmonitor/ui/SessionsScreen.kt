package dk.nst.hrvmonitor.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dk.nst.hrvmonitor.data.StateTag
import dk.nst.hrvmonitor.ppg.HrvCalculator
import dk.nst.hrvmonitor.ppg.PulseMorphology
import dk.nst.hrvmonitor.ppg.SignalProcessor
import dk.nst.hrvmonitor.ui.components.ReportSheet
import dk.nst.hrvmonitor.ui.theme.Accent
import dk.nst.hrvmonitor.ui.theme.OnSurfaceMuted
import dk.nst.hrvmonitor.ui.theme.Pulse
import dk.nst.hrvmonitor.ui.theme.SurfaceDark
import dk.nst.hrvmonitor.ui.theme.SurfaceElev
import dk.nst.hrvmonitor.ui.theme.Warn
import dk.nst.hrvmonitor.viewmodel.MeasurementViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private enum class HistoryTab { List, Trend }

@Composable
fun SessionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var sessions by remember { mutableStateOf<List<SessionEntry>>(emptyList()) }
    var selected by remember { mutableStateOf<MeasurementViewModel.Report?>(null) }
    var tab by remember { mutableStateOf(HistoryTab.List) }

    LaunchedEffect(Unit) {
        sessions = withContext(Dispatchers.IO) { loadSessions(context) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        "History",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        "${sessions.size} session${if (sessions.size == 1) "" else "s"} on this device",
                        color = OnSurfaceMuted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            TabPicker(tab) { tab = it }
            Spacer(Modifier.height(12.dp))

            if (sessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No sessions recorded yet",
                        color = OnSurfaceMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else when (tab) {
                HistoryTab.List -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)
                ) {
                    items(sessions) { entry ->
                        SessionRow(entry) { selected = entry.toReport() }
                    }
                }
                HistoryTab.Trend -> TrendView(sessions) { selected = it.toReport() }
            }
        }
    }

    selected?.let { report ->
        ReportSheet(report = report, onDismiss = { selected = null })
    }
}

@Composable
private fun TabPicker(current: HistoryTab, onPick: (HistoryTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceElev),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        for (t in HistoryTab.entries) {
            val sel = t == current
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (sel) Accent.copy(alpha = 0.22f) else Color.Transparent)
                    .pointerInput(Unit) { detectTapGestures(onTap = { onPick(t) }) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    t.name,
                    color = if (sel) Accent else Color.White,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}

@Composable
private fun SessionRow(entry: SessionEntry, onClick: () -> Unit) {
    val statusColor = if (entry.timedOut) Warn else Accent
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceElev)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.formattedTimestamp,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                buildString {
                    append("${entry.durationSec.roundToInt()} s")
                    if (entry.timedOut) append(" · timed out")
                    if (entry.totalBeats > 0) append(" · ${entry.validBeats}/${entry.totalBeats} beats")
                },
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (entry.bpm != null) "${entry.bpm.roundToInt()}" else "—",
                color = statusColor,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            )
            Text("BPM", color = OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                entry.rmssdMs?.let { "${it.roundToInt()}" } ?: "—",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Text("RMSSD ms", color = OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private data class SessionEntry(
    val sessionId: String,
    val startedAt: Long,
    val durationSec: Float,
    val goodSec: Float,
    val targetGoodSec: Float,
    val sampleRateHz: Float,
    val coverage: Float,
    val timedOut: Boolean,
    val bpm: Float?,
    val spectralBpm: Float,
    val rmssdMs: Float?,
    val sdnnMs: Float?,
    val pnn50: Float?,
    val validBeats: Int,
    val totalBeats: Int,
    val rrMs: List<Float>,
    val peakTimesSec: List<Float>,
    val sessionPath: String,
    val roi: MeasurementViewModel.RoiInfo?,
    val tag: StateTag?,
    val morphology: PulseMorphology.Result?
) {
    val formattedTimestamp: String
        get() {
            return try {
                val sdf = SimpleDateFormat("d MMM, HH:mm:ss", Locale.getDefault())
                sdf.format(Date(startedAt))
            } catch (_: Throwable) { sessionId }
        }

    fun toReport(): MeasurementViewModel.Report = MeasurementViewModel.Report(
        durationSec = durationSec,
        goodSec = goodSec,
        targetGoodSec = targetGoodSec,
        timedOut = timedOut,
        sampleRateHz = sampleRateHz,
        coverage = coverage,
        metrics = HrvCalculator.Metrics(
            bpm = bpm,
            sdnnMs = sdnnMs,
            rmssdMs = rmssdMs,
            pnn50 = pnn50,
            validBeats = validBeats,
            totalBeats = totalBeats
        ),
        rrMs = rrMs,
        peaks = peakTimesSec.map { SignalProcessor.Peak(it, 0f) },
        sessionPath = sessionPath,
        roi = roi,
        spectralBpm = spectralBpm,
        tag = tag,
        morphology = morphology
    )
}

private fun loadSessions(context: Context): List<SessionEntry> {
    val root = File(context.getExternalFilesDir(null), "sessions")
    if (!root.exists()) return emptyList()
    val out = ArrayList<SessionEntry>()
    val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
    for (dir in dirs) {
        val summary = File(dir, "summary.json")
        if (!summary.exists()) continue
        val entry = parseSummary(summary, dir.absolutePath) ?: continue
        out += entry
    }
    out.sortByDescending { it.startedAt }
    return out
}

private fun parseSummary(file: File, dirPath: String): SessionEntry? {
    return try {
        val obj = JSONObject(file.readText())
        val metrics = obj.optJSONObject("metrics")
        val roiObj = obj.optJSONObject("roi")
        val rrArr = obj.optJSONArray("rr_intervals_ms")
        val peakArr = obj.optJSONArray("peak_times_sec")
        val rr = ArrayList<Float>()
        if (rrArr != null) for (i in 0 until rrArr.length()) rr += rrArr.optDouble(i).toFloat()
        val peaks = ArrayList<Float>()
        if (peakArr != null) for (i in 0 until peakArr.length()) peaks += peakArr.optDouble(i).toFloat()

        val roi = roiObj?.let { r ->
            val tilesArr = r.optJSONArray("tile_indices")
            val tiles = IntArray(tilesArr?.length() ?: 0)
            if (tilesArr != null) for (i in 0 until tilesArr.length()) tiles[i] = tilesArr.optInt(i)
            MeasurementViewModel.RoiInfo(
                rowStart = r.optInt("row_start"),
                rowEnd = r.optInt("row_end"),
                colStart = r.optInt("col_start"),
                colEnd = r.optInt("col_end"),
                tileIndices = tiles,
                bestScore = r.optDouble("best_score", 0.0).toFloat(),
                medianFreqHz = r.optDouble("median_freq_hz", 0.0).toFloat(),
                acceptable = r.optBoolean("acceptable", true)
            )
        }

        SessionEntry(
            sessionId = obj.optString("session_id"),
            startedAt = obj.optLong("started_at"),
            durationSec = obj.optDouble("duration_sec").toFloat(),
            goodSec = obj.optDouble("good_sec").toFloat(),
            targetGoodSec = obj.optDouble("target_good_sec", 50.0).toFloat(),
            sampleRateHz = obj.optDouble("sample_rate_hz").toFloat(),
            coverage = obj.optDouble("coverage").toFloat(),
            timedOut = obj.optBoolean("timed_out", false),
            bpm = metrics?.optNullableFloat("bpm"),
            spectralBpm = metrics?.optDouble("spectral_bpm", 0.0)?.toFloat() ?: 0f,
            rmssdMs = metrics?.optNullableFloat("rmssd_ms"),
            sdnnMs = metrics?.optNullableFloat("sdnn_ms"),
            pnn50 = metrics?.optNullableFloat("pnn50_pct"),
            validBeats = metrics?.optInt("valid_beats") ?: 0,
            totalBeats = metrics?.optInt("total_beats") ?: 0,
            rrMs = rr,
            peakTimesSec = peaks,
            sessionPath = dirPath,
            roi = roi,
            tag = StateTag.fromKey(obj.optString("tag", null)),
            morphology = parseMorphology(obj.optJSONObject("morphology"))
        )
    } catch (_: Throwable) {
        null
    }
}

private fun JSONObject.optNullableFloat(key: String): Float? {
    if (isNull(key)) return null
    return optDouble(key, Double.NaN).let { if (it.isNaN()) null else it.toFloat() }
}

// ---- Trend view ---------------------------------------------------------

private enum class TrendMetric(val label: String, val unit: String, val getter: (SessionEntry) -> Float?) {
    Bpm("BPM", "bpm", { it.bpm }),
    Rmssd("RMSSD", "ms", { it.rmssdMs }),
    Sdnn("SDNN", "ms", { it.sdnnMs }),
    Pnn50("pNN50", "%", { it.pnn50 }),
    Crest("Crest", "ms", { it.morphology?.crestTimeMs }),
    Aix("AIx", "%", { it.morphology?.augmentationIndex }),
    Ri("RI", "%", { it.morphology?.reflectionIndex }),
    VAge("vAge", "yr", { it.morphology?.vascularAgeYears })
}

private enum class TrendRange(val label: String, val hours: Long?) {
    H24("24h", 24),
    D7("7d", 24L * 7),
    D30("30d", 24L * 30),
    All("All", null)
}

@Composable
private fun TrendView(sessions: List<SessionEntry>, onSelectSession: (SessionEntry) -> Unit) {
    var metric by remember { mutableStateOf(TrendMetric.Rmssd) }
    var range by remember { mutableStateOf(TrendRange.D7) }
    val now = System.currentTimeMillis()
    val filtered = remember(sessions, range) {
        val cutoff = range.hours?.let { now - it * 3600_000L }
        if (cutoff == null) sessions else sessions.filter { it.startedAt >= cutoff }
    }.sortedBy { it.startedAt }

    Column(Modifier.fillMaxSize()) {
        // Metric selector chips (scrollable)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (m in TrendMetric.entries) {
                ChipToggle(label = m.label, selected = metric == m) { metric = m }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (r in TrendRange.entries) {
                Box(Modifier.weight(1f)) {
                    ChipToggle(label = r.label, selected = range == r, full = true) { range = r }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        TrendChart(
            entries = filtered,
            metric = metric,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            onTap = onSelectSession
        )
        Spacer(Modifier.height(10.dp))
        TrendSummary(filtered, metric)
    }
}

@Composable
private fun ChipToggle(label: String, selected: Boolean, full: Boolean = false, onClick: () -> Unit) {
    Box(
        (if (full) Modifier.fillMaxWidth() else Modifier)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Accent.copy(alpha = 0.22f) else SurfaceElev)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Accent else Color.White,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun TrendChart(
    entries: List<SessionEntry>,
    metric: TrendMetric,
    modifier: Modifier = Modifier,
    onTap: (SessionEntry) -> Unit
) {
    val points = remember(entries, metric) {
        entries.mapNotNull { e -> metric.getter(e)?.let { e to it } }
    }
    var tappedIdx by remember(metric, entries) { mutableStateOf<Int?>(null) }

    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceElev)
            .padding(12.dp)
    ) {
        if (points.size < 2) {
            Text(
                "Need at least 2 sessions with ${metric.label} to show a trend.",
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.Center)
            )
            return@Box
        }
        var canvasW by remember { mutableStateOf(1f) }
        var canvasH by remember { mutableStateOf(1f) }

        val tMin = points.first().first.startedAt.toFloat()
        val tMax = points.last().first.startedAt.toFloat().coerceAtLeast(tMin + 1f)
        val values = points.map { it.second }
        var yMin = values.min()
        var yMax = values.max()
        val pad = (yMax - yMin).coerceAtLeast(0.001f) * 0.15f
        yMin -= pad; yMax += pad
        val ySpan = (yMax - yMin).coerceAtLeast(0.001f)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(points) {
                    detectTapGestures { off ->
                        // Find nearest point (by x distance)
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        var best = -1; var bestD = Float.MAX_VALUE
                        for (i in points.indices) {
                            val px = w * (points[i].first.startedAt - tMin) / (tMax - tMin)
                            val py = h - h * (points[i].second - yMin) / ySpan
                            val d = (px - off.x) * (px - off.x) + (py - off.y) * (py - off.y)
                            if (d < bestD) { bestD = d; best = i }
                        }
                        if (best >= 0 && bestD < (40f * 40f)) {
                            tappedIdx = best
                            onTap(points[best].first)
                        }
                    }
                }
        ) {
            canvasW = size.width; canvasH = size.height
            val w = size.width; val h = size.height

            // Subtle horizontal gridlines
            val gridColor = Color.White.copy(alpha = 0.06f)
            for (k in 0..4) {
                val y = h * k / 4
                drawLine(gridColor, androidx.compose.ui.geometry.Offset(0f, y),
                    androidx.compose.ui.geometry.Offset(w, y), strokeWidth = 1f)
            }

            // Connecting line
            val path = androidx.compose.ui.graphics.Path()
            for ((i, p) in points.withIndex()) {
                val x = w * (p.first.startedAt - tMin) / (tMax - tMin)
                val y = h - h * (p.second - yMin) / ySpan
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = Accent.copy(alpha = 0.85f),
                style = Stroke(width = 2.5f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )

            // Dots
            for ((i, p) in points.withIndex()) {
                val x = w * (p.first.startedAt - tMin) / (tMax - tMin)
                val y = h - h * (p.second - yMin) / ySpan
                drawCircle(Accent, radius = 5f, center = androidx.compose.ui.geometry.Offset(x, y))
                if (i == tappedIdx) {
                    drawCircle(Color.White, radius = 8f,
                        center = androidx.compose.ui.geometry.Offset(x, y),
                        style = Stroke(width = 2f))
                }
            }
        }

        // Y-axis labels (top-right & bottom-right)
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = 4.dp)
        ) {
            Text("${prettyNum(yMax)} ${metric.unit}",
                color = OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
        }
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 4.dp)
        ) {
            Text("${prettyNum(yMin)} ${metric.unit}",
                color = OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
        }

        // X-axis date hints
        val first = points.first().first
        val last = points.last().first
        Text(
            shortDate(first.startedAt),
            color = OnSurfaceMuted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomStart)
        )
        Text(
            shortDate(last.startedAt),
            color = OnSurfaceMuted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun TrendSummary(entries: List<SessionEntry>, metric: TrendMetric) {
    val values = entries.mapNotNull { metric.getter(it) }
    if (values.isEmpty()) {
        Text(
            "No ${metric.label} data in this range.",
            color = OnSurfaceMuted,
            style = MaterialTheme.typography.bodySmall
        )
        return
    }
    val sorted = values.sorted()
    val median = sorted[sorted.size / 2]
    val mean = values.average().toFloat()
    val min = sorted.first(); val max = sorted.last()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceElev)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        SummaryStat("min", "${prettyNum(min)} ${metric.unit}")
        Spacer(Modifier.width(16.dp))
        SummaryStat("median", "${prettyNum(median)} ${metric.unit}")
        Spacer(Modifier.width(16.dp))
        SummaryStat("mean", "${prettyNum(mean)} ${metric.unit}")
        Spacer(Modifier.width(16.dp))
        SummaryStat("max", "${prettyNum(max)} ${metric.unit}")
        Spacer(Modifier.weight(1f))
        SummaryStat("n", "${values.size}")
    }
}

@Composable
private fun SummaryStat(label: String, value: String) {
    Column {
        Text(label, color = OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
        Text(value, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
    }
}

private fun prettyNum(v: Float): String = when {
    kotlin.math.abs(v) >= 100f -> v.roundToInt().toString()
    kotlin.math.abs(v) >= 10f -> "%.1f".format(v)
    else -> "%.2f".format(v)
}

private fun shortDate(ms: Long): String = try {
    SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(ms))
} catch (_: Throwable) { "" }

private fun parseMorphology(m: JSONObject?): PulseMorphology.Result? {
    if (m == null) return null
    val avgArr = m.optJSONArray("averaged_beat") ?: return null
    val timeArr = m.optJSONArray("averaged_beat_time_sec") ?: return null
    val avg = FloatArray(avgArr.length()) { avgArr.optDouble(it).toFloat() }
    val time = FloatArray(timeArr.length()) { timeArr.optDouble(it).toFloat() }
    return PulseMorphology.Result(
        isAvailable = true,
        nBeats = m.optInt("n_beats"),
        nBeatsTotal = m.optInt("n_beats"),
        averagedBeat = avg,
        averagedBeatTime = time,
        footIdx = m.optInt("foot_idx", -1),
        systolicPeakIdx = m.optInt("systolic_peak_idx", -1),
        dicroticNotchIdx = m.optInt("dicrotic_notch_idx", -1),
        diastolicPeakIdx = m.optInt("diastolic_peak_idx", -1),
        crestTimeMs = m.optNullableFloat("crest_time_ms"),
        reflectionIndex = m.optNullableFloat("reflection_index_pct"),
        augmentationIndex = m.optNullableFloat("augmentation_index_pct"),
        stiffnessIndexInv = m.optNullableFloat("stiffness_index_inv_s"),
        agingIndex = m.optNullableFloat("aging_index"),
        vascularAgeYears = m.optNullableFloat("vascular_age_years"),
        // Ventricular-function biomarkers — null in older sessions (recorded
        // before this build); read defensively so historical reports still load.
        lvetMs = m.optNullableFloat("lvet_ms"),
        maxUpstrokePerSec = m.optNullableFloat("max_upstroke_per_sec"),
        pulseAmpVarPct = m.optNullableFloat("pulse_amp_variation_pct")
    )
}
