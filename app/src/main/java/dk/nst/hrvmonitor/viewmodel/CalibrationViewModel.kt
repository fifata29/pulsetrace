package dk.nst.hrvmonitor.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dk.nst.hrvmonitor.data.CalibrationRecorder
import dk.nst.hrvmonitor.ppg.CalibrationAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CalibrationViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val isRecording: Boolean = false,
        val targetSec: Float = TARGET_DURATION_SEC,
        val elapsedSec: Float = 0f,
        val progress: Float = 0f,
        val sampleCount: Long = 0L,
        val sampleRateHz: Float = 0f,
        val centerLuma: Float = 0f,
        val sessionPath: String? = null,        // exposed once recording finishes
        val csvPath: String? = null,
        val gridCols: Int = GRID_COLS,
        val gridRows: Int = GRID_ROWS
    )

    private val recorder = CalibrationRecorder(application.applicationContext)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var startNs: Long = 0
    private var refreshJob: Job? = null
    @Volatile private var sampleCount: Long = 0L
    @Volatile private var firstSampleNs: Long = 0L
    @Volatile private var lastSampleNs: Long = 0L
    @Volatile private var latestCenterLuma: Float = 0f

    val analyzer = CalibrationAnalyzer(GRID_COLS, GRID_ROWS) { sample ->
        if (!_state.value.isRecording) return@CalibrationAnalyzer
        recorder.appendSample(sample)
        sampleCount++
        if (firstSampleNs == 0L) firstSampleNs = sample.timestampNs
        lastSampleNs = sample.timestampNs
        latestCenterLuma = sample.centerLuma
    }

    fun start() {
        if (_state.value.isRecording) return
        sampleCount = 0L
        firstSampleNs = 0L
        lastSampleNs = 0L
        latestCenterLuma = 0f
        startNs = System.nanoTime()

        val session = recorder.start(
            gridCols = GRID_COLS,
            gridRows = GRID_ROWS,
            targetDurationSec = TARGET_DURATION_SEC,
            aeLocked = true
        )

        _state.value = UiState(
            isRecording = true,
            sessionPath = session.dir.absolutePath,
            csvPath = session.csv.absolutePath
        )

        refreshJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                val elapsed = (System.nanoTime() - startNs) / 1e9f
                val durSec = if (firstSampleNs > 0L && lastSampleNs > firstSampleNs)
                    (lastSampleNs - firstSampleNs) / 1e9f else 0f
                val fs = if (durSec > 0f && sampleCount > 1L)
                    (sampleCount - 1) / durSec else 0f

                _state.value = _state.value.copy(
                    elapsedSec = elapsed,
                    progress = (elapsed / TARGET_DURATION_SEC).coerceIn(0f, 1f),
                    sampleCount = sampleCount,
                    sampleRateHz = fs,
                    centerLuma = latestCenterLuma
                )

                if (elapsed >= TARGET_DURATION_SEC) {
                    finalize()
                    break
                }
                delay(REFRESH_MS)
            }
        }
    }

    fun stop() {
        if (!_state.value.isRecording) return
        finalize()
    }

    private fun finalize() {
        refreshJob?.cancel()
        refreshJob = null
        val session = recorder.stop()
        _state.value = _state.value.copy(
            isRecording = false,
            sessionPath = session?.dir?.absolutePath,
            csvPath = session?.csv?.absolutePath
        )
    }

    override fun onCleared() {
        super.onCleared()
        refreshJob?.cancel()
        recorder.stop()
    }

    companion object {
        const val TARGET_DURATION_SEC = 120f
        private const val GRID_COLS = 16
        private const val GRID_ROWS = 12
        private const val REFRESH_MS = 100L
    }
}
