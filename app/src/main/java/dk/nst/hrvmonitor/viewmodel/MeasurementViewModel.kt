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

class MeasurementViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val isMeasuring: Boolean = false,
        val durationSec: Float = TARGET_DURATION_SEC,
        val elapsedSec: Float = 0f,
        val progress: Float = 0f,             // 0..1
        val sampleRateHz: Float = 0f,
        val coverage: Float = 0f,
        val signal: List<SignalProcessor.Sample> = emptyList(),
        val peaks: List<SignalProcessor.Peak> = emptyList(),
        val rrMs: List<Float> = emptyList(),
        val metrics: HrvCalculator.Metrics = HrvCalculator.Metrics(null, null, null, null, 0, 0),
        val report: Report? = null
    )

    /** Frozen end-of-measurement summary for the report card. Null while idle/measuring. */
    data class Report(
        val durationSec: Float,
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
    private var currentSession: SessionRecorder.Session? = null

    val analyzer = PpgAnalyzer { sample ->
        if (!_state.value.isMeasuring) return@PpgAnalyzer
        processor.addSample(sample.timestampNs, sample.red, sample.coverage)
        recorder.appendSample(
            SessionRecorder.SampleRow(
                tNs = sample.timestampNs,
                red = sample.red,
                luma = sample.luma,
                coverage = sample.coverage
            )
        )
    }

    fun start() {
        if (_state.value.isMeasuring) return
        processor.reset()
        startNs = System.nanoTime()
        currentSession = recorder.start()
        _state.value = UiState(isMeasuring = true)

        refreshJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                val snap = processor.snapshot()
                val metrics = HrvCalculator.compute(snap.rrMs)
                val elapsed = (System.nanoTime() - startNs) / 1e9f
                val progress = (elapsed / TARGET_DURATION_SEC).coerceIn(0f, 1f)

                _state.value = _state.value.copy(
                    sampleRateHz = snap.sampleRateHz,
                    coverage = snap.coverage,
                    signal = snap.samples,
                    peaks = snap.peaks,
                    rrMs = snap.rrMs,
                    metrics = metrics,
                    elapsedSec = elapsed,
                    progress = progress
                )
                if (elapsed >= TARGET_DURATION_SEC) {
                    finalizeMeasurement(elapsed)
                    break
                }
                delay(REFRESH_MS)
            }
        }
    }

    fun stop() {
        if (!_state.value.isMeasuring) return
        val elapsed = (System.nanoTime() - startNs) / 1e9f
        finalizeMeasurement(elapsed)
    }

    fun dismissReport() {
        _state.value = _state.value.copy(report = null)
    }

    private fun finalizeMeasurement(elapsed: Float) {
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
        currentSession = null

        val report = Report(
            durationSec = elapsed,
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
            progress = (elapsed / TARGET_DURATION_SEC).coerceIn(0f, 1f),
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
        try { recorder.stop(0f, 0f, 0f, emptyList(), emptyList(), emptyList(),
            HrvCalculator.Metrics(null, null, null, null, 0, 0)) } catch (_: Throwable) {}
    }

    companion object {
        const val TARGET_DURATION_SEC = 30f
        private const val REFRESH_MS = 100L
    }
}
