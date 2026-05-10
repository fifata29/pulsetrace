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
     * Sweep mode walks the user through a fixed pressure protocol so we can
     * compute their personal "press 0–100" range and find the AC-peak optimum
     * offline. Layout (in seconds since Start):
     *   0–5   PRESS FIRM
     *   5–10  RELEASE
     *  10–25  Slowly vary pressure: light ↔ firm
     *  25+    Hold at the level that gave the strongest pulse
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
        // Live pulse-strength + pressure indicators driven by median G across the
        // 16×12 tile grid. Pulse strength is the std of detrended median-G over
        // the last ~2 s. Pressure fraction maps the current DC into the [min,max]
        // range observed since the screen opened (or since Start in sweep mode).
        val pulseStrengthG: Float = 0f,
        val pulseStrengthMaxSeen: Float = 0f,
        val medianDcG: Float = 0f,
        val sessionMinDcG: Float = 0f,
        val sessionMaxDcG: Float = 0f,
        val pressureFrac: Float = 0f,
        val sessionPath: String? = null,
        val csvPath: String? = null,
        val gridCols: Int = GRID_COLS,
        val gridRows: Int = GRID_ROWS
    )

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

    // Ring buffer of recent median-G values across the whole tile grid (live
    // feedback for pulse strength). Median is robust to dark/saturated tiles.
    private val ringSize = RING_BUFFER_SIZE
    private val ringG = FloatArray(ringSize)
    @Volatile private var ringWritten: Long = 0L
    @Volatile private var latestMedianG: Float = 0f
    @Volatile private var sessionMinG: Float = Float.MAX_VALUE
    @Volatile private var sessionMaxG: Float = 0f
    @Volatile private var pulseStrengthMaxSeen: Float = 0f
    private val sortScratch = FloatArray(GRID_COLS * GRID_ROWS)

    val analyzer = RawTileAnalyzer(GRID_COLS, GRID_ROWS) { sample ->
        // Live indicators run continuously while the screen is open so the user
        // can hunt for the pressure sweet-spot before tapping Start.
        val n = sample.gMean.size
        System.arraycopy(sample.gMean, 0, sortScratch, 0, n)
        java.util.Arrays.sort(sortScratch, 0, n)
        val medianG = sortScratch[n / 2]
        latestMedianG = medianG

        val idx = (ringWritten % ringSize).toInt()
        ringG[idx] = medianG
        ringWritten++

        if (medianG < sessionMinG) sessionMinG = medianG
        if (medianG > sessionMaxG) sessionMaxG = medianG

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

    private fun pulseStrengthFromRing(): Float {
        val have = if (ringWritten >= ringSize.toLong()) ringSize else ringWritten.toInt()
        if (have < 8) return 0f
        // Walk the ring in chronological order
        val tmp = FloatArray(have)
        val start = if (ringWritten >= ringSize.toLong())
            (ringWritten % ringSize).toInt() else 0
        for (i in 0 until have) tmp[i] = ringG[(start + i) % ringSize]
        var sum = 0.0
        for (v in tmp) sum += v
        val mean = (sum / have).toFloat()
        var ss = 0.0
        for (v in tmp) {
            val d = v - mean
            ss += d * d
        }
        return sqrt(ss / have).toFloat()
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
            // Sweep needs at least the protocol length + a little hold time
            targetSec = if (on) maxOf(_state.value.targetSec, SWEEP_MIN_DURATION) else _state.value.targetSec
        )
    }

    fun start(notes: String? = null) {
        if (_state.value.isRecording) return
        sampleCount = 0L
        firstSampleNs = 0L
        lastSampleNs = 0L
        latestR = 0f; latestG = 0f; latestB = 0f
        // Reset session-relative pressure scale so each recording starts fresh.
        sessionMinG = Float.MAX_VALUE
        sessionMaxG = 0f
        pulseStrengthMaxSeen = 0f
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
                val ps = pulseStrengthFromRing()
                if (ps > pulseStrengthMaxSeen) pulseStrengthMaxSeen = ps
                val minG = if (sessionMinG == Float.MAX_VALUE) 0f else sessionMinG
                val maxG = sessionMaxG
                val frac = if (maxG - minG > 1.5f) {
                    ((latestMedianG - minG) / (maxG - minG)).coerceIn(0f, 1f)
                } else 0f

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
                        pulseStrengthG = ps,
                        pulseStrengthMaxSeen = pulseStrengthMaxSeen,
                        medianDcG = latestMedianG,
                        sessionMinDcG = minG,
                        sessionMaxDcG = maxG,
                        pressureFrac = frac,
                        sweepPrompt = sweepPromptFor(elapsed, s.sweepMode)
                    )
                    if (elapsed >= s.targetSec) finalize()
                } else {
                    _state.value = s.copy(
                        centerLumaR = latestR,
                        centerLumaG = latestG,
                        centerLumaB = latestB,
                        pulseStrengthG = ps,
                        pulseStrengthMaxSeen = pulseStrengthMaxSeen,
                        medianDcG = latestMedianG,
                        sessionMinDcG = minG,
                        sessionMaxDcG = maxG,
                        pressureFrac = frac
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
        private const val GRID_COLS = 16
        private const val GRID_ROWS = 12
        private const val REFRESH_MS = 100L
        private const val RING_BUFFER_SIZE = 128 // ~2 s @ 60 Hz

        // Sweep-mode protocol timing (seconds since Start)
        const val SWEEP_PRESS_END = 5f
        const val SWEEP_RELEASE_END = 10f
        const val SWEEP_VARY_END = 25f
        const val SWEEP_MIN_DURATION = 60f
    }
}
