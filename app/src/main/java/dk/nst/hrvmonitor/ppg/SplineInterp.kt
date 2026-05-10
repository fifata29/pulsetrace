package dk.nst.hrvmonitor.ppg

/**
 * Catmull-Rom interpolation for uniform-spaced PPG signals. Used to upsample 30 Hz
 * captures to ~120 Hz so fiducial points (dicrotic notch, foot, peaks) can be
 * located with sub-frame precision before computing morphology metrics.
 *
 * Why Catmull-Rom over natural cubic splines: same C1 continuity, comparable
 * curvature accuracy on smooth signals like PPG, but trivially local (4 points)
 * — no global system to solve, no end-effect correction. Same idea as the
 * cubic-spline upsampling validated in the smartphone-PPG literature.
 */
object SplineInterp {

    /**
     * Upsample [x] by integer [factor]. Returns an array of length
     * `(x.size - 1) * factor + 1`. The original samples are preserved exactly
     * (output[i*factor] == x[i]).
     */
    fun upsample(x: FloatArray, factor: Int): FloatArray {
        val n = x.size
        if (n < 2 || factor <= 1) return x.copyOf()
        val out = FloatArray((n - 1) * factor + 1)
        for (i in 0 until n - 1) {
            val p0 = if (i == 0) x[i] else x[i - 1]
            val p1 = x[i]
            val p2 = x[i + 1]
            val p3 = if (i + 2 < n) x[i + 2] else x[i + 1]
            for (j in 0 until factor) {
                val t = j.toFloat() / factor
                out[i * factor + j] = catmullRom(p0, p1, p2, p3, t)
            }
        }
        out[out.size - 1] = x[n - 1]
        return out
    }

    /** Centripetal Catmull-Rom basis (uniform parameterisation). */
    private fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * (
            2f * p1 +
            (p2 - p0) * t +
            (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 +
            (-p0 + 3f * p1 - 3f * p2 + p3) * t3
        )
    }
}
