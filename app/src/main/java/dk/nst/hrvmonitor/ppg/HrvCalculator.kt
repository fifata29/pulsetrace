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

        // Build an accept-mask aligned with the original RR sequence so that
        // RMSSD / pNN50 (which are *successive-pair* metrics) only count pairs
        // where BOTH RRs are accepted AND are consecutive in time. Previous
        // implementation flattened the filtered list and computed successive
        // diffs on it, which counted spurious "diffs" across rejected gaps —
        // a 900 ms → (rejected) → 700 ms pair became a 200 ms diff even though
        // a real beat in between would have made the diffs 50 + 50 ms.
        val acceptMask = acceptanceMask(rrMs)
        val nn = ArrayList<Float>(rrMs.size)
        for (i in rrMs.indices) if (acceptMask[i]) nn.add(rrMs[i])
        if (nn.size < 3) {
            return Metrics(null, null, null, null, nn.size, rrMs.size)
        }

        val sorted = nn.sorted()
        val median = sorted[sorted.size / 2]
        val bpm = 60_000f / median

        var meanSum = 0.0
        for (v in nn) meanSum += v
        val mean = (meanSum / nn.size).toFloat()
        var varSum = 0f
        for (v in nn) varSum += (v - mean) * (v - mean)
        val sdnn = sqrt(varSum / nn.size)

        // Successive-pair metrics: only count adjacent ACCEPTED RRs in the
        // original sequence. Pairs spanning a rejected RR are skipped.
        var sqDiffSum = 0.0
        var nn50 = 0
        var pairCount = 0
        for (i in 1 until rrMs.size) {
            if (!acceptMask[i] || !acceptMask[i - 1]) continue
            val d = rrMs[i] - rrMs[i - 1]
            sqDiffSum += d.toDouble() * d.toDouble()
            if (abs(d) > 50f) nn50++
            pairCount++
        }
        val rmssd: Float? = if (pairCount > 0) sqrt(sqDiffSum / pairCount).toFloat() else null
        val pnn50: Float? = if (pairCount > 0) 100f * nn50.toFloat() / pairCount.toFloat() else null

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
     * Returns a per-RR boolean mask: true = accept, false = reject. Median-based
     * rejection: an RR is accepted if it's in the physiological 300–2000 ms band
     * AND within [REJECT_FRACTION] of the median of the in-range RRs. One
     * refinement pass tightens the median once obvious outliers are gone.
     */
    private fun acceptanceMask(rrMs: List<Float>): BooleanArray {
        val mask = BooleanArray(rrMs.size)
        val inRangeIdx = rrMs.indices.filter { rrMs[it] in 300f..2000f }
        if (inRangeIdx.size < 3) {
            for (i in inRangeIdx) mask[i] = true
            return mask
        }

        val inRange = inRangeIdx.map { rrMs[it] }.sorted()
        var ref = inRange[inRange.size / 2]
        var keptIdx = inRangeIdx.filter { abs(rrMs[it] - ref) / ref <= REJECT_FRACTION }
        if (keptIdx.size >= 3) {
            val keptSorted = keptIdx.map { rrMs[it] }.sorted()
            ref = keptSorted[keptSorted.size / 2]
            keptIdx = inRangeIdx.filter { abs(rrMs[it] - ref) / ref <= REJECT_FRACTION }
        }
        for (i in keptIdx) mask[i] = true
        return mask
    }

    // Tightened from 0.25 → 0.20. At 0.25 we kept RRs up to ±25% from median;
    // recent forearm sessions had genuine RRs ~900 ms but mis-detected pairs at
    // 700 ms and 1100 ms (each ±22 %) which slipped through and inflated RMSSD.
    private const val REJECT_FRACTION = 0.20f
}
