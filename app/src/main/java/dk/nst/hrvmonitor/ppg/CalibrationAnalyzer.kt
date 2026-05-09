package dk.nst.hrvmonitor.ppg

import android.graphics.ImageFormat
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Frame analyzer for calibration. Splits the frame into a [gridCols] × [gridRows] grid
 * and emits the mean red value per tile (R = Y + 1.402·(V−128)) on every frame.
 *
 * The output is exactly 192 floats per frame at the default 16×12 grid, ~5800 numbers/s,
 * ~3 MB across a 2-minute recording — small enough to share for offline analysis.
 *
 * Pulse signal location varies by phone: on devices like the OnePlus 11, the centered
 * region saturates and loses modulation while the periphery still pulses. The tile grid
 * lets us identify the highest-SNR region post-hoc.
 */
class CalibrationAnalyzer(
    val gridCols: Int = 16,
    val gridRows: Int = 12,
    private val onSample: (TileSample) -> Unit
) : ImageAnalysis.Analyzer {

    /** One frame's tile grid. [tilesR] is row-major: index = row * gridCols + col, length = gridCols * gridRows. */
    data class TileSample(
        val timestampNs: Long,
        val tilesR: FloatArray,
        val frameWidth: Int,
        val frameHeight: Int,
        val tileWidth: Int,
        val tileHeight: Int,
        val centerLuma: Float
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (image.format != ImageFormat.YUV_420_888 || image.planes.size < 3) return
            val width = image.width
            val height = image.height
            val tileW = width / gridCols
            val tileH = height / gridRows
            if (tileW < 4 || tileH < 4) return
            // Make tile dims even so chroma indexing is clean.
            val tw = (tileW / 2) * 2
            val th = (tileH / 2) * 2
            if (tw < 4 || th < 4) return

            val yPlane = image.planes[0]
            val vPlane = image.planes[2]
            val out = FloatArray(gridCols * gridRows)

            for (row in 0 until gridRows) {
                for (col in 0 until gridCols) {
                    val x = col * tileW
                    val y = row * tileH
                    val ySum = sumPlane(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, x, y, tw, th)
                    val vSum = sumPlane(vPlane.buffer, vPlane.rowStride, vPlane.pixelStride, x / 2, y / 2, tw / 2, th / 2)
                    val yPixels = (tw * th).toFloat()
                    val vPixels = ((tw / 2) * (th / 2)).toFloat().coerceAtLeast(1f)
                    val meanY = if (yPixels > 0) ySum / yPixels else 0f
                    val meanV = vSum / vPixels
                    val red = (meanY + 1.402f * (meanV - 128f)).coerceIn(0f, 255f)
                    out[row * gridCols + col] = red
                }
            }

            // Luma in the centered tile, used as a coverage proxy for the UI.
            val cRow = gridRows / 2
            val cCol = gridCols / 2
            val cx = cCol * tileW
            val cy = cRow * tileH
            val cYSum = sumPlane(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, cx, cy, tw, th)
            val centerLuma = cYSum / (tw * th).toFloat()

            onSample(
                TileSample(
                    timestampNs = System.nanoTime(),
                    tilesR = out,
                    frameWidth = width,
                    frameHeight = height,
                    tileWidth = tw,
                    tileHeight = th,
                    centerLuma = centerLuma
                )
            )
        } catch (t: Throwable) {
            Log.e(TAG, "calibration frame failed", t)
        } finally {
            try { image.close() } catch (_: Throwable) {}
        }
    }

    private fun sumPlane(
        buf: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        x: Int, y: Int, w: Int, h: Int
    ): Float {
        if (w <= 0 || h <= 0 || rowStride <= 0 || pixelStride <= 0) return 0f
        val limit = buf.limit()
        var sum = 0L
        for (row in 0 until h) {
            val base = (y + row) * rowStride + x * pixelStride
            for (col in 0 until w) {
                val idx = base + col * pixelStride
                if (idx < 0 || idx >= limit) continue
                sum += buf.get(idx).toInt() and 0xFF
            }
        }
        return sum.toFloat()
    }

    companion object {
        private const val TAG = "CalibrationAnalyzer"
    }
}
