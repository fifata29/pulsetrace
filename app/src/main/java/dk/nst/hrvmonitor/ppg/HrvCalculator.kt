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

    /**
     * Median-based outlier rejection. The previous sequential filter latched onto the
     * first in-range RR; if that was a filter-ringing artifact, every real beat
     * downstream got rejected and BPM ended up at 180+ from a tiny noise cluster.
     *
     * Now: compute the median of all physiologically-plausible RRs (300–2000 ms) and
     * reject anything more than [REJECT_FRACTION] away from it. One refinement pass
     * tightens the median once obvious outliers are gone.
     */
    private fun filterOutliers(rrMs: List<Float>): List<Float> {
        val inRange = rrMs.filter { it in 300f..2000f }
        if (inRange.size < 3) return inRange

        var ref = inRange.sorted()[inRange.size / 2]
        var accepted = inRange.filter { kotlin.math.abs(it - ref) / ref <= REJECT_FRACTION }
        if (accepted.size < 3) return accepted

        // Refine: recompute median on the cleaned set, re-apply the same threshold.
        ref = accepted.sorted()[accepted.size / 2]
        accepted = inRange.filter { kotlin.math.abs(it - ref) / ref <= REJECT_FRACTION }
        return accepted
    }

    private const val REJECT_FRACTION = 0.25f
}
