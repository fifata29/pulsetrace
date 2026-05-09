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
        val coverage: Float            // last-known fingertip coverage
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
            return Snapshot(0f, emptyList(), emptyList(), emptyList(), lastCoverage)
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

        // Detrend with moving average (~1.5 s).
        val maWin = (1.5f * fs).toInt().coerceAtLeast(5)
        val detrended = movingAverageDetrend(uniform, maWin)

        // Bandpass via cascaded biquads.
        val filtered = butterworthBandpass(detrended, fs, lowHz, highHz)

        // Peak detect on filtered signal.
        val peakIdx = findPeaks(filtered, fs, minSeparationSec = 0.30f)

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

        return Snapshot(fs, samples, peaks, rr, lastCoverage)
    }

    private data class TimedSample(val tNs: Long, val red: Float)

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

    /** Simple peak detection with adaptive prominence and minimum spacing. */
    private fun findPeaks(x: FloatArray, fs: Float, minSeparationSec: Float): IntArray {
        if (x.size < 3) return IntArray(0)
        val minSep = (minSeparationSec * fs).toInt().coerceAtLeast(1)

        // Adaptive threshold: 30% of recent positive RMS.
        var sumSq = 0f; var count = 0
        for (v in x) if (v > 0) { sumSq += v * v; count++ }
        val rmsPos = if (count > 0) sqrt(sumSq / count) else 0f
        val threshold = 0.30f * rmsPos

        val peaks = ArrayList<Int>()
        var lastPeak = -minSep
        for (i in 1 until x.size - 1) {
            if (x[i] > x[i - 1] && x[i] >= x[i + 1] && x[i] > threshold &&
                i - lastPeak >= minSep) {
                peaks += i
                lastPeak = i
            }
        }
        val out = IntArray(peaks.size)
        for (i in peaks.indices) out[i] = peaks[i]
        return out
    }

}
