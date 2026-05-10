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
import kotlin.math.sqrt

class RawModeViewModel(application: Application) : AndroidViewModel(application) {

    enum class Site { Fingertip, Forearm, Wrist, Other }

    /**
     * Sweep mode walks the user through a fixed pressure protocol so the AC-vs-DC
     * curve can be analysed offline. Layout (s since Start):
     *   0–5   PRESS FIRM
     *   5–10  RELEASE
     *  10–25  Slowly vary pressure: light ↔ firm
     *  25+   Hold at the strongest-pulse level
     */
    data class UiState(
        val isRecording: Boolean = false,
        val site: Site = Site.Forearm,
        val targetSec: Float = DEFAULT_DURATION_SEC,
        val targetFps: Int = TARGET_FPS,
        val sweepMode: Boolean = false,
        val sweepPrompt: String = "",
        val elapsedSec: Float = 0f,
        val progress: Float = 0f,
        val sampleCount: Long = 0L,
        val sampleRateHz: Float = 0f,
        val centerLumaR: Float = 0f,
        val centerLumaG: Float = 0f,
        val centerLumaB: Float = 0f,
        // Position-scout: per-tile rolling AC amplitude (std of last ~2 s of
        // green channel). Row-major over (row, col); length = gridCols·gridRows.
        // The screen overlays this on the camera preview as a heat-map so the
        // user can hunt the optimal lateral position before tapping Start.
        val tileAc: FloatArray = FloatArray(GRID_COLS * GRID_ROWS),
        val bestTileRow: Int = -1,
        val bestTileCol: Int = -1,
        val bestTileAc: Float = 0f,
        val bestTileAcMaxSeen: Float = 0f,
        val sessionPath: String? = null,
        val csvPath: String? = null,
        val gridCols: Int = GRID_COLS,
        val gridRows: Int = GRID_ROWS
    ) {
        // Default array-equality on data classes is identity; we don't compare
        // two UiStates for equality anywhere that would care, so leave it.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private val recorder = RawRecorder(application.applicationContext)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { startRefreshLoop() }

    private var startNs: Long = 0
    private var refreshJob: Job? = null
    @Volatile private var sampleCount: Long = 0L
    @Volatile private var firstSampleNs: Long = 0L
    @Volatile private var lastSampleNs: Long = 0L
    @Volatile private var latestR: Float = 0f
    @Volatile private var latestG: Float = 0f
    @Volatile private var latestB: Float = 0f
    @Volatile private var bestTileAcMaxSeen: Float = 0f

    // Per-tile ring buffer of the green-channel mean. Each tile gets its own
    // RING_BUFFER_SIZE-element ring. Total memory: 192 × 128 × 4 B ≈ 96 KB.
    private val ringPerTile: Array<FloatArray> =
        Array(GRID_COLS * GRID_ROWS) { FloatArray(RING_BUFFER_SIZE) }
    @Volatile private var ringWritten: Long = 0L

    val analyzer = RawTileAnalyzer(GRID_COLS, GRID_ROWS) { sample ->
        val n = sample.gMean.size
        val slot = (ringWritten % RING_BUFFER_SIZE).toInt()
        for (i in 0 until n) ringPerTile[i][slot] = sample.gMean[i]
        ringWritten++

        val centerIdx = (GRID_ROWS / 2) * GRID_COLS + (GRID_COLS / 2)
        latestR = sample.rMean.getOrNull(centerIdx) ?: 0f
        latestG = sample.gMean.getOrNull(centerIdx) ?: 0f
        latestB = sample.bMean.getOrNull(centerIdx) ?: 0f

        if (_state.value.isRecording) {
            recorder.appendSample(sample)
            sampleCount++
            if (firstSampleNs == 0L) firstSampleNs = sample.timestampNs
            lastSampleNs = sample.timestampNs
        }
    }

    /** Returns (tileAc array, bestIdx, bestAc). tileAc[i] is the std of the
     *  recent green-channel values for tile i, used as a fast pulse-amplitude
     *  proxy. */
    private fun computeTileAcs(): Triple<FloatArray, Int, Float> {
        val nTiles = GRID_COLS * GRID_ROWS
        val out = FloatArray(nTiles)
        val have = if (ringWritten >= RING_BUFFER_SIZE.toLong())
            RING_BUFFER_SIZE else ringWritten.toInt()
        if (have < 8) return Triple(out, -1, 0f)
        var bestIdx = -1
        var bestAc = 0f
        for (i in 0 until nTiles) {
            val ring = ringPerTile[i]
            var sum = 0.0
            var sumSq = 0.0
            for (j in 0 until have) {
                val v = ring[j]
                sum += v
                sumSq += v * v
            }
            val mean = sum / have
            val variance = (sumSq / have - mean * mean).coerceAtLeast(0.0)
            val ac = sqrt(variance).toFloat()
            out[i] = ac
            if (ac > bestAc) { bestAc = ac; bestIdx = i }
        }
        return Triple(out, bestIdx, bestAc)
    }

    private fun sweepPromptFor(elapsed: Float, sweepMode: Boolean): String {
        if (!sweepMode) return ""
        return when {
            elapsed < SWEEP_PRESS_END -> "PRESS FIRM — push the lens hard into the skin"
            elapsed < SWEEP_RELEASE_END -> "RELEASE — barely let the lens touch the skin"
            elapsed < SWEEP_VARY_END -> "Slowly vary pressure: light ↔ firm ↔ light"
            else -> "Hold at the pressure where the pulse bar was strongest"
        }
    }

    fun setSite(site: Site) {
        if (_state.value.isRecording) return
        _state.value = _state.value.copy(site = site)
    }

    fun setDurationSec(sec: Float) {
        if (_state.value.isRecording) return
        _state.value = _state.value.copy(targetSec = sec.coerceIn(30f, 300f))
    }

    fun setSweepMode(on: Boolean) {
        if (_state.value.isRecording) return
        _state.value = _state.value.copy(
            sweepMode = on,
            targetSec = if (on) maxOf(_state.value.targetSec, SWEEP_MIN_DURATION) else _state.value.targetSec
        )
    }

    fun start(notes: String? = null) {
        if (_state.value.isRecording) return
        sampleCount = 0L
        firstSampleNs = 0L
        lastSampleNs = 0L
        latestR = 0f; latestG = 0f; latestB = 0f
        bestTileAcMaxSeen = 0f
        startNs = System.nanoTime()

        val target = _state.value.targetSec
        val site = _state.value.site
        val sweep = _state.value.sweepMode
        val combinedNotes = listOfNotNull(
            notes?.takeIf { it.isNotBlank() },
            if (sweep) "sweep_protocol=press5_release5_vary15_hold" else null
        ).joinToString(" | ").ifBlank { null }
        val session = recorder.start(
            gridCols = GRID_COLS,
            gridRows = GRID_ROWS,
            targetFps = TARGET_FPS,
            targetDurationSec = target,
            site = site.name.lowercase(),
            notes = combinedNotes
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
    }

    private fun startRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                val s = _state.value
                val (tileAc, bestIdx, bestAc) = computeTileAcs()
                if (bestAc > bestTileAcMaxSeen) bestTileAcMaxSeen = bestAc
                val bestRow = if (bestIdx >= 0) bestIdx / GRID_COLS else -1
                val bestCol = if (bestIdx >= 0) bestIdx % GRID_COLS else -1

                if (s.isRecording) {
                    val elapsed = (System.nanoTime() - startNs) / 1e9f
                    val durSec = if (firstSampleNs > 0L && lastSampleNs > firstSampleNs)
                        (lastSampleNs - firstSampleNs) / 1e9f else 0f
                    val fs = if (durSec > 0f && sampleCount > 1L)
                        (sampleCount - 1) / durSec else 0f
                    _state.value = s.copy(
                        elapsedSec = elapsed,
                        progress = (elapsed / s.targetSec).coerceIn(0f, 1f),
                        sampleCount = sampleCount,
                        sampleRateHz = fs,
                        centerLumaR = latestR,
                        centerLumaG = latestG,
                        centerLumaB = latestB,
                        tileAc = tileAc,
                        bestTileRow = bestRow,
                        bestTileCol = bestCol,
                        bestTileAc = bestAc,
                        bestTileAcMaxSeen = bestTileAcMaxSeen,
                        sweepPrompt = sweepPromptFor(elapsed, s.sweepMode)
                    )
                    if (elapsed >= s.targetSec) finalize()
                } else {
                    _state.value = s.copy(
                        centerLumaR = latestR,
                        centerLumaG = latestG,
                        centerLumaB = latestB,
                        tileAc = tileAc,
                        bestTileRow = bestRow,
                        bestTileCol = bestCol,
                        bestTileAc = bestAc,
                        bestTileAcMaxSeen = bestTileAcMaxSeen
                    )
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
        val session = recorder.stop()
        _state.value = _state.value.copy(
            isRecording = false,
            sweepPrompt = "",
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
        const val GRID_COLS = 16
        const val GRID_ROWS = 12
        private const val REFRESH_MS = 100L
        private const val RING_BUFFER_SIZE = 128 // ~2 s @ 60 Hz

        const val SWEEP_PRESS_END = 5f
        const val SWEEP_RELEASE_END = 10f
        const val SWEEP_VARY_END = 25f
        const val SWEEP_MIN_DURATION = 60f
    }
}
