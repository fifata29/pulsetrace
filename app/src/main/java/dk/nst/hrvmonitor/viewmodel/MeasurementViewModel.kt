package dk.nst.hrvmonitor.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

class MeasurementViewModel : ViewModel() {

    data class UiState(
        val isMeasuring: Boolean = false,
        val sampleRateHz: Float = 0f,
        val coverage: Float = 0f,
        val signal: List<SignalProcessor.Sample> = emptyList(),
        val peaks: List<SignalProcessor.Peak> = emptyList(),
        val rrMs: List<Float> = emptyList(),
        val metrics: HrvCalculator.Metrics = HrvCalculator.Metrics(null, null, null, null, 0, 0),
        val elapsedSec: Float = 0f
    )

    private val processor = SignalProcessor(windowSeconds = 30f)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private var startNs: Long = 0

    val analyzer = PpgAnalyzer { sample ->
        if (!_state.value.isMeasuring) return@PpgAnalyzer
        processor.addSample(sample.timestampNs, sample.red, sample.coverage)
    }

    fun start() {
        if (_state.value.isMeasuring) return
        processor.reset()
        startNs = System.nanoTime()
        _state.value = UiState(isMeasuring = true)

        refreshJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                val snap = processor.snapshot()
                val metrics = HrvCalculator.compute(snap.rrMs)
                val elapsed = (System.nanoTime() - startNs) / 1e9f
                _state.value = _state.value.copy(
                    sampleRateHz = snap.sampleRateHz,
                    coverage = snap.coverage,
                    signal = snap.samples,
                    peaks = snap.peaks,
                    rrMs = snap.rrMs,
                    metrics = metrics,
                    elapsedSec = elapsed
                )
                delay(REFRESH_MS)
            }
        }
    }

    fun stop() {
        refreshJob?.cancel()
        refreshJob = null
        _state.value = _state.value.copy(isMeasuring = false)
    }

    override fun onCleared() {
        super.onCleared()
        refreshJob?.cancel()
    }

    companion object {
        private const val REFRESH_MS = 100L
    }
}
