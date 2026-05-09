package dk.nst.hrvmonitor.ppg

import android.graphics.ImageFormat
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Frame analyzer that splits the frame into a [gridCols] × [gridRows] grid and emits both
 * mean red (R = Y + 1.402·(V−128)) AND mean luma per tile. Used by:
 *   - calibration mode (records full grid for offline analysis)
 *   - measurement mode phase 1 (per-tile pulse search to pick the optimal ROI)
 *   - measurement mode phase 2 (averaging the chosen tiles to feed the SignalProcessor)
 */
class TileGridAnalyzer(
    val gridCols: Int = 16,
    val gridRows: Int = 12,
    private val onSample: (TileSample) -> Unit
) : ImageAnalysis.Analyzer {

    /**
     * One frame's tile grid. [tilesR] and [tilesY] are row-major:
     * index = row * gridCols + col, length = gridCols * gridRows.
     */
    data class TileSample(
        val timestampNs: Long,
        val tilesR: FloatArray,
        val tilesY: FloatArray,
        val frameWidth: Int,
        val frameHeight: Int,
        val tileWidth: Int,
        val tileHeight: Int
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    // Reusable byte buffers — direct ByteBuffer.get(idx) is a JNI call per byte;
    // bulk-copying the plane into a Java byte[] once per frame is ~10× faster.
    private var yScratch: ByteArray = ByteArray(0)
    private var vScratch: ByteArray = ByteArray(0)

    override fun analyze(image: ImageProxy) {
        try {
            if (image.format != ImageFormat.YUV_420_888 || image.planes.size < 3) return
            val width = image.width
            val height = image.height
            val tileW = width / gridCols
            val tileH = height / gridRows
            if (tileW < 4 || tileH < 4) return
            val tw = (tileW / 2) * 2
            val th = (tileH / 2) * 2
            if (tw < 4 || th < 4) return

            val yPlane = image.planes[0]
            val vPlane = image.planes[2]
            val yBuf = yPlane.buffer
            val vBuf = vPlane.buffer

            val yLen = yBuf.remaining()
            val vLen = vBuf.remaining()
            if (yScratch.size < yLen) yScratch = ByteArray(yLen)
            if (vScratch.size < vLen) vScratch = ByteArray(vLen)
            yBuf.position(0); yBuf.get(yScratch, 0, yLen)
            vBuf.position(0); vBuf.get(vScratch, 0, vLen)

            val outR = FloatArray(gridCols * gridRows)
            val outY = FloatArray(gridCols * gridRows)
            val yPixels = (tw * th).toFloat()
            val vPixels = ((tw / 2) * (th / 2)).toFloat().coerceAtLeast(1f)

            for (row in 0 until gridRows) {
                for (col in 0 until gridCols) {
                    val x = col * tileW
                    val y = row * tileH
                    val ySum = sumArray(yScratch, yLen, yPlane.rowStride, yPlane.pixelStride, x, y, tw, th)
                    val vSum = sumArray(vScratch, vLen, vPlane.rowStride, vPlane.pixelStride, x / 2, y / 2, tw / 2, th / 2)
                    val meanY = if (yPixels > 0f) ySum / yPixels else 0f
                    val meanV = vSum / vPixels
                    val red = (meanY + 1.402f * (meanV - 128f)).coerceIn(0f, 255f)
                    val idx = row * gridCols + col
                    outR[idx] = red
                    outY[idx] = meanY
                }
            }

            onSample(
                TileSample(
                    timestampNs = System.nanoTime(),
                    tilesR = outR,
                    tilesY = outY,
                    frameWidth = width,
                    frameHeight = height,
                    tileWidth = tw,
                    tileHeight = th
                )
            )
        } catch (t: Throwable) {
            Log.e(TAG, "tile-grid frame failed", t)
        } finally {
            try { image.close() } catch (_: Throwable) {}
        }
    }

    private fun sumArray(
        arr: ByteArray, len: Int,
        rowStride: Int, pixelStride: Int,
        x: Int, y: Int, w: Int, h: Int
    ): Float {
        if (w <= 0 || h <= 0 || rowStride <= 0 || pixelStride <= 0) return 0f
        var sum = 0L
        if (pixelStride == 1) {
            for (row in 0 until h) {
                val base = (y + row) * rowStride + x
                val end = base + w
                if (base < 0 || end > len) continue
                for (i in base until end) sum += arr[i].toInt() and 0xFF
            }
        } else {
            for (row in 0 until h) {
                val base = (y + row) * rowStride + x * pixelStride
                for (col in 0 until w) {
                    val idx = base + col * pixelStride
                    if (idx < 0 || idx >= len) continue
                    sum += arr[idx].toInt() and 0xFF
                }
            }
        }
        return sum.toFloat()
    }

    companion object {
        private const val TAG = "TileGridAnalyzer"
    }
}
