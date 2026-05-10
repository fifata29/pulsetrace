package dk.nst.hrvmonitor.data

import android.content.Context
import android.os.Build
import android.util.Log
import dk.nst.hrvmonitor.ppg.HrvCalculator
import dk.nst.hrvmonitor.ppg.QualityScorer
import dk.nst.hrvmonitor.ppg.SignalProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Streams PPG samples to a CSV file and writes a JSON summary at the end of each session.
 * Files land under <externalFilesDir>/sessions/<timestamp>/, accessible via:
 *   adb pull /sdcard/Android/data/dk.nst.hrvmonitor.debug/files/sessions
 *
 * Designed to be cheap on the analyzer thread: samples are dropped into a SharedFlow that
 * the IO writer drains, so a slow disk never back-pressures the camera.
 */
class SessionRecorder(private val appContext: Context) {

    data class SampleRow(
        val tNs: Long,
        val red: Float,
        val luma: Float,
        val coverage: Float
    )

    data class Session(val dir: File, val samplesCsv: File, val summaryJson: File, val startedAt: Long)

    private val sampleFlow = MutableSharedFlow<SampleRow>(
        extraBufferCapacity = 2048,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var writerJob: Job? = null
    private var current: Session? = null
    private var samplesWritten: Long = 0

    fun start(): Session {
        finishWriterIfActive()
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date())
        val root = File(appContext.getExternalFilesDir(null), "sessions/$ts")
        root.mkdirs()
        val samples = File(root, "samples.csv")
        val summary = File(root, "summary.json")

        val session = Session(root, samples, summary, System.currentTimeMillis())
        current = session
        samplesWritten = 0

        writerJob = ioScope.launch {
            BufferedWriter(FileWriter(samples)).use { w ->
                w.write("# PulseTrace recording\n")
                w.write("# session_id: $ts\n")
                w.write("# device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.SDK_INT})\n")
                w.write("# started_at: ${session.startedAt}\n")
                w.write("timestamp_ns,red,luma,coverage\n")
                sampleFlow.collect { s ->
                    w.write("${s.tNs},${"%.3f".format(Locale.US, s.red)}," +
                        "${"%.3f".format(Locale.US, s.luma)}," +
                        "${"%.4f".format(Locale.US, s.coverage)}\n")
                    samplesWritten++
                    if (samplesWritten % 64L == 0L) w.flush()
                }
            }
        }
        Log.i(TAG, "Session started at ${session.dir.absolutePath}")
        return session
    }

    fun appendSample(row: SampleRow) {
        if (current == null) return
        sampleFlow.tryEmit(row)
    }

    /**
     * Closes the CSV writer and writes summary.json with metrics and full RR/peak series.
     * Safe to call when no session is active (no-op).
     */
    /** Optional ROI metadata added to summary.json. */
    data class RoiInfo(
        val rowStart: Int,
        val rowEnd: Int,
        val colStart: Int,
        val colEnd: Int,
        val tileIndices: IntArray,
        val gridCols: Int,
        val gridRows: Int,
        val bestScore: Float,
        val medianFreqHz: Float,
        val acceptable: Boolean
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    fun stop(
        durationSec: Float,
        sampleRateHz: Float,
        coverage: Float,
        signal: List<SignalProcessor.Sample>,
        peaks: List<SignalProcessor.Peak>,
        rrMs: List<Float>,
        metrics: HrvCalculator.Metrics,
        roi: RoiInfo? = null,
        goodSec: Float = 0f,
        targetGoodSec: Float = 0f,
        timedOut: Boolean = false,
        spectralBpm: Float = 0f,
        qualityScore: QualityScorer.Score? = null
    ): Session? {
        val session = current ?: return null
        finishWriterIfActive()

        ioScope.launch {
            try {
                BufferedWriter(FileWriter(session.summaryJson)).use { w ->
                    w.write(buildSummaryJson(
                        session, durationSec, sampleRateHz, coverage,
                        peaks, rrMs, metrics, samplesWritten,
                        roi, goodSec, targetGoodSec, timedOut, spectralBpm,
                        qualityScore
                    ))
                }
                Log.i(TAG, "Summary written to ${session.summaryJson.absolutePath}")
            } catch (t: Throwable) {
                Log.e(TAG, "summary write failed", t)
            }
        }
        current = null
        return session
    }

    private fun finishWriterIfActive() {
        try { writerJob?.cancel() } catch (_: Throwable) {}
        writerJob = null
    }

    private fun buildSummaryJson(
        s: Session,
        durationSec: Float,
        fs: Float,
        coverage: Float,
        peaks: List<SignalProcessor.Peak>,
        rrMs: List<Float>,
        metrics: HrvCalculator.Metrics,
        nSamples: Long,
        roi: RoiInfo?,
        goodSec: Float,
        targetGoodSec: Float,
        timedOut: Boolean,
        spectralBpm: Float,
        qualityScore: QualityScorer.Score?
    ): String = buildString {
        append("{\n")
        append("  \"session_id\": \"${s.dir.name}\",\n")
        append("  \"started_at\": ${s.startedAt},\n")
        append("  \"duration_sec\": ${"%.3f".format(Locale.US, durationSec)},\n")
        append("  \"good_sec\": ${"%.3f".format(Locale.US, goodSec)},\n")
        append("  \"target_good_sec\": ${"%.3f".format(Locale.US, targetGoodSec)},\n")
        append("  \"timed_out\": $timedOut,\n")
        append("  \"sample_rate_hz\": ${"%.3f".format(Locale.US, fs)},\n")
        append("  \"n_samples\": $nSamples,\n")
        append("  \"coverage\": ${"%.4f".format(Locale.US, coverage)},\n")
        append("  \"device\": \"${Build.MANUFACTURER} ${Build.MODEL} / API ${Build.VERSION.SDK_INT}\",\n")
        if (roi != null) {
            append("  \"roi\": {\n")
            append("    \"grid_cols\": ${roi.gridCols},\n")
            append("    \"grid_rows\": ${roi.gridRows},\n")
            append("    \"row_start\": ${roi.rowStart},\n")
            append("    \"row_end\": ${roi.rowEnd},\n")
            append("    \"col_start\": ${roi.colStart},\n")
            append("    \"col_end\": ${roi.colEnd},\n")
            append("    \"tile_indices\": [${roi.tileIndices.joinToString(",")}],\n")
            append("    \"best_score\": ${"%.4f".format(Locale.US, roi.bestScore)},\n")
            append("    \"median_freq_hz\": ${"%.3f".format(Locale.US, roi.medianFreqHz)},\n")
            append("    \"acceptable\": ${roi.acceptable}\n")
            append("  },\n")
        }
        append("  \"metrics\": {\n")
        append("    \"bpm\": ${num(metrics.bpm)},\n")
        append("    \"spectral_bpm\": ${"%.3f".format(Locale.US, spectralBpm)},\n")
        append("    \"rmssd_ms\": ${num(metrics.rmssdMs)},\n")
        append("    \"sdnn_ms\": ${num(metrics.sdnnMs)},\n")
        append("    \"pnn50_pct\": ${num(metrics.pnn50)},\n")
        append("    \"valid_beats\": ${metrics.validBeats},\n")
        append("    \"total_beats\": ${metrics.totalBeats}\n")
        append("  },\n")
        append("  \"rr_intervals_ms\": [${rrMs.joinToString(",") { "%.2f".format(Locale.US, it) }}],\n")
        append("  \"peak_times_sec\": [${peaks.joinToString(",") { "%.4f".format(Locale.US, it.tSec) }}]")
        if (qualityScore != null) {
            append(",\n")
            append("  \"quality\": {\n")
            append("    \"total\": ${qualityScore.total},\n")
            append("    \"tier\": \"${qualityScore.tier.name}\",\n")
            append("    \"components\": {\n")
            qualityScore.components.forEachIndexed { i, c ->
                append("      \"${c.name.replace(" ", "_").lowercase()}\": ${c.score}")
                if (i < qualityScore.components.lastIndex) append(",")
                append("\n")
            }
            append("    }\n")
            append("  }")
        }
        append("\n}\n")
    }

    /**
     * Re-write summary.json adding (or updating) the user-supplied state tag.
     * Loaded by hand-rolled JSON parsing to avoid depending on a JSON library here.
     */
    fun appendTagToSummary(sessionDir: String, tag: dk.nst.hrvmonitor.data.StateTag) {
        ioScope.launch {
            // The summary write happens on the same scope and may not have flushed yet.
            // Poll up to 1 second for the file to appear.
            val summary = File(sessionDir, "summary.json")
            repeat(10) {
                if (summary.exists() && summary.length() > 0) return@repeat
                delay(100)
            }
            if (!summary.exists()) {
                Log.w(TAG, "appendTag: summary.json never appeared in $sessionDir")
                return@launch
            }
            try {
                val text = summary.readText()
                val withoutTag = text.replace(Regex(",\\s*\"tag\"\\s*:\\s*\"[^\"]*\""), "")
                val insertPos = withoutTag.lastIndexOf("}")
                if (insertPos < 0) return@launch
                val updated = withoutTag.substring(0, insertPos).trimEnd().trimEnd(',') +
                    ",\n  \"tag\": \"${tag.key}\"\n" +
                    withoutTag.substring(insertPos)
                summary.writeText(updated)
                Log.i(TAG, "Tag ${tag.key} written to ${summary.name}")
            } catch (t: Throwable) {
                Log.e(TAG, "tag write failed", t)
            }
        }
    }

    private fun num(v: Float?): String =
        if (v == null || v.isNaN() || v.isInfinite()) "null"
        else "%.3f".format(Locale.US, v)

    companion object {
        private const val TAG = "SessionRecorder"
    }
}
