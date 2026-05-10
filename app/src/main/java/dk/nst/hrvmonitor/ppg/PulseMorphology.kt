package dk.nst.hrvmonitor.ppg

import kotlin.math.abs
import kotlin.math.max

/**
 * Pulse-wave morphology analysis.
 *
 * v2 fixes (post-first-results review):
 *   - Now operates on the **morphology signal** (despiked + detrended, no narrow
 *     bandpass) so harmonics carrying the dicrotic notch (5–15 Hz) are preserved.
 *     Previously we used the 0.7–4 Hz bandpass, which produced a sinusoid with no
 *     real morphology and nonsensical fiducials.
 *   - Sign convention: empirical check on this device shows bandpass peaks
 *     coincide with raw red **maxima** (systolic event = HIGHER red value, not
 *     lower as one might expect from absorption alone). Likely the bright torch
 *     + the depth of skin sampling makes scattering dominate over absorption at
 *     systole. So we do NOT invert — the detrended signal is already systole-up.
 *   - Global systolic-peak search (not "first half"): the systolic peak typically
 *     lives at 15–35 % of the beat duration, but a full-beat search is robust.
 *   - Sanity gates on RI / AIx / AGI / vascular age: garbage is reported as null
 *     instead of being passed through.
 */
object PulseMorphology {

    data class Result(
        val isAvailable: Boolean,
        val nBeats: Int,
        val nBeatsTotal: Int,
        val averagedBeat: FloatArray,
        val averagedBeatTime: FloatArray,
        val footIdx: Int,
        val systolicPeakIdx: Int,
        val dicroticNotchIdx: Int,
        val diastolicPeakIdx: Int,
        val crestTimeMs: Float?,
        val reflectionIndex: Float?,
        val augmentationIndex: Float?,
        val stiffnessIndexInv: Float?,
        val agingIndex: Float?,
        val vascularAgeYears: Float?
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)

        companion object {
            fun unavailable(nBeatsTotal: Int) = Result(
                isAvailable = false, nBeats = 0, nBeatsTotal = nBeatsTotal,
                averagedBeat = FloatArray(0), averagedBeatTime = FloatArray(0),
                footIdx = -1, systolicPeakIdx = -1, dicroticNotchIdx = -1, diastolicPeakIdx = -1,
                crestTimeMs = null, reflectionIndex = null, augmentationIndex = null,
                stiffnessIndexInv = null, agingIndex = null, vascularAgeYears = null
            )
        }
    }

    private const val UPSAMPLE = 4
    private const val BEAT_LEN = 250
    private const val MIN_BEAT_S = 0.4f
    private const val MAX_BEAT_S = 1.6f
    private const val MIN_VALID_BEATS = 20

    /**
     * @param morphSignal The detrended-but-not-bandpassed signal at [sampleRateHz].
     *                    Already systole-up on this device's optical setup (verified
     *                    empirically — see the polarity check diagnostic).
     * @param sampleRateHz Sample rate of [morphSignal].
     * @param peakIndices Systolic-event indices into [morphSignal] (from peak detection
     *                    on the heart-rate-bandpassed signal).
     */
    fun compute(
        morphSignal: FloatArray,
        sampleRateHz: Float,
        peakIndices: IntArray
    ): Result {
        if (morphSignal.size < 64 || peakIndices.size < 4) {
            return Result.unavailable(peakIndices.size)
        }

        // Spline-upsample for sub-frame fiducial precision (literature-validated).
        val up = SplineInterp.upsample(morphSignal, UPSAMPLE)
        val fsUp = sampleRateHz * UPSAMPLE
        val peaksUpRaw = IntArray(peakIndices.size) {
            (peakIndices[it] * UPSAMPLE).coerceIn(0, up.size - 1)
        }

        // The peak indices were found on the *bandpassed* signal — they're cycle markers
        // but their exact sample position may not coincide with the morphology signal's
        // local maximum. Snap each peak to the nearest local max within ±0.15 s on the
        // upsampled morphology signal.
        val snapWin = (0.15f * fsUp).toInt().coerceAtLeast(2)
        val peaksUp = IntArray(peaksUpRaw.size) { i ->
            val centre = peaksUpRaw[i]
            val from = (centre - snapWin).coerceAtLeast(0)
            val to = (centre + snapWin).coerceAtMost(up.size - 1)
            var best = centre; var bestVal = up[centre]
            for (k in from..to) if (up[k] > bestVal) { bestVal = up[k]; best = k }
            best
        }

        // Locate feet (local minimum just before each systolic peak).
        val feet = locateFeet(up, peaksUp)

        // Slice beats foot[i]..foot[i+1]. Two-stage filter:
        //   (a) Hard physiological range (0.4–1.6 s).
        //   (b) Hampel-style: reject beats whose duration is > 30 % from the median.
        // The second stage catches "missed peak → 1.5×-cycle segment" cases that
        // pass the hard filter but corrupt the averaged shape.
        val rawBeats = mutableListOf<FloatArray>()
        val beatDurationsSec = mutableListOf<Float>()
        val provisional = mutableListOf<Pair<FloatArray, Float>>()
        for (i in 0 until feet.size - 1) {
            val a = feet[i]; val b = feet[i + 1]
            val durSec = (b - a) / fsUp
            if (durSec !in MIN_BEAT_S..MAX_BEAT_S) continue
            provisional += resampleSegment(up, a, b, BEAT_LEN) to durSec
        }
        if (provisional.size < MIN_VALID_BEATS) return Result.unavailable(provisional.size)
        val provDurations = provisional.map { it.second }.sorted()
        val medProvDur = provDurations[provDurations.size / 2]
        for ((beat, dur) in provisional) {
            val rel = dur / medProvDur
            if (rel in 0.7f..1.3f) {
                rawBeats += beat
                beatDurationsSec += dur
            }
        }
        if (rawBeats.size < MIN_VALID_BEATS) return Result.unavailable(rawBeats.size)

        // Reject beats whose amplitude is far from the median (motion artifacts).
        val peakHeights = rawBeats.map { it.max() - it.min() }
        val medAmp = peakHeights.sorted()[peakHeights.size / 2]
        val mads = peakHeights.map { abs(it - medAmp) }.sorted()
        val mad = mads[mads.size / 2].coerceAtLeast(0.001f)
        val keptIndices = peakHeights.indices.filter { abs(peakHeights[it] - medAmp) <= 2.5f * mad }
        if (keptIndices.size < MIN_VALID_BEATS) return Result.unavailable(keptIndices.size)
        val accepted = keptIndices.map { rawBeats[it] }
        val keptDurations = keptIndices.map { beatDurationsSec[it] }.sorted()
        val medDur = keptDurations[keptDurations.size / 2]

        // Sample-wise median across aligned beats — robust to outliers, preserves shape.
        val avg = FloatArray(BEAT_LEN)
        val bucket = FloatArray(accepted.size)
        for (s in 0 until BEAT_LEN) {
            for (i in accepted.indices) bucket[i] = accepted[i][s]
            bucket.sort()
            avg[s] = bucket[bucket.size / 2]
        }
        val avgTime = FloatArray(BEAT_LEN) { it * medDur / (BEAT_LEN - 1) }

        // Fiducials on the averaged beat.
        val fid = locateFiducials(avg)

        // Compute metrics, gating each one against plausibility.
        val sysAmp = avg[fid.systolicPeak] - avg[fid.foot]
        val crestTimeMs = if (fid.systolicPeak > fid.foot)
            (avgTime[fid.systolicPeak] - avgTime[fid.foot]) * 1000f else null

        // RI / AIx / SI all require a CONFIDENT dicrotic notch. Without one, any
        // value we compute is fabricated from noise (we've seen RI = -13 % and
        // AIx = 113 % on palm where there's anatomically no notch). Return null
        // rather than mislead the user.
        val ri = if (fid.notchConfident &&
                fid.diastolicPeak > fid.systolicPeak && sysAmp > 1e-6f) {
            val diaAmp = avg[fid.diastolicPeak] - avg[fid.foot]
            val v = (100f * diaAmp / sysAmp)
            if (v in 5f..120f) v else null
        } else null

        val aix = if (fid.notchConfident &&
                fid.dicroticNotch in (fid.systolicPeak + 1) until fid.diastolicPeak &&
                sysAmp > 1e-6f) {
            val p1 = avg[fid.systolicPeak] - avg[fid.foot]
            val p2 = avg[fid.diastolicPeak] - avg[fid.foot]
            val v = (100f * (p2 - p1) / p1)
            if (v in -50f..50f) v else null
        } else null

        val si = if (fid.notchConfident && fid.diastolicPeak > fid.systolicPeak) {
            val dt = (avgTime[fid.diastolicPeak] - avgTime[fid.systolicPeak]).coerceAtLeast(1e-3f)
            1f / dt
        } else null

        val (agi, _) = computeAgi(avg, fsAvgBeat = (BEAT_LEN - 1) / medDur)
        // Vascular-age regression: only emit if AGI is in a plausible range. Outside the
        // calibrated range the linear model becomes meaningless.
        val vascAge = agi?.takeIf { it in -1.5f..1.5f }?.let { (65f - 25f * it).coerceIn(20f, 85f) }

        return Result(
            isAvailable = true,
            nBeats = accepted.size,
            nBeatsTotal = peakIndices.size,
            averagedBeat = avg,
            averagedBeatTime = avgTime,
            footIdx = fid.foot,
            systolicPeakIdx = fid.systolicPeak,
            dicroticNotchIdx = fid.dicroticNotch,
            diastolicPeakIdx = fid.diastolicPeak,
            crestTimeMs = crestTimeMs,
            reflectionIndex = ri,
            augmentationIndex = aix,
            stiffnessIndexInv = si,
            agingIndex = agi,
            vascularAgeYears = vascAge
        )
    }

    /**
     * Walk back from each peak to its local minimum, but **only within the last
     * 30 % of the inter-peak interval**. The pre-systolic foot is always at the
     * very end of diastole — searching the whole interval risks picking up the
     * dicrotic-notch valley earlier in the cycle, which then segments the beat
     * starting at the wrong point.
     */
    private fun locateFeet(x: FloatArray, peaksUp: IntArray): IntArray {
        val out = IntArray(peaksUp.size)
        for (i in peaksUp.indices) {
            val prev = if (i == 0) (peaksUp[i] - 600).coerceAtLeast(0) else peaksUp[i - 1]
            val span = peaksUp[i] - prev
            // Restrict the search to the last ~30 % of the gap (or last 0.4 s, whichever larger).
            val from = (peaksUp[i] - (span * 0.35f).toInt())
                .coerceAtLeast(prev + 1)
                .coerceAtMost(peaksUp[i])
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
        val diastolicPeak: Int,
        // True only when the notch is a real prominent feature, not a manufactured
        // fallback. Downstream metrics (RI, AIx, SI) must skip when this is false.
        val notchConfident: Boolean
    )

    /**
     * Derivative-based fiducial detection — pyPPG / PulseAnalyse style.
     *
     *  • Foot   : tangent intersection. Line through (idxMaxUpslope, beat[idxMaxUpslope])
     *             with slope d1[idxMaxUpslope] is intersected with the pre-systolic
     *             baseline. More reproducible across beats than a local-min search
     *             because it's defined by slope rather than by amplitude.
     *  • Sys    : first PPG' (d1) zero-crossing from + to − after foot.
     *  • Notch  : first prominent positive peak of PPG'' (d2) on the descending limb
     *             after sys. Same as before but the prominence test against the d²
     *             noise floor stays in place. Notch must clear D2_PROMINENCE_K · σ(d²)
     *             to be flagged as confident — otherwise RI/AIx return null.
     *  • Dia    : next d1 zero-crossing from + to − after notch; falls back to local
     *             max if the zero crossing is missing.
     *
     * Reference: Goda MA, Charlton PH, Behar JA 2024. pyPPG. *Physiol Meas* 45:045001
     *   https://doi.org/10.1088/1361-6579/ad33a2
     */
    private fun locateFiducials(beat: FloatArray): Fiducials {
        val n = beat.size
        if (n < 16) return Fiducials(0, 0, 0, 0, notchConfident = false)

        // Light 5-point smoothing reduces noise propagation into the derivatives.
        val smoothed = movingAvg5(beat)

        // Central-difference derivatives.
        val d1 = FloatArray(n)
        for (i in 1 until n - 1) d1[i] = 0.5f * (smoothed[i + 1] - smoothed[i - 1])
        d1[0] = d1[1]; d1[n - 1] = d1[n - 2]
        val d2 = FloatArray(n)
        for (i in 1 until n - 1) d2[i] = smoothed[i + 1] - 2f * smoothed[i] + smoothed[i - 1]
        d2[0] = d2[1]; d2[n - 1] = d2[n - 2]

        // Maximum upslope: argmax of d1 within the first 40 % of the beat.
        val upslopeEnd = (n * 0.40f).toInt().coerceAtLeast(4)
        var msIdx = 1; var msVal = d1[1]
        for (i in 2 until upslopeEnd) if (d1[i] > msVal) { msVal = d1[i]; msIdx = i }

        // Foot via tangent intersection PLUS a local-min sanity floor.
        // Tangent intersection: project the line through (msIdx, beat[msIdx])
        // with slope d1[msIdx] backwards until it hits the pre-systolic baseline.
        // This works cleanly on sharp, well-defined upstrokes — but on fingertip
        // reflectance, where the systolic upstroke is broadened by sensor
        // saturation, the projection can overshoot 60-100 ms earlier than the
        // actual anatomical foot. So we ALSO find the local minimum in the
        // first 25 % of the beat and use whichever of the two lands later.
        val baseline = smoothed[0]
        val tangentFoot = if (msVal > 1e-6f) {
            val t = msIdx - (smoothed[msIdx] - baseline) / msVal
            t.toInt().coerceIn(0, (msIdx - 1).coerceAtLeast(0))
        } else 0
        val footSearchEnd = (n * 0.25f).toInt().coerceAtLeast(2)
        var localMinFoot = 0; var localMinVal = smoothed[0]
        for (i in 1 until footSearchEnd) {
            if (smoothed[i] < localMinVal) { localMinVal = smoothed[i]; localMinFoot = i }
        }
        val footIdx = maxOf(tangentFoot, localMinFoot)

        // Systolic peak: first d1 zero-crossing (+ → −) after msIdx.
        var sysIdx = -1
        for (i in msIdx + 1 until n - 1) {
            if (d1[i] >= 0f && d1[i + 1] < 0f) { sysIdx = i; break }
        }
        if (sysIdx < 0) {
            // Fallback: global max (legacy behaviour on beats with no clean zero-crossing).
            var maxV = Float.NEGATIVE_INFINITY
            for (i in 0 until n) if (smoothed[i] > maxV) { maxV = smoothed[i]; sysIdx = i }
        }

        // Notch search range: 6 % past sys, ending 5 % before end of beat.
        val searchStart = (sysIdx + (n * 0.06f).toInt()).coerceAtMost(n - 4)
        val searchEnd = (n - (n * 0.05f).toInt()).coerceAtLeast(searchStart + 4)

        // d² noise floor inside the search window.
        var d2Sum = 0.0; var d2SumSq = 0.0; var d2Count = 0
        for (i in searchStart until searchEnd) {
            d2Sum += d2[i]; d2SumSq += d2[i].toDouble() * d2[i].toDouble(); d2Count++
        }
        val d2Std = if (d2Count > 1) {
            val m = d2Sum / d2Count
            kotlin.math.sqrt((d2SumSq / d2Count - m * m).coerceAtLeast(0.0)).toFloat()
        } else 0f

        // Notch = strongest positive d² peak in [searchStart, searchEnd].
        var notchIdx = -1; var bestProm = Float.NEGATIVE_INFINITY
        for (i in searchStart + 1 until searchEnd - 1) {
            if (d2[i] > d2[i - 1] && d2[i] > d2[i + 1] && d2[i] > 0f) {
                if (d2[i] > bestProm) { bestProm = d2[i]; notchIdx = i }
            }
        }
        val notchConfident = notchIdx >= 0 && bestProm > D2_PROMINENCE_K * d2Std
        if (notchIdx < 0) notchIdx = (sysIdx + (n - sysIdx) / 3).coerceIn(searchStart, searchEnd - 1)

        // Diastolic peak: first d1 zero-crossing (+ → −) after notch; if missing,
        // fall back to a local max scan capped at the end of the search window.
        var diaIdx = -1
        for (i in notchIdx + 1 until searchEnd - 1) {
            if (d1[i] >= 0f && d1[i + 1] < 0f) { diaIdx = i; break }
        }
        if (diaIdx < 0) {
            var maxV = smoothed[notchIdx]; diaIdx = notchIdx
            for (i in notchIdx + 1 until searchEnd) {
                if (smoothed[i] > maxV) { maxV = smoothed[i]; diaIdx = i }
            }
        }
        if (diaIdx <= notchIdx) diaIdx = notchIdx

        return Fiducials(footIdx, sysIdx, notchIdx, diaIdx, notchConfident)
    }

    private fun movingAvg5(x: FloatArray): FloatArray {
        val n = x.size
        if (n < 5) return x.copyOf()
        val out = FloatArray(n)
        out[0] = x[0]; out[1] = (x[0] + x[1] + x[2]) / 3f
        for (i in 2 until n - 2) {
            out[i] = (x[i - 2] + x[i - 1] + x[i] + x[i + 1] + x[i + 2]) / 5f
        }
        out[n - 2] = (x[n - 3] + x[n - 2] + x[n - 1]) / 3f
        out[n - 1] = x[n - 1]
        return out
    }

    private const val D2_PROMINENCE_K = 2.0f

    private fun computeAgi(beat: FloatArray, fsAvgBeat: Float): Pair<Float?, FloatArray?> {
        val n = beat.size
        if (n < 20) return null to null
        val rng = (beat.max() - beat.min()).coerceAtLeast(1e-6f)
        val norm = FloatArray(n) { (beat[it] - beat.min()) / rng }

        val d2 = FloatArray(n)
        for (i in 1 until n - 1) d2[i] = norm[i + 1] - 2f * norm[i] + norm[i - 1]
        d2[0] = d2[1]; d2[n - 1] = d2[n - 2]

        val limit = (n * 0.55f).toInt()
        val signs = mutableListOf<Pair<Int, Float>>()
        var lookingForMax = true
        for (i in 2 until limit - 2) {
            val left = d2[i - 1]; val mid = d2[i]; val right = d2[i + 1]
            val isMax = mid > left && mid >= right
            val isMin = mid < left && mid <= right
            if (lookingForMax && isMax) { signs += i to mid; lookingForMax = false }
            else if (!lookingForMax && isMin) { signs += i to mid; lookingForMax = true }
            if (signs.size >= 5) break
        }
        if (signs.size < 5) return null to null

        val a = signs[0].second
        val b = signs[1].second
        val c = signs[2].second
        val d = signs[3].second
        val e = signs[4].second
        if (abs(a) < 1e-6f) return null to null
        val agi = (b - c - d - e) / a
        return agi.coerceIn(-3f, 3f) to floatArrayOf(a, b, c, d, e)
    }
}
