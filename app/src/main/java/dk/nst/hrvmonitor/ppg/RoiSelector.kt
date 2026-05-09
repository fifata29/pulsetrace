package dk.nst.hrvmonitor.ppg

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Picks the optimal ROI from ~10 s of tile-grid samples.
 *
 * Pipeline:
 *  1. Resample each tile's red-channel time series to a uniform grid at the estimated fs.
 *  2. Bandpass each tile in the heart-rate band ([lowHz]–[highHz], zero-phase Butterworth).
 *  3. Per tile: AC RMS (band power), saturation flag, mean correlation with up-to-4 neighbors.
 *  4. Score = AC_rms × clamp(neighbor_correlation), with saturated tiles set to 0.
 *  5. Take the top [topK] tiles, return their bounding box.
 *
 * The neighbor-correlation term enforces spatial coherence — random noise scoring high in
 * a single tile won't survive because its neighbors won't agree with it.
 */
object RoiSelector {

    data class Result(
        val tileIndices: IntArray,         // indices into row-major grid (size = topK at most)
        val bboxRowStart: Int,
        val bboxRowEnd: Int,                // inclusive
        val bboxColStart: Int,
        val bboxColEnd: Int,                // inclusive
        val bestScore: Float,
        val medianFreqHz: Float,
        val sampleRateHz: Float,
        val acceptable: Boolean             // false if best score is too weak
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /**
     * @param tilesPerFrame  list of TileSamples collected during phase 1, oldest first.
     *                       All samples must have the same gridCols/gridRows.
     */
    fun select(
        tilesPerFrame: List<TileGridAnalyzer.TileSample>,
        gridCols: Int,
        gridRows: Int,
        topK: Int = 6,
        lowHz: Float = 0.7f,
        highHz: Float = 4.0f,
        minScore: Float = 0.05f
    ): Result {
        val n = tilesPerFrame.size
        val nTiles = gridCols * gridRows
        if (n < 16) {
            return centeredFallback(gridCols, gridRows, topK, sampleRateHz = 0f)
        }

        val t0 = tilesPerFrame.first().timestampNs
        val tEnd = tilesPerFrame.last().timestampNs
        val durSec = ((tEnd - t0) / 1e9).toFloat()
        val fs = if (durSec > 0f) (n - 1) / durSec else 30f
        if (fs < 5f) return centeredFallback(gridCols, gridRows, topK, fs)

        // Build per-tile time series, then linearly resample onto a uniform grid.
        val uniformN = n
        val tSec = FloatArray(n) { ((tilesPerFrame[it].timestampNs - t0) / 1e9).toFloat() }
        val uniformDt = if (durSec > 0f) durSec / (uniformN - 1) else 1f / fs
        val uniformGrid = Array(nTiles) { FloatArray(uniformN) }
        for (tile in 0 until nTiles) {
            // Pull the per-frame value for this tile.
            val series = FloatArray(n) { tilesPerFrame[it].tilesR[tile] }
            var idx = 0
            for (i in 0 until uniformN) {
                val t = i * uniformDt
                while (idx < n - 2 && tSec[idx + 1] < t) idx++
                val t1 = tSec[idx]; val t2 = tSec[idx + 1]
                val a = if (t2 > t1) ((t - t1) / (t2 - t1)).coerceIn(0f, 1f) else 0f
                uniformGrid[tile][i] = series[idx] * (1f - a) + series[idx + 1] * a
            }
        }

        // Per-tile DC, max, saturation flag.
        val dc = FloatArray(nTiles)
        val maxV = FloatArray(nTiles) { Float.NEGATIVE_INFINITY }
        for (tile in 0 until nTiles) {
            var s = 0.0
            var mx = Float.NEGATIVE_INFINITY
            for (v in uniformGrid[tile]) {
                s += v
                if (v > mx) mx = v
            }
            dc[tile] = (s / uniformN).toFloat()
            maxV[tile] = mx
        }
        val saturated = BooleanArray(nTiles) { maxV[it] >= 254.0f }

        // Bandpass each tile (zero-phase, 2 biquad sections).
        val cutLow = lowHz.coerceAtLeast(0.05f)
        val cutHigh = highHz.coerceAtMost(fs * 0.45f)
        val bp = if (cutHigh <= cutLow) uniformGrid else {
            val (bHp, aHp) = biquadHighpass(cutLow, fs)
            val (bLp, aLp) = biquadLowpass(cutHigh, fs)
            Array(nTiles) { tile ->
                val s1 = filterBidirectional(uniformGrid[tile], bHp, aHp)
                filterBidirectional(s1, bLp, aLp)
            }
        }

        // AC RMS in band.
        val acRms = FloatArray(nTiles)
        for (tile in 0 until nTiles) {
            var sumSq = 0.0
            for (v in bp[tile]) sumSq += (v.toDouble() * v.toDouble())
            acRms[tile] = sqrt(sumSq / uniformN).toFloat()
        }

        // Per-tile correlation with up-to-4 neighbors (Pearson on bandpassed series).
        val neighborCorr = FloatArray(nTiles)
        for (row in 0 until gridRows) {
            for (col in 0 until gridCols) {
                val idx = row * gridCols + col
                if (saturated[idx]) { neighborCorr[idx] = 0f; continue }
                var sum = 0f
                var count = 0
                for ((dr, dc2) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                    val nr = row + dr; val nc = col + dc2
                    if (nr !in 0 until gridRows || nc !in 0 until gridCols) continue
                    val nidx = nr * gridCols + nc
                    if (saturated[nidx]) continue
                    sum += pearson(bp[idx], bp[nidx])
                    count++
                }
                neighborCorr[idx] = if (count > 0) sum / count else 0f
            }
        }

        val score = FloatArray(nTiles) { i ->
            if (saturated[i]) 0f
            else acRms[i] * neighborCorr[i].coerceAtLeast(0f)
        }

        // Compute a consensus dominant frequency by AC-weighted histogram of per-tile peaks.
        val freqStep = 0.05f
        val freqBins = ((highHz - lowHz) / freqStep).toInt().coerceAtLeast(1)
        val histWeights = FloatArray(freqBins)
        for (tile in 0 until nTiles) {
            if (saturated[tile]) continue
            val peakF = dominantFreqHz(bp[tile], fs, lowHz, highHz)
            val bin = (((peakF - lowHz) / freqStep).toInt()).coerceIn(0, freqBins - 1)
            histWeights[bin] += acRms[tile]
        }
        val medianBin = histWeights.indices.maxByOrNull { histWeights[it] } ?: 0
        val medianFreq = lowHz + (medianBin + 0.5f) * freqStep

        // Pick top-K by score.
        val ordered = (0 until nTiles).sortedByDescending { score[it] }
        val picked = mutableListOf<Int>()
        for (i in ordered) {
            if (score[i] <= 0f) break
            picked += i
            if (picked.size >= topK) break
        }

        if (picked.isEmpty() || score[picked[0]] < minScore) {
            return centeredFallback(gridCols, gridRows, topK, fs).copy(
                acceptable = false,
                medianFreqHz = medianFreq,
                sampleRateHz = fs
            )
        }

        var rMin = Int.MAX_VALUE; var rMax = Int.MIN_VALUE
        var cMin = Int.MAX_VALUE; var cMax = Int.MIN_VALUE
        for (idx in picked) {
            val r = idx / gridCols; val c = idx % gridCols
            if (r < rMin) rMin = r; if (r > rMax) rMax = r
            if (c < cMin) cMin = c; if (c > cMax) cMax = c
        }

        // Expand to full bounding box (include any tiles inside the box, even if not in top-K).
        val bboxIndices = ArrayList<Int>()
        for (r in rMin..rMax) for (c in cMin..cMax) {
            val i = r * gridCols + c
            if (!saturated[i]) bboxIndices += i
        }

        return Result(
            tileIndices = bboxIndices.toIntArray(),
            bboxRowStart = rMin,
            bboxRowEnd = rMax,
            bboxColStart = cMin,
            bboxColEnd = cMax,
            bestScore = score[picked[0]],
            medianFreqHz = medianFreq,
            sampleRateHz = fs,
            acceptable = true
        )
    }

    private fun centeredFallback(cols: Int, rows: Int, topK: Int, sampleRateHz: Float): Result {
        // 4×3 cluster centered in the frame.
        val rMin = (rows / 2 - 1).coerceAtLeast(0)
        val rMax = (rows / 2).coerceAtMost(rows - 1)
        val cMin = (cols / 2 - 2).coerceAtLeast(0)
        val cMax = (cols / 2 + 1).coerceAtMost(cols - 1)
        val list = ArrayList<Int>()
        for (r in rMin..rMax) for (c in cMin..cMax) list += r * cols + c
        return Result(
            tileIndices = list.toIntArray().copyOf(topK.coerceAtMost(list.size)),
            bboxRowStart = rMin,
            bboxRowEnd = rMax,
            bboxColStart = cMin,
            bboxColEnd = cMax,
            bestScore = 0f,
            medianFreqHz = 1f,
            sampleRateHz = sampleRateHz,
            acceptable = false
        )
    }

    private fun pearson(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        if (n < 2) return 0f
        var ma = 0.0; var mb = 0.0
        for (i in 0 until n) { ma += a[i]; mb += b[i] }
        ma /= n; mb /= n
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in 0 until n) {
            val xa = a[i] - ma; val xb = b[i] - mb
            num += xa * xb
            da += xa * xa
            db += xb * xb
        }
        val denom = sqrt(da * db)
        return if (denom > 1e-9) (num / denom).toFloat() else 0f
    }

    /** DFT-style scan inside [lowHz, highHz] at 0.05 Hz steps; returns the peak frequency. */
    private fun dominantFreqHz(x: FloatArray, fs: Float, lowHz: Float, highHz: Float): Float {
        val n = x.size
        var bestF = lowHz; var bestPow = -1.0
        var f = lowHz
        while (f <= highHz) {
            val w = 2 * PI * f / fs
            var sumC = 0.0; var sumS = 0.0
            for (i in 0 until n) {
                val v = x[i].toDouble()
                sumC += v * cos(w * i)
                sumS += v * sin(w * i)
            }
            val pow = sumC * sumC + sumS * sumS
            if (pow > bestPow) { bestPow = pow; bestF = f }
            f += 0.05f
        }
        return bestF
    }

    private fun biquadHighpass(fc: Float, fs: Float): Pair<FloatArray, FloatArray> {
        val w0 = 2 * PI.toFloat() * fc / fs
        val cw = cos(w0); val sw = sin(w0); val q = 0.7071f; val alpha = sw / (2 * q)
        val a0 = 1 + alpha
        return floatArrayOf((1 + cw) / 2 / a0, -(1 + cw) / a0, (1 + cw) / 2 / a0) to
            floatArrayOf(1f, -2 * cw / a0, (1 - alpha) / a0)
    }

    private fun biquadLowpass(fc: Float, fs: Float): Pair<FloatArray, FloatArray> {
        val w0 = 2 * PI.toFloat() * fc / fs
        val cw = cos(w0); val sw = sin(w0); val q = 0.7071f; val alpha = sw / (2 * q)
        val a0 = 1 + alpha
        return floatArrayOf((1 - cw) / 2 / a0, (1 - cw) / a0, (1 - cw) / 2 / a0) to
            floatArrayOf(1f, -2 * cw / a0, (1 - alpha) / a0)
    }

    private fun filterBidirectional(x: FloatArray, b: FloatArray, a: FloatArray): FloatArray {
        val fwd = filterDirect(x, b, a)
        val rev = fwd.reversedArray()
        return filterDirect(rev, b, a).reversedArray()
    }

    private fun filterDirect(x: FloatArray, b: FloatArray, a: FloatArray): FloatArray {
        val y = FloatArray(x.size)
        for (n in x.indices) {
            var acc = b[0] * x[n]
            if (n >= 1) acc += b[1] * x[n - 1] - a[1] * y[n - 1]
            if (n >= 2) acc += b[2] * x[n - 2] - a[2] * y[n - 2]
            y[n] = acc
        }
        return y
    }
}
