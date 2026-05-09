package dk.nst.hrvmonitor.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dk.nst.hrvmonitor.data.SessionRecorder
import dk.nst.hrvmonitor.ppg.HrvCalculator
import dk.nst.hrvmonitor.ppg.PpgAnalyzer
import dk.nst.hrvmonitor.ppg.SignalProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the measurement session. Quality-gated budget:
 *   - The progress bar accumulates only when the fingertip coverage is "good"
 *     (coverage ≥ [GOOD_COVERAGE]). Bad-quality samples don't count toward
 *     [TARGET_GOOD_SEC] and don't enter the live signal processor.
 *   - All samples (good or bad) are still written to samples.csv so we keep a
 *     full raw record for offline analysis.
 *   - Wall-clock is capped at [MAX_WALL_CLOCK_SEC]; if good-time hasn't reached
 *     the target by then, the session ends with [Report.timedOut] = true.
 */
class MeasurementViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val isMeasuring: Boolean = false,
        val targetGoodSec: Float = TARGET_GOOD_SEC,
        val elapsedSec: Float = 0f,
        val goodSec: Float = 0f,
        val progress: Float = 0f,             // 0..1 of goodSec / targetGoodSec
        val isGoodSignal: Boolean = false,    // current frame quality
        val sampleRateHz: Float = 0f,
        val coverage: Float = 0f,             // most recent coverage
        val signal: List<SignalProcessor.Sample> = emptyList(),
        val peaks: List<SignalProcessor.Peak> = emptyList(),
        val rrMs: List<Float> = emptyList(),
        val metrics: HrvCalculator.Metrics = HrvCalculator.Metrics(null, null, null, null, 0, 0),
        val report: Report? = null
    )

    /** Frozen end-of-measurement summary for the report card. Null while idle/measuring. */
    data class Report(
        val durationSec: Float,        // wall-clock duration
        val goodSec: Float,            // accumulated good-quality time
        val targetGoodSec: Float,
        val timedOut: Boolean,
        val sampleRateHz: Float,
        val coverage: Float,
        val metrics: HrvCalculator.Metrics,
        val rrMs: List<Float>,
        val peaks: List<SignalProcessor.Peak>,
        val sessionPath: String?
    )

    private val processor = SignalProcessor(windowSeconds = 60f)
    private val recorder = SessionRecorder(application.applicationContext)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private var startNs: Long = 0
    private var goodNs: Long = 0L
    private var lastGoodTNs: Long = 0L

    @Volatile private var latestCoverage: Float = 0f

    val analyzer = PpgAnalyzer { sample ->
        if (!_state.value.isMeasuring) return@PpgAnalyzer

        latestCoverage = sample.coverage

        // Always log raw frame to CSV for offline analysis.
        recorder.appendSample(
            SessionRecorder.SampleRow(
                tNs = sample.timestampNs,
                red = sample.red,
                luma = sample.luma,
                coverage = sample.coverage
            )
        )

        if (sample.coverage >= GOOD_COVERAGE) {
            val ts = sample.timestampNs
            val gap = ts - lastGoodTNs
            if (lastGoodTNs > 0L && gap in 1L..MAX_GOOD_GAP_NS) {
                goodNs += gap
            }
            lastGoodTNs = ts
            processor.addSample(ts, sample.red, sample.coverage)
        } else {
            // Reset gap tracking — next good sample starts a fresh continuous run.
            lastGoodTNs = 0L
        }
    }

    fun start() {
        if (_state.value.isMeasuring) return
        processor.reset()
        startNs = System.nanoTime()
        goodNs = 0L
        lastGoodTNs = 0L
        latestCoverage = 0f
        recorder.start()
        _state.value = UiState(isMeasuring = true)

        refreshJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                val snap = processor.snapshot()
                val metrics = HrvCalculator.compute(snap.rrMs)
                val elapsed = (System.nanoTime() - startNs) / 1e9f
                val goodSec = goodNs / 1e9f
                val progress = (goodSec / TARGET_GOOD_SEC).coerceIn(0f, 1f)
                val coverageNow = latestCoverage

                _state.value = _state.value.copy(
                    sampleRateHz = snap.sampleRateHz,
                    coverage = coverageNow,
                    isGoodSignal = coverageNow >= GOOD_COVERAGE,
                    signal = snap.samples,
                    peaks = snap.peaks,
                    rrMs = snap.rrMs,
                    metrics = metrics,
                    elapsedSec = elapsed,
                    goodSec = goodSec,
                    progress = progress
                )

                if (goodSec >= TARGET_GOOD_SEC) {
                    finalizeMeasurement(elapsed, goodSec, timedOut = false)
                    break
                }
                if (elapsed >= MAX_WALL_CLOCK_SEC) {
                    finalizeMeasurement(elapsed, goodSec, timedOut = true)
                    break
                }
                delay(REFRESH_MS)
            }
        }
    }

    fun stop() {
        if (!_state.value.isMeasuring) return
        val elapsed = (System.nanoTime() - startNs) / 1e9f
        val goodSec = goodNs / 1e9f
        finalizeMeasurement(elapsed, goodSec, timedOut = goodSec < TARGET_GOOD_SEC)
    }

    fun dismissReport() {
        _state.value = _state.value.copy(report = null)
    }

    private fun finalizeMeasurement(elapsed: Float, goodSec: Float, timedOut: Boolean) {
        refreshJob?.cancel()
        refreshJob = null

        val snap = processor.snapshot()
        val metrics = HrvCalculator.compute(snap.rrMs)

        val session = recorder.stop(
            durationSec = elapsed,
            sampleRateHz = snap.sampleRateHz,
            coverage = snap.coverage,
            signal = snap.samples,
            peaks = snap.peaks,
            rrMs = snap.rrMs,
            metrics = metrics
        )

        val report = Report(
            durationSec = elapsed,
            goodSec = goodSec,
            targetGoodSec = TARGET_GOOD_SEC,
            timedOut = timedOut,
            sampleRateHz = snap.sampleRateHz,
            coverage = snap.coverage,
            metrics = metrics,
            rrMs = snap.rrMs,
            peaks = snap.peaks,
            sessionPath = session?.dir?.absolutePath
        )

        _state.value = _state.value.copy(
            isMeasuring = false,
            elapsedSec = elapsed,
            goodSec = goodSec,
            progress = (goodSec / TARGET_GOOD_SEC).coerceIn(0f, 1f),
            metrics = metrics,
            rrMs = snap.rrMs,
            peaks = snap.peaks,
            signal = snap.samples,
            report = report
        )
    }

    override fun onCleared() {
        super.onCleared()
        refreshJob?.cancel()
        try {
            recorder.stop(0f, 0f, 0f, emptyList(), emptyList(), emptyList(),
                HrvCalculator.Metrics(null, null, null, null, 0, 0))
        } catch (_: Throwable) {}
    }

    companion object {
        const val TARGET_GOOD_SEC = 50f
        private const val MAX_WALL_CLOCK_SEC = 180f
        private const val GOOD_COVERAGE = 0.90f
        // Allow up to ~7 dropped frames (at 30 fps) inside a "continuous good" run.
        private const val MAX_GOOD_GAP_NS = 250_000_000L
        private const val REFRESH_MS = 100L
    }
}
