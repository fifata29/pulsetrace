package dk.nst.hrvmonitor.ui

import android.content.Context
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

@Composable
fun SessionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var sessions by remember { mutableStateOf<List<SessionEntry>>(emptyList()) }
    var selected by remember { mutableStateOf<MeasurementViewModel.Report?>(null) }

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
            Spacer(Modifier.height(12.dp))

            if (sessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No sessions recorded yet",
                        color = OnSurfaceMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)
                ) {
                    items(sessions) { entry ->
                        SessionRow(entry) { selected = entry.toReport() }
                    }
                }
            }
        }
    }

    selected?.let { report ->
        ReportSheet(report = report, onDismiss = { selected = null })
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
        vascularAgeYears = m.optNullableFloat("vascular_age_years")
    )
}
