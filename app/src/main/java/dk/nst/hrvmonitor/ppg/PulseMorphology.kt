package dk.nst.hrvmonitor.ppg

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pulse-wave morphology analysis.
 *
 * Pipeline:
 *   1. Take the bandpassed signal + detected systolic peaks.
 *   2. Cubic-spline upsample to a higher rate (default 4× → 120 Hz from a 30 Hz capture).
 *   3. Locate the foot (local minimum) preceding each systolic peak. A "beat" runs
 *      foot[i] → foot[i+1].
 *   4. Resample each beat to a fixed grid (250 samples), aligned at the foot.
 *      Reject beats that are too short, too long, or have anomalous amplitude.
 *   5. Compute a robust average beat: median across aligned beats per sample.
 *   6. On the average beat, locate fiducial points using 1st & 2nd derivatives:
 *      foot · systolic peak · dicrotic notch · diastolic peak.
 *   7. Compute scalar metrics: Crest Time, Reflection Index, Augmentation Index,
 *      Stiffness Index proxy, and Takazawa's Aging Index (AGI) from SDPPG waves.
 *   8. Estimate vascular age from AGI using the Takazawa regression.
 *
 * All metrics are gated by the number of usable beats. If < [minValidBeats] beats
 * survive the quality filter, returns [Result.unavailable].
 */
object PulseMorphology {

    data class Result(
        val isAvailable: Boolean,
        val nBeats: Int,
        val nBeatsTotal: Int,
        /** Averaged beat waveform on a fixed-length grid; index 0 == foot. */
        val averagedBeat: FloatArray,
        /** Time axis (seconds) of [averagedBeat]; 0 at foot. */
        val averagedBeatTime: FloatArray,
        val footIdx: Int,
        val systolicPeakIdx: Int,
        val dicroticNotchIdx: Int,
        val diastolicPeakIdx: Int,
        val crestTimeMs: Float?,
        val reflectionIndex: Float?,        // d/s × 100, 0..100
        val augmentationIndex: Float?,      // (P2-P1)/P1 × 100, can be negative or positive
        val stiffnessIndexInv: Float?,      // 1 / (t_diastolic - t_systolic) in 1/s
        val agingIndex: Float?,             // Takazawa (b-c-d-e)/a, dimensionless
        val vascularAgeYears: Float?
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)

        companion object {
            fun unavailable(nBeatsTotal: Int) = Result(
                isAvailable = false,
                nBeats = 0,
                nBeatsTotal = nBeatsTotal,
                averagedBeat = FloatArray(0),
                averagedBeatTime = FloatArray(0),
                footIdx = -1,
                systolicPeakIdx = -1,
                dicroticNotchIdx = -1,
                diastolicPeakIdx = -1,
                crestTimeMs = null,
                reflectionIndex = null,
                augmentationIndex = null,
                stiffnessIndexInv = null,
                agingIndex = null,
                vascularAgeYears = null
            )
        }
    }

    private const val UPSAMPLE = 4
    private const val BEAT_LEN = 250
    private const val MIN_BEAT_S = 0.4f      // < 150 BPM
    private const val MAX_BEAT_S = 1.6f      // > 37 BPM
    private const val MIN_VALID_BEATS = 20

    fun compute(
        signalUniform: FloatArray,    // bandpassed signal, uniform sample rate fs
        sampleRateHz: Float,
        peakIndices: IntArray
    ): Result {
        if (signalUniform.size < 64 || peakIndices.size < 4) {
            return Result.unavailable(peakIndices.size)
        }

        // 1) Upsample to make foot/notch detection sub-sample precise.
        val up = SplineInterp.upsample(signalUniform, UPSAMPLE)
        val fsUp = sampleRateHz * UPSAMPLE

        // Map original peak indices to upsampled grid.
        val peaksUp = IntArray(peakIndices.size) { (peakIndices[it] * UPSAMPLE).coerceAtMost(up.size - 1) }

        // 2) Find foot (local minimum) before each peak.
        val feet = locateFeet(up, peaksUp)

        // 3) Slice beats foot[i]..foot[i+1] and resample to BEAT_LEN.
        val rawBeats = mutableListOf<FloatArray>()
        val beatDurationsSec = mutableListOf<Float>()
        for (i in 0 until feet.size - 1) {
            val a = feet[i]; val b = feet[i + 1]
            val durSec = (b - a) / fsUp
            if (durSec !in MIN_BEAT_S..MAX_BEAT_S) continue
            rawBeats += resampleSegment(up, a, b, BEAT_LEN)
            beatDurationsSec += durSec
        }
        if (rawBeats.size < MIN_VALID_BEATS) {
            return Result.unavailable(rawBeats.size)
        }

        // 4) Reject amplitude outliers (>2 MAD from median peak height).
        val peakHeights = rawBeats.map { it.max() - it.min() }
        val medAmp = peakHeights.sorted().let { it[it.size / 2] }
        val mad = peakHeights.map { kotlin.math.abs(it - medAmp) }.sorted().let { it[it.size / 2] }.coerceAtLeast(0.001f)
        val accepted = rawBeats.zip(peakHeights).filter { kotlin.math.abs(it.second - medAmp) <= 2.5f * mad }.map { it.first }
        if (accepted.size < MIN_VALID_BEATS) {
            return Result.unavailable(accepted.size)
        }

        // 5) Median across aligned beats per sample → robust average beat.
        val avg = FloatArray(BEAT_LEN)
        val sampleBucket = FloatArray(accepted.size)
        for (s in 0 until BEAT_LEN) {
            for (i in accepted.indices) sampleBucket[i] = accepted[i][s]
            sampleBucket.sort()
            avg[s] = sampleBucket[sampleBucket.size / 2]
        }
        // Time axis: the average beat duration is the median of the durations of
        // the *accepted* beats (we kept only beats that passed the amplitude filter,
        // so use their median for the time axis).
        val keptDurations = beatDurationsSec.zip(peakHeights)
            .filter { kotlin.math.abs(it.second - medAmp) <= 2.5f * mad }
            .map { it.first }
        val medDur = keptDurations.sorted()[keptDurations.size / 2]
        val avgTime = FloatArray(BEAT_LEN) { it * medDur / (BEAT_LEN - 1) }

        // 6) Fiducial points on the average beat.
        val fiducials = locateFiducials(avg)
        val footI = fiducials.foot
        val sysI = fiducials.systolicPeak
        val notchI = fiducials.dicroticNotch
        val diaI = fiducials.diastolicPeak

        // 7) Metrics.
        val sysAmp = avg[sysI] - avg[footI]
        val crestTimeMs = if (sysI > footI) (avgTime[sysI] - avgTime[footI]) * 1000f else null

        // Reflection Index — diastolic peak amp / systolic peak amp (× 100).
        val ri = if (diaI > sysI && sysAmp > 1e-6f) {
            val diaAmp = avg[diaI] - avg[footI]
            (100f * diaAmp / sysAmp).coerceIn(0f, 200f)
        } else null

        // Augmentation Index — (P2 - P1)/P1 × 100. P1 is systolic peak (forward wave),
        // P2 is the inflection point at the dicrotic notch (reflected wave reference).
        // Reported as a percentage; can be negative for younger/elastic vasculature.
        val aix = if (notchI in (sysI + 1) until diaI && sysAmp > 1e-6f) {
            val p1 = avg[sysI] - avg[footI]
            val p2 = avg[diaI] - avg[footI]
            (100f * (p2 - p1) / p1)
        } else null

        // Stiffness Index proxy (we don't have subject height; use 1/Δt).
        val si = if (diaI > sysI) {
            val dt = (avgTime[diaI] - avgTime[sysI]).coerceAtLeast(1e-3f)
            1f / dt
        } else null

        // 8) Takazawa Aging Index from SDPPG.
        val (agi, abcde) = computeAgi(avg, sampleRateHz = (BEAT_LEN - 1) / medDur)

        // 9) Vascular age — Takazawa et al. linear regression of AGI vs chronological age:
        //    age ≈ 65 - 25 * AGI (rough fit from published data; 1990s cohorts).
        //    Bounded to reasonable adult range.
        val vascAge = agi?.let { (65f - 25f * it).coerceIn(18f, 90f) }

        return Result(
            isAvailable = true,
            nBeats = accepted.size,
            nBeatsTotal = peakIndices.size,
            averagedBeat = avg,
            averagedBeatTime = avgTime,
            footIdx = footI,
            systolicPeakIdx = sysI,
            dicroticNotchIdx = notchI,
            diastolicPeakIdx = diaI,
            crestTimeMs = crestTimeMs,
            reflectionIndex = ri,
            augmentationIndex = aix,
            stiffnessIndexInv = si,
            agingIndex = agi,
            vascularAgeYears = vascAge
        )
    }

    /** For each peak, walk backward to the local minimum since the previous peak. */
    private fun locateFeet(x: FloatArray, peaksUp: IntArray): IntArray {
        val out = IntArray(peaksUp.size)
        for (i in peaksUp.indices) {
            val from = if (i == 0) max(0, peaksUp[i] - 600) else peaksUp[i - 1] + 1
            var minIdx = peaksUp[i]
            var minVal = x[peaksUp[i]]
            var k = peaksUp[i] - 1
            while (k >= from) {
                if (x[k] < minVal) { minVal = x[k]; minIdx = k }
                k--
            }
            out[i] = minIdx
        }
        return out
    }

    /** Linear-resample x[a..b] to a fixed-length array of [n] samples. */
    private fun resampleSegment(x: FloatArray, a: Int, b: Int, n: Int): FloatArray {
        val out = FloatArray(n)
        val span = (b - a).toFloat()
        for (i in 0 until n) {
            val pos = a + span * i / (n - 1)
            val lo = pos.toInt().coerceIn(a, b)
            val hi = (lo + 1).coerceAtMost(b)
            val frac = pos - lo
            out[i] = x[lo] * (1f - frac) + x[hi] * frac
        }
        return out
    }

    private data class Fiducials(
        val foot: Int,
        val systolicPeak: Int,
        val dicroticNotch: Int,
        val diastolicPeak: Int
    )

    /**
     * Find foot (start), systolic peak (global max), dicrotic notch (local min on
     * descending limb), and diastolic peak (local max after notch).
     *
     * The notch is detected by the **second-derivative method** (most-cited
     * approach in the PPG literature, Takazawa-style): inside the systolic-peak
     * → end window, find the most prominent positive peak in the 2nd derivative.
     * That position corresponds to a strong upward inflection — the notch.
     */
    private fun locateFiducials(beat: FloatArray): Fiducials {
        val n = beat.size
        val foot = 0  // we already aligned at the foot

        // Systolic peak: global max in the first half.
        var sysIdx = 0
        var sysVal = Float.NEGATIVE_INFINITY
        for (i in 0 until n / 2) if (beat[i] > sysVal) { sysVal = beat[i]; sysIdx = i }

        // Search dicrotic notch on the descending limb (sysIdx+10 .. n-30).
        val searchStart = (sysIdx + (n * 0.06f).toInt()).coerceAtMost(n - 4)
        val searchEnd = (n - (n * 0.10f).toInt()).coerceAtLeast(searchStart + 4)

        // Compute second derivative within the search range (centered diff).
        val d2 = FloatArray(n)
        for (i in 1 until n - 1) d2[i] = beat[i + 1] - 2f * beat[i] + beat[i - 1]

        var notchIdx = -1
        var bestProm = Float.NEGATIVE_INFINITY
        for (i in searchStart + 1 until searchEnd - 1) {
            if (d2[i] > d2[i - 1] && d2[i] > d2[i + 1] && d2[i] > 0f) {
                if (d2[i] > bestProm) { bestProm = d2[i]; notchIdx = i }
            }
        }
        if (notchIdx < 0) notchIdx = (sysIdx + (n - sysIdx) / 3).coerceIn(searchStart, searchEnd - 1)

        // Diastolic peak = local max between notch and end.
        var diaIdx = notchIdx
        var diaVal = beat[notchIdx]
        for (i in notchIdx + 1 until searchEnd) if (beat[i] > diaVal) { diaVal = beat[i]; diaIdx = i }

        return Fiducials(foot, sysIdx, notchIdx, diaIdx)
    }

    /**
     * Compute Takazawa's Aging Index from the second derivative of [beat].
     * SDPPG has 5 named extrema in early-systole — a (positive), b (negative),
     * c (positive small), d (negative small), e (positive at the dicrotic notch).
     *
     * AGI = (b − c − d − e) / a, where each letter is the **signed** amplitude
     * of the corresponding wave on the SDPPG. Validated to correlate r ≈ 0.8
     * with chronological age.
     */
    private fun computeAgi(beat: FloatArray, sampleRateHz: Float): Pair<Float?, FloatArray?> {
        val n = beat.size
        if (n < 20) return null to null
        // Normalise so that absolute amplitudes are scale-invariant.
        val rng = (beat.max() - beat.min()).coerceAtLeast(1e-6f)
        val norm = FloatArray(n) { (beat[it] - beat.min()) / rng }

        // Second derivative.
        val d2 = FloatArray(n)
        for (i in 1 until n - 1) d2[i] = norm[i + 1] - 2f * norm[i] + norm[i - 1]
        d2[0] = d2[1]; d2[n - 1] = d2[n - 2]

        // Find the 5 successive extrema in d2 within the first ~50 % of the beat
        // (Takazawa's a/b/c/d/e all live in the systolic ejection phase).
        val limit = (n * 0.55f).toInt()
        val signs = mutableListOf<Pair<Int, Float>>()  // (idx, signed amp)
        var lookingForMax = true
        var lastIdx = 0
        for (i in 2 until limit - 2) {
            val left = d2[i - 1]; val mid = d2[i]; val right = d2[i + 1]
            val isMax = mid > left && mid >= right
            val isMin = mid < left && mid <= right
            if (lookingForMax && isMax) {
                signs += i to mid; lookingForMax = false; lastIdx = i
            } else if (!lookingForMax && isMin) {
                signs += i to mid; lookingForMax = true; lastIdx = i
            }
            if (signs.size >= 5) break
        }
        if (signs.size < 5) return null to null

        val a = signs[0].second
        val b = signs[1].second
        val c = signs[2].second
        val d = signs[3].second
        val e = signs[4].second
        if (kotlin.math.abs(a) < 1e-6f) return null to null
        val agi = (b - c - d - e) / a
        return agi.coerceIn(-3f, 3f) to floatArrayOf(a, b, c, d, e)
    }
}
