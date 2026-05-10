package dk.nst.hrvmonitor.ppg

import dk.nst.hrvmonitor.viewmodel.MeasurementViewModel
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Confidence score for a completed measurement, on a 0–100 scale split across five
 * independent components. Each component is bounded so a low score in one area can't
 * fully drag the others down — the goal is honest signalling, not punishment.
 *
 *  - **BPM agreement** (30): peak-derived BPM vs spectral (FFT) BPM. Disagreement
 *    means the peak detector is finding the wrong rhythm.
 *  - **Beat acceptance** (25): fraction of detected peaks that survived the median-RR
 *    outlier filter. < 80 % = noisy peak detection.
 *  - **RR variability sanity** (15): coefficient of variation in RR. Either too low
 *    (suspicious — usually means filter rejected real beats) or too high (noisy).
 *  - **Sample rate** (15): fps directly limits HRV resolution.
 *  - **Coverage / completeness** (15): sustained finger contact, no timeout.
 */
object QualityScorer {

    enum class Tier(val label: String, val advice: String) {
        Excellent("Excellent", "Use this number with confidence"),
        Good("Good", "Reliable for trend tracking"),
        Fair("Fair", "Indicative; consider re-measuring"),
        Low("Low", "Don't trust this reading; retry")
    }

    data class Component(val name: String, val score: Int, val maxScore: Int, val note: String)

    data class Score(
        val total: Int,
        val tier: Tier,
        val components: List<Component>
    )

    fun score(report: MeasurementViewModel.Report): Score = scoreFromInputs(
        bpm = report.metrics.bpm,
        spectralBpm = report.spectralBpm,
        rrMs = report.rrMs,
        sampleRateHz = report.sampleRateHz,
        coverage = report.coverage,
        validBeats = report.metrics.validBeats,
        totalBeats = report.metrics.totalBeats,
        timedOut = report.timedOut,
        perfusionIndex = 0f
    )

    fun scoreFromInputs(
        bpm: Float?,
        spectralBpm: Float,
        rrMs: List<Float>,
        sampleRateHz: Float,
        coverage: Float,
        validBeats: Int,
        totalBeats: Int,
        timedOut: Boolean,
        // AC / DC, expressed as a percentage. The hardware-derived perfusion
        // index from any clinical pulse oximeter — the strongest single SQI
        // in the literature (Elgendi 2016, Bioengineering). For our pipeline:
        // AC = std of the bandpassed signal, DC = mean of the raw resampled
        // channel value over the same window.
        perfusionIndex: Float = 0f
    ): Score {
        val parts = mutableListOf<Component>()

        // 1) BPM agreement (25) ---------------------------------------------
        val (agreementScore, agreementNote) = if (bpm != null && spectralBpm > 1f) {
            val rel = abs(bpm - spectralBpm) / bpm
            val s = (25f * (1f - (rel / 0.30f).coerceIn(0f, 1f))).roundToInt()
            s to "${"%.1f".format(bpm)} vs ${"%.1f".format(spectralBpm)} BPM (Δ ${"%.0f".format(rel * 100)}%)"
        } else {
            0 to "Not enough beats to compare"
        }
        parts += Component("BPM agreement", agreementScore, 25, agreementNote)

        // 2) Beat acceptance (20) -------------------------------------------
        val (acceptScore, acceptNote) = if (totalBeats > 0) {
            val ratio = validBeats.toFloat() / totalBeats
            (20 * ratio).roundToInt() to "$validBeats / $totalBeats beats accepted"
        } else 0 to "No beats detected"
        parts += Component("Beat acceptance", acceptScore, 20, acceptNote)

        // 3) RR variability sanity (15) -------------------------------------
        val cv = if (rrMs.size > 2) {
            val mean = rrMs.average()
            val sd = sqrt(rrMs.map { (it - mean).pow(2) }.average())
            (sd / mean).toFloat()
        } else 0f
        val cvScore = when {
            rrMs.size < 3 -> 0
            cv < 0.005f -> 0
            cv > 0.30f -> 0
            cv < 0.02f -> 7
            cv < 0.20f -> 15
            else -> (15 * (1f - (cv - 0.20f) / 0.10f).coerceIn(0f, 1f)).roundToInt()
        }
        parts += Component(
            "RR variability sanity", cvScore, 15,
            "CV ${"%.1f".format(cv * 100)}%"
        )

        // 4) Sample rate (15) -----------------------------------------------
        val fsScore = (15f * ((sampleRateHz - 10f) / 18f).coerceIn(0f, 1f)).roundToInt()
        parts += Component(
            "Sample rate", fsScore, 15,
            "${"%.0f".format(sampleRateHz)} Hz"
        )

        // 5) Coverage + completion (15) -------------------------------------
        val cov = coverage.coerceIn(0f, 1f)
        val covScore = if (timedOut) (5f * cov).roundToInt() else (15f * cov).roundToInt()
        parts += Component(
            "Coverage", covScore, 15,
            if (timedOut) "Timed out · ${(cov * 100).roundToInt()}% contact"
            else "${(cov * 100).roundToInt()}% contact"
        )

        // 6) Perfusion index (10) -------------------------------------------
        // AC/DC ratio of the displayed channel. Gold-standard hardware SQI used
        // on every clinical pulse oximeter. 0.5 % = poor, 1 % = OK, 3 %+ = strong.
        val piScore = when {
            perfusionIndex >= 3f -> 10
            perfusionIndex >= 1f -> (5 + 5f * (perfusionIndex - 1f) / 2f).roundToInt().coerceIn(5, 10)
            perfusionIndex >= 0.3f -> (5f * (perfusionIndex - 0.3f) / 0.7f).roundToInt().coerceIn(0, 5)
            else -> 0
        }
        parts += Component(
            "Perfusion index", piScore, 10,
            "${"%.2f".format(perfusionIndex)}%  (AC/DC)"
        )

        val totalScore = parts.sumOf { it.score }.coerceIn(0, 100)
        val tier = when {
            totalScore >= 85 -> Tier.Excellent
            totalScore >= 70 -> Tier.Good
            totalScore >= 50 -> Tier.Fair
            else -> Tier.Low
        }
        return Score(totalScore, tier, parts)
    }
}
