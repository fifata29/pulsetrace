package dk.nst.hrvmonitor.ppg

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Time-domain HRV metrics computed from a series of RR intervals (milliseconds).
 *
 * - bpm: 60_000 / median(RR)
 * - sdnn: standard deviation of NN intervals
 * - rmssd: root mean square of successive differences (the canonical short-term HRV index)
 * - pnn50: % of successive RR diffs greater than 50 ms
 *
 * Outliers are filtered with a simple 20% deviation rule before metrics are computed.
 */
object HrvCalculator {

    data class Metrics(
        val bpm: Float?,
        val sdnnMs: Float?,
        val rmssdMs: Float?,
        val pnn50: Float?,
        val validBeats: Int,
        val totalBeats: Int
    )

    fun compute(rrMs: List<Float>): Metrics {
        if (rrMs.size < 3) {
            return Metrics(null, null, null, null, 0, rrMs.size)
        }

        val nn = filterOutliers(rrMs)
        if (nn.size < 3) {
            return Metrics(null, null, null, null, nn.size, rrMs.size)
        }

        val sorted = nn.sorted()
        val median = sorted[sorted.size / 2]
        val bpm = 60_000f / median

        val mean = nn.average().toFloat()
        var varSum = 0f
        for (v in nn) varSum += (v - mean) * (v - mean)
        val sdnn = sqrt(varSum / nn.size)

        var sqDiffSum = 0f
        var nn50 = 0
        for (i in 1 until nn.size) {
            val d = nn[i] - nn[i - 1]
            sqDiffSum += d * d
            if (abs(d) > 50f) nn50++
        }
        val rmssd = sqrt(sqDiffSum / (nn.size - 1))
        val pnn50 = 100f * nn50 / (nn.size - 1)

        return Metrics(
            bpm = bpm,
            sdnnMs = sdnn,
            rmssdMs = rmssd,
            pnn50 = pnn50,
            validBeats = nn.size,
            totalBeats = rrMs.size
        )
    }

    private fun filterOutliers(rrMs: List<Float>): List<Float> {
        val out = ArrayList<Float>(rrMs.size)
        for ((i, v) in rrMs.withIndex()) {
            if (v < 300f || v > 2000f) continue
            val ref = if (out.isNotEmpty()) out.last() else rrMs[i]
            if (abs(v - ref) / ref <= 0.20f) out += v
        }
        return out
    }
}
