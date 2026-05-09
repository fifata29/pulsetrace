package dk.nst.hrvmonitor.ppg

import android.graphics.ImageFormat
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * CameraX ImageAnalysis.Analyzer that extracts a single PPG sample per frame.
 *
 * Strategy: average the Y (luma) and V (chroma) of a centered ROI in the YUV_420_888 frame.
 * Red channel approximation: R = Y + 1.402 * (V - 128). With the torch on and a fingertip
 * pressed against the lens, R varies with capillary blood volume — that's the PPG waveform.
 *
 * The fraction of pixels above a brightness threshold is also reported as a coverage proxy
 * so the UI can flag bad finger placement.
 */
class PpgAnalyzer(
    private val onSample: (Sample) -> Unit
) : ImageAnalysis.Analyzer {

    data class Sample(
        val timestampNs: Long,
        val red: Float,        // mean red value in ROI (0..255)
        val luma: Float,       // mean luma in ROI (0..255)
        val coverage: Float    // fraction of ROI pixels brighter than COVERAGE_THRESHOLD
    )

    override fun analyze(image: ImageProxy) {
        try {
            if (image.format != ImageFormat.YUV_420_888) return
            if (image.planes.size < 3) return

            val width = image.width
            val height = image.height
            if (width < 32 || height < 32) return

            val roiSize = (minOf(width, height) * 0.4f).toInt().coerceIn(16, minOf(width, height))
            val roiSizeEven = (roiSize / 2) * 2
            val roiX = ((width - roiSizeEven) / 2).coerceAtLeast(0)
            val roiY = ((height - roiSizeEven) / 2).coerceAtLeast(0)

            val yPlane = image.planes[0]
            val vPlane = image.planes[2]

            val ySum = sumPlane(
                yPlane.buffer, yPlane.rowStride, yPlane.pixelStride,
                roiX, roiY, roiSizeEven, roiSizeEven
            )
            val vSum = sumPlane(
                vPlane.buffer, vPlane.rowStride, vPlane.pixelStride,
                roiX / 2, roiY / 2, roiSizeEven / 2, roiSizeEven / 2
            )

            val yPixels = ySum.count.toFloat()
            val vPixels = vSum.count.toFloat()
            if (yPixels <= 0f || vPixels <= 0f) return

            val meanY = ySum.sum / yPixels
            val meanV = vSum.sum / vPixels
            val red = (meanY + 1.402f * (meanV - 128f)).coerceIn(0f, 255f)
            val coverage = (ySum.brightCount / yPixels).coerceIn(0f, 1f)

            onSample(
                Sample(
                    timestampNs = System.nanoTime(),
                    red = red,
                    luma = meanY,
                    coverage = coverage
                )
            )
        } catch (t: Throwable) {
            Log.e(TAG, "frame analyze failed", t)
        } finally {
            try { image.close() } catch (_: Throwable) {}
        }
    }

    private data class PlaneSum(val sum: Long, val brightCount: Long, val count: Long)

    private fun sumPlane(
        buf: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        x: Int, y: Int, w: Int, h: Int
    ): PlaneSum {
        if (w <= 0 || h <= 0 || rowStride <= 0 || pixelStride <= 0) {
            return PlaneSum(0L, 0L, 0L)
        }
        val limit = buf.limit()
        var sum = 0L
        var bright = 0L
        var count = 0L
        for (row in 0 until h) {
            val base = (y + row) * rowStride + x * pixelStride
            for (col in 0 until w) {
                val idx = base + col * pixelStride
                if (idx < 0 || idx >= limit) continue
                val v = buf.get(idx).toInt() and 0xFF
                sum += v
                count++
                if (v > COVERAGE_THRESHOLD) bright++
            }
        }
        return PlaneSum(sum, bright, count)
    }

    companion object {
        private const val TAG = "PpgAnalyzer"
        private const val COVERAGE_THRESHOLD = 100
    }
}
