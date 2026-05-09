package dk.nst.hrvmonitor.ppg

import android.graphics.ImageFormat
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

            val width = image.width
            val height = image.height

            // Centered square ROI at 40% of the smaller dimension
            val roiSize = (minOf(width, height) * 0.4f).toInt().coerceAtLeast(32)
            val roiX = (width - roiSize) / 2
            val roiY = (height - roiSize) / 2

            val yPlane = image.planes[0]
            val vPlane = image.planes[2]

            val ySum = sumPlane(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride,
                roiX, roiY, roiSize, roiSize)

            // U/V planes are subsampled 2:1 in both dims for YUV_420
            val vSum = sumPlane(vPlane.buffer, vPlane.rowStride, vPlane.pixelStride,
                roiX / 2, roiY / 2, roiSize / 2, roiSize / 2)

            val pixels = (roiSize * roiSize).toFloat()
            val subPixels = ((roiSize / 2) * (roiSize / 2)).toFloat()

            val meanY = ySum.sum / pixels
            val meanV = vSum.sum / subPixels
            val red = (meanY + 1.402f * (meanV - 128f)).coerceIn(0f, 255f)
            val coverage = ySum.brightCount / pixels

            onSample(
                Sample(
                    timestampNs = System.nanoTime(),
                    red = red,
                    luma = meanY,
                    coverage = coverage
                )
            )
        } finally {
            image.close()
        }
    }

    private data class PlaneSum(val sum: Long, val brightCount: Long)

    private fun sumPlane(
        buf: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        x: Int, y: Int, w: Int, h: Int
    ): PlaneSum {
        var sum = 0L
        var bright = 0L
        for (row in 0 until h) {
            val base = (y + row) * rowStride + x * pixelStride
            for (col in 0 until w) {
                val v = buf.get(base + col * pixelStride).toInt() and 0xFF
                sum += v
                if (v > COVERAGE_THRESHOLD) bright++
            }
        }
        return PlaneSum(sum, bright)
    }

    companion object {
        // With torch + fingertip the luma is high and saturated red. Pixels below this
        // threshold are likely "no finger" / leaking ambient light.
        private const val COVERAGE_THRESHOLD = 100
    }
}
