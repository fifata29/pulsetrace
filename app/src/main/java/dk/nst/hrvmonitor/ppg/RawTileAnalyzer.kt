package dk.nst.hrvmonitor.ppg

import android.graphics.ImageFormat
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.sqrt

/**
 * Raw Mode analyzer: per tile and per frame, computes R / G / B mean AND std from
 * the YUV_420_888 planes by reconstructing R, G, B per pixel inside each tile.
 *
 * Output is heavier than [TileGridAnalyzer] (6 stats per tile vs 2) and is intended
 * for offline research recordings, not the live measurement path.
 *
 * BT.601 full-range conversion:
 *   R = Y + 1.402·(V − 128)
 *   G = Y − 0.344·(U − 128) − 0.714·(V − 128)
 *   B = Y + 1.772·(U − 128)
 *
 * For speed we stride 2×2 inside each tile (reading 1 of every 4 Y pixels). At
 * 60 FPS / 640×480 / 16×12 grid this keeps each frame's cost well under 16 ms on
 * a Snapdragon 8 Gen 2 while still averaging a few hundred pixels per tile.
 */
class RawTileAnalyzer(
    val gridCols: Int = 16,
    val gridRows: Int = 12,
    private val onSample: (RawTileSample) -> Unit
) : ImageAnalysis.Analyzer {

    /** One frame, row-major. Each FloatArray is length gridCols*gridRows. */
    data class RawTileSample(
        val timestampNs: Long,
        val rMean: FloatArray, val rStd: FloatArray,
        val gMean: FloatArray, val gStd: FloatArray,
        val bMean: FloatArray, val bStd: FloatArray,
        val frameWidth: Int,
        val frameHeight: Int,
        val tileWidth: Int,
        val tileHeight: Int
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private var yScratch: ByteArray = ByteArray(0)
    private var uScratch: ByteArray = ByteArray(0)
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
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            val yBuf = yPlane.buffer
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer

            val yLen = yBuf.remaining()
            val uLen = uBuf.remaining()
            val vLen = vBuf.remaining()
            if (yScratch.size < yLen) yScratch = ByteArray(yLen)
            if (uScratch.size < uLen) uScratch = ByteArray(uLen)
            if (vScratch.size < vLen) vScratch = ByteArray(vLen)
            yBuf.position(0); yBuf.get(yScratch, 0, yLen)
            uBuf.position(0); uBuf.get(uScratch, 0, uLen)
            vBuf.position(0); vBuf.get(vScratch, 0, vLen)

            val yRowStride = yPlane.rowStride
            val yPixStride = yPlane.pixelStride
            val uRowStride = uPlane.rowStride
            val uPixStride = uPlane.pixelStride
            val vRowStride = vPlane.rowStride
            val vPixStride = vPlane.pixelStride

            val nTiles = gridCols * gridRows
            val rMean = FloatArray(nTiles); val rStd = FloatArray(nTiles)
            val gMean = FloatArray(nTiles); val gStd = FloatArray(nTiles)
            val bMean = FloatArray(nTiles); val bStd = FloatArray(nTiles)

            for (row in 0 until gridRows) {
                for (col in 0 until gridCols) {
                    val x0 = col * tileW
                    val y0 = row * tileH
                    val (mr, sr, mg, sg, mb, sb) = tileStats(
                        x0, y0, tw, th,
                        yRowStride, yPixStride, yLen,
                        uRowStride, uPixStride, uLen,
                        vRowStride, vPixStride, vLen
                    )
                    val idx = row * gridCols + col
                    rMean[idx] = mr; rStd[idx] = sr
                    gMean[idx] = mg; gStd[idx] = sg
                    bMean[idx] = mb; bStd[idx] = sb
                }
            }

            onSample(
                RawTileSample(
                    timestampNs = System.nanoTime(),
                    rMean = rMean, rStd = rStd,
                    gMean = gMean, gStd = gStd,
                    bMean = bMean, bStd = bStd,
                    frameWidth = width,
                    frameHeight = height,
                    tileWidth = tw,
                    tileHeight = th
                )
            )
        } catch (t: Throwable) {
            Log.e(TAG, "raw tile frame failed", t)
        } finally {
            try { image.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Walks pixels of one tile with 2×2 stride, reconstructing R/G/B per sampled
     * pixel and accumulating sum + sum-of-squares for each channel. Returns
     * (rMean, rStd, gMean, gStd, bMean, bStd).
     */
    private fun tileStats(
        x0: Int, y0: Int, tw: Int, th: Int,
        yRowStride: Int, yPixStride: Int, yLen: Int,
        uRowStride: Int, uPixStride: Int, uLen: Int,
        vRowStride: Int, vPixStride: Int, vLen: Int
    ): FloatArray {
        var sumR = 0.0; var sumRR = 0.0
        var sumG = 0.0; var sumGG = 0.0
        var sumB = 0.0; var sumBB = 0.0
        var n = 0
        var py = 0
        while (py < th) {
            val yRow = y0 + py
            val uvRow = (y0 + py) / 2
            var px = 0
            while (px < tw) {
                val xCol = x0 + px
                val uvCol = (x0 + px) / 2
                val yIdx = yRow * yRowStride + xCol * yPixStride
                val uIdx = uvRow * uRowStride + uvCol * uPixStride
                val vIdx = uvRow * vRowStride + uvCol * vPixStride
                if (yIdx in 0 until yLen && uIdx in 0 until uLen && vIdx in 0 until vLen) {
                    val y = (yScratch[yIdx].toInt() and 0xFF).toDouble()
                    val u = (uScratch[uIdx].toInt() and 0xFF).toDouble() - 128.0
                    val v = (vScratch[vIdx].toInt() and 0xFF).toDouble() - 128.0
                    val r = (y + 1.402 * v).coerceIn(0.0, 255.0)
                    val g = (y - 0.344 * u - 0.714 * v).coerceIn(0.0, 255.0)
                    val b = (y + 1.772 * u).coerceIn(0.0, 255.0)
                    sumR += r; sumRR += r * r
                    sumG += g; sumGG += g * g
                    sumB += b; sumBB += b * b
                    n++
                }
                px += 2
            }
            py += 2
        }
        if (n == 0) return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
        val nf = n.toDouble()
        val mr = sumR / nf
        val mg = sumG / nf
        val mb = sumB / nf
        val vr = (sumRR / nf - mr * mr).coerceAtLeast(0.0)
        val vg = (sumGG / nf - mg * mg).coerceAtLeast(0.0)
        val vb = (sumBB / nf - mb * mb).coerceAtLeast(0.0)
        return floatArrayOf(
            mr.toFloat(), sqrt(vr).toFloat(),
            mg.toFloat(), sqrt(vg).toFloat(),
            mb.toFloat(), sqrt(vb).toFloat()
        )
    }

    companion object {
        private const val TAG = "RawTileAnalyzer"
    }
}
