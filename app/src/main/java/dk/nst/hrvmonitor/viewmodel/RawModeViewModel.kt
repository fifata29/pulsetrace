package dk.nst.hrvmonitor.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dk.nst.hrvmonitor.data.RawRecorder
import dk.nst.hrvmonitor.ppg.RawTileAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RawModeViewModel(application: Application) : AndroidViewModel(application) {

    enum class Site { Fingertip, Forearm, Wrist, Other }

    data class UiState(
        val isRecording: Boolean = false,
        val site: Site = Site.Forearm,
        val targetSec: Float = DEFAULT_DURATION_SEC,
        val targetFps: Int = TARGET_FPS,
        val elapsedSec: Float = 0f,
        val progress: Float = 0f,
        val sampleCount: Long = 0L,
        val sampleRateHz: Float = 0f,
        val centerLumaR: Float = 0f,
        val centerLumaG: Float = 0f,
        val centerLumaB: Float = 0f,
        val sessionPath: String? = null,
        val csvPath: String? = null,
        val gridCols: Int = GRID_COLS,
        val gridRows: Int = GRID_ROWS
    )

    private val recorder = RawRecorder(application.applicationContext)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var startNs: Long = 0
    private var refreshJob: Job? = null
    @Volatile private var sampleCount: Long = 0L
    @Volatile private var firstSampleNs: Long = 0L
    @Volatile private var lastSampleNs: Long = 0L
    @Volatile private var latestR: Float = 0f
    @Volatile private var latestG: Float = 0f
    @Volatile private var latestB: Float = 0f

    val analyzer = RawTileAnalyzer(GRID_COLS, GRID_ROWS) { sample ->
        if (!_state.value.isRecording) return@RawTileAnalyzer
        recorder.appendSample(sample)
        sampleCount++
        if (firstSampleNs == 0L) firstSampleNs = sample.timestampNs
        lastSampleNs = sample.timestampNs
        val centerIdx = (GRID_ROWS / 2) * GRID_COLS + (GRID_COLS / 2)
        latestR = sample.rMean.getOrNull(centerIdx) ?: 0f
        latestG = sample.gMean.getOrNull(centerIdx) ?: 0f
        latestB = sample.bMean.getOrNull(centerIdx) ?: 0f
    }

    fun setSite(site: Site) {
        if (_state.value.isRecording) return
        _state.value = _state.value.copy(site = site)
    }

    fun setDurationSec(sec: Float) {
        if (_state.value.isRecording) return
        _state.value = _state.value.copy(targetSec = sec.coerceIn(30f, 300f))
    }

    fun start(notes: String? = null) {
        if (_state.value.isRecording) return
        sampleCount = 0L
        firstSampleNs = 0L
        lastSampleNs = 0L
        latestR = 0f; latestG = 0f; latestB = 0f
        startNs = System.nanoTime()

        val target = _state.value.targetSec
        val site = _state.value.site
        val session = recorder.start(
            gridCols = GRID_COLS,
            gridRows = GRID_ROWS,
            targetFps = TARGET_FPS,
            targetDurationSec = target,
            site = site.name.lowercase(),
            notes = notes
        )

        _state.value = _state.value.copy(
            isRecording = true,
            elapsedSec = 0f,
            progress = 0f,
            sampleCount = 0L,
            sampleRateHz = 0f,
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

                val s = _state.value
                _state.value = s.copy(
                    elapsedSec = elapsed,
                    progress = (elapsed / s.targetSec).coerceIn(0f, 1f),
                    sampleCount = sampleCount,
                    sampleRateHz = fs,
                    centerLumaR = latestR,
                    centerLumaG = latestG,
                    centerLumaB = latestB
                )

                if (elapsed >= s.targetSec) {
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
        const val DEFAULT_DURATION_SEC = 120f
        const val TARGET_FPS = 60
        private const val GRID_COLS = 16
        private const val GRID_ROWS = 12
        private const val REFRESH_MS = 100L
    }
}
