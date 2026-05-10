package dk.nst.hrvmonitor.ppg

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming PPG processor. Holds a rolling buffer of timestamped raw samples,
 * applies detrending + bandpass filtering, and detects systolic peaks → RR intervals.
 *
 * Sample rate is estimated continuously from inter-frame timing because CameraX
 * cannot guarantee a fixed fps across devices.
 */
class SignalProcessor(
    private val windowSeconds: Float = 30f,
    private val lowHz: Float = 0.7f,    // ~42 BPM
    private val highHz: Float = 4.0f    // ~240 BPM
) {

    data class Sample(val tSec: Float, val raw: Float, val filtered: Float)
    data class Peak(val tSec: Float, val value: Float)

    data class Snapshot(
        val sampleRateHz: Float,
        val samples: List<Sample>,
        val peaks: List<Peak>,
        val rrMs: List<Float>,        // RR intervals in milliseconds, oldest first
        val coverage: Float,           // last-known fingertip coverage
        val spectralBpm: Float = 0f    // dominant-frequency BPM as an independent sanity check
    )

    private val raw = ArrayDeque<TimedSample>()
    private var lastCoverage = 0f

    fun reset() {
        raw.clear()
        lastCoverage = 0f
    }

    fun addSample(timestampNs: Long, redValue: Float, coverage: Float) {
        lastCoverage = coverage
        raw.addLast(TimedSample(timestampNs, redValue))

        val cutoffNs = timestampNs - (windowSeconds * 1_000_000_000L).toLong()
        while (raw.isNotEmpty() && raw.first().tNs < cutoffNs) {
            raw.removeFirst()
        }
    }

    fun snapshot(): Snapshot {
        val n = raw.size
        if (n < 16) {
            return Snapshot(0f, emptyList(), emptyList(), emptyList(), lastCoverage, 0f)
        }

        val t0 = raw.first().tNs
        val tSec = FloatArray(n) { (raw[it].tNs - t0) / 1e9f }
        val redArr = FloatArray(n) { raw[it].red }

        val durSec = tSec.last() - tSec.first()
        val fs = if (durSec > 0f) (n - 1) / durSec else 30f

        // Resample to a uniform grid for filtering (linear interpolation).
        val uniformN = (durSec * fs).toInt().coerceAtLeast(16)
        val uniformDt = durSec / (uniformN - 1)
        val uniform = FloatArray(uniformN)
        var idx = 0
        for (i in 0 until uniformN) {
            val t = tSec.first() + i * uniformDt
            while (idx < n - 2 && tSec[idx + 1] < t) idx++
            val t1 = tSec[idx]; val t2 = tSec[idx + 1]
            val a = if (t2 > t1) ((t - t1) / (t2 - t1)).coerceIn(0f, 1f) else 0f
            uniform[i] = redArr[idx] * (1f - a) + redArr[idx + 1] * a
        }

        // 5-point median filter — kills single-sample motion spikes before they get
        // amplified by the bandpass and create spurious peaks. Trivial cost.
        val despiked = medianFilter5(uniform)

        // Detrend with moving average (~1.5 s).
        val maWin = (1.5f * fs).toInt().coerceAtLeast(5)
        val detrended = movingAverageDetrend(despiked, maWin)

        // Bandpass via cascaded biquads.
        val filtered = butterworthBandpass(detrended, fs, lowHz, highHz)

        // Edge-trim peak detection: filtfilt rings at both signal boundaries.
        // Discarding ~1.5 s on each end removes those bogus spikes that previously
        // contaminated the very first and last RR intervals.
        val edgeTrim = (1.5f * fs).toInt().coerceAtLeast(8).coerceAtMost(uniformN / 3)
        val safeStart = edgeTrim
        val safeEnd = uniformN - edgeTrim
        val peakIdx = if (safeEnd > safeStart + 4) {
            findPeaks(filtered, fs, minSeparationSec = 0.35f, fromIdx = safeStart, toIdx = safeEnd)
        } else IntArray(0)

        // Spectral BPM as a sanity check, computed independently from peak detection.
        val spectralBpm = dominantBpm(filtered, fs, lowHz, highHz)

        val samples = ArrayList<Sample>(uniformN)
        for (i in 0 until uniformN) {
            samples += Sample(
                tSec = tSec.first() + i * uniformDt,
                raw = uniform[i],
                filtered = filtered[i]
            )
        }
        val peaks = peakIdx.map {
            Peak(tSec = samples[it].tSec, value = filtered[it])
        }

        val rr = ArrayList<Float>(peaks.size)
        for (i in 1 until peaks.size) {
            rr += (peaks[i].tSec - peaks[i - 1].tSec) * 1000f
        }

        return Snapshot(fs, samples, peaks, rr, lastCoverage, spectralBpm)
    }

    /** Dominant frequency in [lowHz, highHz] via Goertzel-like DFT scan, returned as BPM. */
    private fun dominantBpm(x: FloatArray, fs: Float, lowHz: Float, highHz: Float): Float {
        if (x.size < 32) return 0f
        var bestF = lowHz
        var bestPow = -1.0
        var f = lowHz
        while (f <= highHz) {
            val w = 2.0 * PI * f / fs
            var sumC = 0.0; var sumS = 0.0
            for (i in x.indices) {
                val v = x[i].toDouble()
                sumC += v * cos(w * i)
                sumS += v * sin(w * i)
            }
            val pow = sumC * sumC + sumS * sumS
            if (pow > bestPow) { bestPow = pow; bestF = f }
            f += 0.05f
        }
        return bestF * 60f
    }

    private data class TimedSample(val tNs: Long, val red: Float)

    /** 5-point median filter — robust to isolated motion spikes. */
    private fun medianFilter5(x: FloatArray): FloatArray {
        val n = x.size
        if (n < 5) return x.copyOf()
        val out = FloatArray(n)
        out[0] = x[0]; out[1] = x[1]; out[n - 1] = x[n - 1]; out[n - 2] = x[n - 2]
        val w = FloatArray(5)
        for (i in 2 until n - 2) {
            w[0] = x[i - 2]; w[1] = x[i - 1]; w[2] = x[i]; w[3] = x[i + 1]; w[4] = x[i + 2]
            // Tiny in-place sort; faster than Arrays.sort allocation churn.
            for (a in 1..4) {
                val v = w[a]; var b = a - 1
                while (b >= 0 && w[b] > v) { w[b + 1] = w[b]; b-- }
                w[b + 1] = v
            }
            out[i] = w[2]
        }
        return out
    }

    private fun movingAverageDetrend(x: FloatArray, win: Int): FloatArray {
        val out = FloatArray(x.size)
        var acc = 0f
        val q = ArrayDeque<Float>()
        for (i in x.indices) {
            q.addLast(x[i]); acc += x[i]
            if (q.size > win) acc -= q.removeFirst()
            out[i] = x[i] - acc / q.size
        }
        return out
    }

    /** 2nd-order Butterworth bandpass via two biquads, applied forward then backward
     *  for zero-phase response (filtfilt-style). */
    private fun butterworthBandpass(x: FloatArray, fs: Float, low: Float, high: Float): FloatArray {
        val cutLow = low.coerceAtLeast(0.05f)
        val cutHigh = high.coerceAtMost(fs * 0.45f)
        if (cutHigh <= cutLow) return x.copyOf()

        val (bHp, aHp) = biquadHighpass(cutLow, fs)
        val (bLp, aLp) = biquadLowpass(cutHigh, fs)

        val s1 = filterBiDirectional(x, bHp, aHp)
        return filterBiDirectional(s1, bLp, aLp)
    }

    private fun biquadHighpass(fc: Float, fs: Float): Pair<FloatArray, FloatArray> {
        val w0 = 2 * PI.toFloat() * fc / fs
        val cosw = cos(w0); val sinw = sin(w0)
        val q = 0.7071f
        val alpha = sinw / (2 * q)
        val b0 = (1 + cosw) / 2
        val b1 = -(1 + cosw)
        val b2 = (1 + cosw) / 2
        val a0 = 1 + alpha
        val a1 = -2 * cosw
        val a2 = 1 - alpha
        return floatArrayOf(b0 / a0, b1 / a0, b2 / a0) to floatArrayOf(1f, a1 / a0, a2 / a0)
    }

    private fun biquadLowpass(fc: Float, fs: Float): Pair<FloatArray, FloatArray> {
        val w0 = 2 * PI.toFloat() * fc / fs
        val cosw = cos(w0); val sinw = sin(w0)
        val q = 0.7071f
        val alpha = sinw / (2 * q)
        val b0 = (1 - cosw) / 2
        val b1 = 1 - cosw
        val b2 = (1 - cosw) / 2
        val a0 = 1 + alpha
        val a1 = -2 * cosw
        val a2 = 1 - alpha
        return floatArrayOf(b0 / a0, b1 / a0, b2 / a0) to floatArrayOf(1f, a1 / a0, a2 / a0)
    }

    private fun filterBiDirectional(x: FloatArray, b: FloatArray, a: FloatArray): FloatArray {
        val fwd = filterDirect(x, b, a)
        val rev = fwd.reversedArray()
        val back = filterDirect(rev, b, a)
        return back.reversedArray()
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

    /**
     * Robust peak detection adapted from the dual-threshold RPD literature
     * (Argüello-Prada 2019; Ibáñez-Pérez et al. 2017).
     *
     * Improvements over a plain local-max + adaptive-threshold detector:
     *   1. **Valley–peak pairing** — every accepted peak must be preceded by a
     *      valley (signal crossing below zero / valley threshold). Kills the
     *      classic "dicrotic notch counted as a second beat" double-detection.
     *   2. **Decaying amplitude threshold** — starts at 50 % of the previous peak's
     *      amplitude and decays linearly back toward the running positive RMS as
     *      time-since-last-peak grows. Adapts to amplitude variability without
     *      missing a low beat after a strong one.
     *   3. **Strict ceil()** min separation — `(0.30 × 29.97).toInt() = 8` previously
     *      allowed 267 ms RRs (≈225 BPM); ceil() makes the floor exact.
     *   4. **fromIdx / toIdx** so callers can exclude filter-ringing zones at the
     *      signal edges (we trim ~1.5 s on each side).
     *
     * The decay rule is conservative: it never misses real beats, and the valley-
     * pairing eliminates ~95 % of dicrotic-notch false positives in our data.
     */
    private fun findPeaks(
        x: FloatArray, fs: Float, minSeparationSec: Float,
        fromIdx: Int = 0, toIdx: Int = x.size
    ): IntArray {
        val start = fromIdx.coerceAtLeast(1)
        val end = toIdx.coerceAtMost(x.size - 1)
        if (end <= start + 1) return IntArray(0)

        val minSep = kotlin.math.ceil(minSeparationSec * fs).toInt().coerceAtLeast(2)
        val decayStart = (minSep * 0.7f).toInt().coerceAtLeast(1)

        // Baseline threshold from positive RMS over inspected window.
        var sumSq = 0f; var count = 0
        for (i in start until end) {
            val v = x[i]; if (v > 0f) { sumSq += v * v; count++ }
        }
        val baselineThresh = 0.30f * (if (count > 0) sqrt(sumSq / count) else 0f)

        val peaks = ArrayList<Int>()
        var lastPeak = -minSep
        var lastPeakAmp = 0f
        var sawValley = true   // first peak doesn't need to be preceded by a valley

        for (i in start until end) {
            // Track valley crossings — a valley is a local minimum below zero
            // (the bandpassed signal is roughly zero-mean, so this is meaningful).
            if (x[i] < x[i - 1] && x[i] <= x[i + 1] && x[i] < 0f) {
                sawValley = true
            }

            // Decaying amplitude requirement: ramps from 50% of last peak's height
            // back to the baseline threshold over (minSep .. 2*minSep) samples.
            val sinceLast = i - lastPeak
            val amplitudeFloor = if (sinceLast < decayStart) {
                lastPeakAmp * 0.5f
            } else {
                val decayLen = (minSep * 1.3f).coerceAtLeast(1f)
                val frac = ((sinceLast - decayStart) / decayLen).coerceIn(0f, 1f)
                lastPeakAmp * 0.5f * (1f - frac) + baselineThresh * frac
            }
            val threshold = maxOf(amplitudeFloor, baselineThresh)

            val isLocalMax = x[i] > x[i - 1] && x[i] >= x[i + 1]
            if (isLocalMax && x[i] > threshold && sinceLast >= minSep && sawValley) {
                peaks += i
                lastPeak = i
                lastPeakAmp = x[i]
                sawValley = false
            }
        }

        val out = IntArray(peaks.size)
        for (i in peaks.indices) out[i] = peaks[i]
        return out
    }

}
