package dk.nst.hrvmonitor.data

import android.content.Context
import android.os.Build
import android.util.Log
import dk.nst.hrvmonitor.ppg.RawTileAnalyzer
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
 * Streams [RawTileAnalyzer.RawTileSample]s to a CSV with one row per frame and
 * 6 columns per grid tile (R/G/B mean + R/G/B std). The header is self-describing
 * so the analysis scripts can read any session without hard-coded assumptions.
 *
 * Layout:
 *   timestamp_ns, frame_idx,
 *   t0_0_R, t0_0_G, t0_0_B, t0_0_Rs, t0_0_Gs, t0_0_Bs,
 *   t0_1_R, t0_1_G, t0_1_B, t0_1_Rs, t0_1_Gs, t0_1_Bs, ...
 *
 * Means are written with 2 decimals, stds with 2 decimals — at 60 FPS / 240 s
 * / 16×12 grid this lands around 70-90 MB per session.
 *
 * Output: <externalFilesDir>/raw_sessions/<timestamp>/raw-tiles.csv
 */
class RawRecorder(private val appContext: Context) {

    data class Session(val dir: File, val csv: File, val metaCsv: File, val startedAt: Long)

    /** Per-frame camera metadata captured from Camera2 CaptureResult — the
     *  authoritative record of what the camera was actually doing (ISO,
     *  exposure time, AE/AWB state, AWB colour gains) at each frame. Used
     *  to verify whether the camera is changing settings mid-recording. */
    data class CameraMetadata(
        val timestampNs: Long,
        val sensorTimestampNs: Long,
        val isoSensitivity: Int,
        val exposureTimeNs: Long,
        val frameDurationNs: Long,
        val aeState: Int,
        val awbState: Int,
        val rGain: Float,
        val gEvenGain: Float,
        val gOddGain: Float,
        val bGain: Float
    )

    private val flow = MutableSharedFlow<RawTileAnalyzer.RawTileSample>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val metaFlow = MutableSharedFlow<CameraMetadata>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var writerJob: Job? = null
    private var metaWriterJob: Job? = null
    private var current: Session? = null
    private var rowsWritten: Long = 0
    private var metaRowsWritten: Long = 0
    private var headerWritten = false
    private var metaHeaderWritten = false
    private var frameIdx: Long = 0

    fun start(
        gridCols: Int,
        gridRows: Int,
        targetFps: Int,
        targetDurationSec: Float,
        site: String,
        notes: String?,
        cameraMode: String = "auto"
    ): Session {
        finishWriterIfActive()

        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date())
        val root = File(appContext.getExternalFilesDir(null), "raw_sessions/$ts")
        root.mkdirs()
        val csv = File(root, "raw-tiles.csv")
        val metaCsv = File(root, "camera_metadata.csv")
        val session = Session(root, csv, metaCsv, System.currentTimeMillis())
        current = session
        rowsWritten = 0
        metaRowsWritten = 0
        frameIdx = 0
        headerWritten = false
        metaHeaderWritten = false

        writerJob = ioScope.launch {
            BufferedWriter(FileWriter(csv)).use { w ->
                flow.collect { s ->
                    if (!headerWritten) {
                        w.write("# PulseTrace raw v1\n")
                        w.write("# session_id: $ts\n")
                        w.write("# started_at: ${session.startedAt}\n")
                        w.write("# device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.SDK_INT})\n")
                        w.write("# frame_width: ${s.frameWidth}\n")
                        w.write("# frame_height: ${s.frameHeight}\n")
                        w.write("# grid_cols: $gridCols\n")
                        w.write("# grid_rows: $gridRows\n")
                        w.write("# tile_width_px: ${s.tileWidth}\n")
                        w.write("# tile_height_px: ${s.tileHeight}\n")
                        w.write("# target_fps: $targetFps\n")
                        w.write("# target_duration_sec: ${"%.1f".format(Locale.US, targetDurationSec)}\n")
                        w.write("# site: $site\n")
                        w.write("# camera_mode: $cameraMode\n")
                        if (!notes.isNullOrBlank()) w.write("# notes: $notes\n")
                        w.write("# torch: on\n")
                        w.write("# stride: 2x2 sub-sampled R/G/B reconstruction (BT.601 full-range)\n")
                        w.write("# units: 0..255 per channel; std is sqrt of in-tile pixel variance\n")
                        w.write("# tile naming: t<row>_<col>_(R|G|B|Rs|Gs|Bs); row 0 = top, col 0 = left\n")
                        val sb = StringBuilder("timestamp_ns,frame_idx")
                        for (row in 0 until gridRows) {
                            for (col in 0 until gridCols) {
                                val p = "t${row}_$col"
                                sb.append(",${p}_R,${p}_G,${p}_B,${p}_Rs,${p}_Gs,${p}_Bs")
                            }
                        }
                        sb.append("\n")
                        w.write(sb.toString())
                        headerWritten = true
                    }
                    val sb = StringBuilder()
                    sb.append(s.timestampNs).append(',').append(frameIdx)
                    val n = s.rMean.size
                    for (i in 0 until n) {
                        sb.append(',').append("%.2f".format(Locale.US, s.rMean[i]))
                        sb.append(',').append("%.2f".format(Locale.US, s.gMean[i]))
                        sb.append(',').append("%.2f".format(Locale.US, s.bMean[i]))
                        sb.append(',').append("%.2f".format(Locale.US, s.rStd[i]))
                        sb.append(',').append("%.2f".format(Locale.US, s.gStd[i]))
                        sb.append(',').append("%.2f".format(Locale.US, s.bStd[i]))
                    }
                    sb.append('\n')
                    w.write(sb.toString())
                    rowsWritten++
                    frameIdx++
                    if (rowsWritten % 32L == 0L) w.flush()
                }
            }
        }
        // Parallel writer for camera metadata. Streams CaptureResult-derived
        // per-frame entries into camera_metadata.csv alongside raw-tiles.csv.
        metaWriterJob = ioScope.launch {
            BufferedWriter(FileWriter(session.metaCsv)).use { w ->
                metaFlow.collect { m ->
                    if (!metaHeaderWritten) {
                        w.write("# PulseTrace camera-metadata v1\n")
                        w.write("# session_id: $ts\n")
                        w.write("# started_at: ${session.startedAt}\n")
                        w.write("# camera_mode: $cameraMode\n")
                        w.write("# site: $site\n")
                        w.write("# ae_state: 0=INACTIVE 1=SEARCHING 2=CONVERGED 3=LOCKED 4=FLASH_REQUIRED 5=PRECAPTURE\n")
                        w.write("# awb_state: 0=INACTIVE 1=SEARCHING 2=CONVERGED 3=LOCKED\n")
                        w.write("# exposure_time_ns, frame_duration_ns: nanoseconds\n")
                        w.write("# rGain..bGain: AWB colour gains in RggbChannelVector order\n")
                        w.write("timestamp_ns,sensor_timestamp_ns,iso,exposure_ns,frame_duration_ns,ae_state,awb_state,r_gain,g_even_gain,g_odd_gain,b_gain\n")
                        metaHeaderWritten = true
                    }
                    val sb = StringBuilder()
                    sb.append(m.timestampNs).append(',')
                        .append(m.sensorTimestampNs).append(',')
                        .append(m.isoSensitivity).append(',')
                        .append(m.exposureTimeNs).append(',')
                        .append(m.frameDurationNs).append(',')
                        .append(m.aeState).append(',')
                        .append(m.awbState).append(',')
                        .append("%.4f".format(Locale.US, m.rGain)).append(',')
                        .append("%.4f".format(Locale.US, m.gEvenGain)).append(',')
                        .append("%.4f".format(Locale.US, m.gOddGain)).append(',')
                        .append("%.4f".format(Locale.US, m.bGain))
                        .append('\n')
                    w.write(sb.toString())
                    metaRowsWritten++
                    if (metaRowsWritten % 32L == 0L) w.flush()
                }
            }
        }

        Log.i(TAG, "Raw session started at ${session.dir.absolutePath}")
        return session
    }

    fun appendSample(sample: RawTileAnalyzer.RawTileSample) {
        if (current == null) return
        flow.tryEmit(sample)
    }

    fun appendCameraMetadata(meta: CameraMetadata) {
        if (current == null) return
        metaFlow.tryEmit(meta)
    }

    fun stop(): Session? {
        val s = current ?: return null
        finishWriterIfActive()
        current = null
        Log.i(TAG, "Raw session ended ($rowsWritten rows, $metaRowsWritten meta rows): ${s.csv.absolutePath}")
        return s
    }

    val rowsWrittenSnapshot: Long get() = rowsWritten

    private fun finishWriterIfActive() {
        try { writerJob?.cancel() } catch (_: Throwable) {}
        try { metaWriterJob?.cancel() } catch (_: Throwable) {}
        writerJob = null
        metaWriterJob = null
    }

    companion object {
        private const val TAG = "RawRecorder"
    }
}
