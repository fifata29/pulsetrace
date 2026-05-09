package dk.nst.hrvmonitor.data

import android.content.Context
import android.os.Build
import android.util.Log
import dk.nst.hrvmonitor.ppg.HrvCalculator
import dk.nst.hrvmonitor.ppg.SignalProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
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
    fun stop(
        durationSec: Float,
        sampleRateHz: Float,
        coverage: Float,
        signal: List<SignalProcessor.Sample>,
        peaks: List<SignalProcessor.Peak>,
        rrMs: List<Float>,
        metrics: HrvCalculator.Metrics
    ): Session? {
        val session = current ?: return null
        finishWriterIfActive()

        ioScope.launch {
            try {
                BufferedWriter(FileWriter(session.summaryJson)).use { w ->
                    w.write(buildSummaryJson(
                        session, durationSec, sampleRateHz, coverage,
                        peaks, rrMs, metrics, samplesWritten
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
        nSamples: Long
    ): String = buildString {
        append("{\n")
        append("  \"session_id\": \"${s.dir.name}\",\n")
        append("  \"started_at\": ${s.startedAt},\n")
        append("  \"duration_sec\": ${"%.3f".format(Locale.US, durationSec)},\n")
        append("  \"sample_rate_hz\": ${"%.3f".format(Locale.US, fs)},\n")
        append("  \"n_samples\": $nSamples,\n")
        append("  \"coverage\": ${"%.4f".format(Locale.US, coverage)},\n")
        append("  \"device\": \"${Build.MANUFACTURER} ${Build.MODEL} / API ${Build.VERSION.SDK_INT}\",\n")
        append("  \"metrics\": {\n")
        append("    \"bpm\": ${num(metrics.bpm)},\n")
        append("    \"rmssd_ms\": ${num(metrics.rmssdMs)},\n")
        append("    \"sdnn_ms\": ${num(metrics.sdnnMs)},\n")
        append("    \"pnn50_pct\": ${num(metrics.pnn50)},\n")
        append("    \"valid_beats\": ${metrics.validBeats},\n")
        append("    \"total_beats\": ${metrics.totalBeats}\n")
        append("  },\n")
        append("  \"rr_intervals_ms\": [${rrMs.joinToString(",") { "%.2f".format(Locale.US, it) }}],\n")
        append("  \"peak_times_sec\": [${peaks.joinToString(",") { "%.4f".format(Locale.US, it.tSec) }}]\n")
        append("}\n")
    }

    private fun num(v: Float?): String =
        if (v == null || v.isNaN() || v.isInfinite()) "null"
        else "%.3f".format(Locale.US, v)

    companion object {
        private const val TAG = "SessionRecorder"
    }
}
