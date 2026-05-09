package dk.nst.hrvmonitor.data

import android.content.Context
import android.os.Build
import android.util.Log
import dk.nst.hrvmonitor.ppg.CalibrationAnalyzer
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
 * Streams [CalibrationAnalyzer.TileSample]s to a CSV with one row per frame and one column
 * per grid tile. The header includes device + camera config so the file is self-describing.
 *
 * Output: <externalFilesDir>/calibrations/<timestamp>/calibration-tiles.csv
 */
class CalibrationRecorder(private val appContext: Context) {

    data class Session(val dir: File, val csv: File, val startedAt: Long)

    private val flow = MutableSharedFlow<CalibrationAnalyzer.TileSample>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var writerJob: Job? = null
    private var current: Session? = null
    private var rowsWritten: Long = 0
    private var headerWritten = false

    fun start(
        gridCols: Int,
        gridRows: Int,
        targetDurationSec: Float,
        aeLocked: Boolean
    ): Session {
        finishWriterIfActive()

        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date())
        val root = File(appContext.getExternalFilesDir(null), "calibrations/$ts")
        root.mkdirs()
        val csv = File(root, "calibration-tiles.csv")
        val session = Session(root, csv, System.currentTimeMillis())
        current = session
        rowsWritten = 0
        headerWritten = false

        writerJob = ioScope.launch {
            BufferedWriter(FileWriter(csv)).use { w ->
                flow.collect { s ->
                    if (!headerWritten) {
                        w.write("# PulseTrace calibration v1\n")
                        w.write("# session_id: $ts\n")
                        w.write("# started_at: ${session.startedAt}\n")
                        w.write("# device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.SDK_INT})\n")
                        w.write("# frame_width: ${s.frameWidth}\n")
                        w.write("# frame_height: ${s.frameHeight}\n")
                        w.write("# grid_cols: $gridCols\n")
                        w.write("# grid_rows: $gridRows\n")
                        w.write("# tile_width_px: ${s.tileWidth}\n")
                        w.write("# tile_height_px: ${s.tileHeight}\n")
                        w.write("# target_duration_sec: ${"%.1f".format(Locale.US, targetDurationSec)}\n")
                        w.write("# torch: on\n")
                        w.write("# ae_locked: $aeLocked\n")
                        w.write("# awb_locked: $aeLocked\n")
                        w.write("# units: red channel mean per tile, 0..255 (R = Y + 1.402*(V-128))\n")
                        w.write("# tile naming: t_<row>_<col>, row 0 = top, col 0 = left\n")
                        val sb = StringBuilder("timestamp_ns")
                        for (row in 0 until gridRows) for (col in 0 until gridCols) {
                            sb.append(",t_").append(row).append("_").append(col)
                        }
                        sb.append("\n")
                        w.write(sb.toString())
                        headerWritten = true
                    }
                    val sb = StringBuilder()
                    sb.append(s.timestampNs)
                    val tiles = s.tilesR
                    for (i in tiles.indices) {
                        sb.append(',').append("%.2f".format(Locale.US, tiles[i]))
                    }
                    sb.append('\n')
                    w.write(sb.toString())
                    rowsWritten++
                    if (rowsWritten % 32L == 0L) w.flush()
                }
            }
        }
        Log.i(TAG, "Calibration session started at ${session.dir.absolutePath}")
        return session
    }

    fun appendSample(sample: CalibrationAnalyzer.TileSample) {
        if (current == null) return
        flow.tryEmit(sample)
    }

    fun stop(): Session? {
        val s = current ?: return null
        finishWriterIfActive()
        current = null
        Log.i(TAG, "Calibration session ended ($rowsWritten rows): ${s.csv.absolutePath}")
        return s
    }

    private fun finishWriterIfActive() {
        try { writerJob?.cancel() } catch (_: Throwable) {}
        writerJob = null
    }

    companion object {
        private const val TAG = "CalibrationRecorder"
    }
}
