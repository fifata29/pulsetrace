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

    /** Raw Mode is for research recordings — we label the body site precisely
     *  so offline analysis can compare like-with-like across the 4 sites that
     *  matter to us: fingertip (transmission PPG), palm (reflectance, thick
     *  tissue), forearm volar (inner / vein side, classic reflectance site),
     *  forearm dorsal (outer / hairy side, different vascular bed). */
    enum class Site { Fingertip, Palm, ForearmVolar, ForearmDorsal, Other }

    /** Camera control mode for the recording.
     *
     *  - Auto: legacy behaviour. Camera2 picks ISO, exposure time, white-balance
     *    gains automatically; AE_LOCK + AWB_LOCK are set at the moment recording
     *    starts but the *values* the camera locked at are whatever it happened
     *    to converge on (often suboptimal — high ISO with shot noise, drifting
     *    exposure that bleeds into the pulse signal).
     *
     *  - Manual: AE and AWB disabled entirely. Fixed ISO, fixed exposure, fixed
     *    colour gains. The camera becomes a deterministic photon-to-count
     *    converter — the only thing that varies frame-to-frame is light
     *    reaching the sensor, which is what we actually want for PPG. */
    enum class CameraMode { Auto, Manual }

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
        val site: Site = Site.ForearmVolar,
        val cameraMode: CameraMode = CameraMode.Manual,
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
        // Live-diagnostic fields — populated every refresh tick whether or not
        // we're recording, so the user can see in real time whether the camera
        // is doing what we asked and whether a pulse is actually present.
        val ppgTrace: FloatArray = FloatArray(TRACE_DISPLAY_LEN),
        val ppgTraceFs: Float = 0f,
        val liveBpm: Float = 0f,
        val liveIso: Int = 0,
        val liveExposureNs: Long = 0L,
        val liveRGain: Float = 0f,
        val liveGGain: Float = 0f,
        val liveBGain: Float = 0f,
        val liveAeState: Int = -1,
        val liveAwbState: Int = -1,
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
    @Volatile private var liveIso: Int = 0
    @Volatile private var liveExposureNs: Long = 0L
    @Volatile private var liveRGain: Float = 0f
    @Volatile private var liveGGain: Float = 0f
    @Volatile private var liveBGain: Float = 0f
    @Volatile private var liveAeState: Int = -1
    @Volatile private var liveAwbState: Int = -1

    // Per-tile ring buffer of the green-channel mean. Each tile gets its own
    // RING_BUFFER_SIZE-element ring. The full ring (6 s) feeds live BPM and the
    // strip chart; the last HEATMAP_WINDOW samples (2 s) feed the responsive
    // heatmap. Total memory: 192 × 360 × 4 B ≈ 280 KB.
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
     *  cardiac-band component (>~0.7 Hz / 42 BPM) of the recent green-channel
     *  values for tile i. We run a single-pole IIR high-pass over each tile's
     *  ring before taking std — without that the std mixes pulse with slow
     *  drift (AE adjustment, breathing, vasomotion, motion artefact), which
     *  is what made the old heatmap "not helpful". */
    private fun computeTileAcs(): Triple<FloatArray, Int, Float> {
        val nTiles = GRID_COLS * GRID_ROWS
        val out = FloatArray(nTiles)
        val available = minOf(ringWritten, RING_BUFFER_SIZE.toLong()).toInt()
        val window = minOf(available, HEATMAP_WINDOW)
        if (window < 16) return Triple(out, -1, 0f)
        // start = index of oldest sample in the heatmap window.
        val start = ((ringWritten - window + RING_BUFFER_SIZE) % RING_BUFFER_SIZE).toInt()
        var bestIdx = -1
        var bestAc = 0f
        for (i in 0 until nTiles) {
            val ring = ringPerTile[i]
            // HPF: y[n] = alpha * (y[n-1] + x[n] - x[n-1]); discard first
            // HPF_WARMUP samples to let the filter settle.
            var prevX = ring[start]
            var prevY = 0f
            var sum = 0.0
            var sumSq = 0.0
            var n = 0
            for (k in 1 until window) {
                val idx = if (start + k < RING_BUFFER_SIZE) start + k
                          else start + k - RING_BUFFER_SIZE
                val x = ring[idx]
                val y = HPF_ALPHA * (prevY + x - prevX)
                prevX = x
                prevY = y
                if (k >= HPF_WARMUP) {
                    sum += y
                    sumSq += y.toDouble() * y
                    n++
                }
            }
            if (n < 2) continue
            val mean = sum / n
            val variance = (sumSq / n - mean * mean).coerceAtLeast(0.0)
            val ac = sqrt(variance).toFloat()
            out[i] = ac
            if (ac > bestAc) { bestAc = ac; bestIdx = i }
        }
        return Triple(out, bestIdx, bestAc)
    }

    /** Live trace + BPM from the best tile's FULL ring (~6 s of green-channel
     *  data). Applied HPF, then peak-detect for BPM. Returns the display-length
     *  trace (last TRACE_DISPLAY_LEN samples) and BPM (0 if not enough peaks). */
    private fun computeLiveTrace(bestIdx: Int, fsHz: Float): Triple<FloatArray, Float, Float> {
        val display = FloatArray(TRACE_DISPLAY_LEN)
        if (bestIdx < 0) return Triple(display, 0f, 0f)
        val available = minOf(ringWritten, RING_BUFFER_SIZE.toLong()).toInt()
        if (available < 60) return Triple(display, 0f, 0f)
        val start = ((ringWritten - available + RING_BUFFER_SIZE) % RING_BUFFER_SIZE).toInt()
        val ring = ringPerTile[bestIdx]
        // HPF the full ring.
        val hpf = FloatArray(available)
        var prevX = ring[start]
        var prevY = 0f
        for (k in 1 until available) {
            val idx = if (start + k < RING_BUFFER_SIZE) start + k
                      else start + k - RING_BUFFER_SIZE
            val x = ring[idx]
            val y = HPF_ALPHA * (prevY + x - prevX)
            hpf[k] = y
            prevX = x
            prevY = y
        }
        // Skip warm-up.
        val usableStart = minOf(HPF_WARMUP, available)
        val usableLen = available - usableStart
        if (usableLen < 30) return Triple(display, 0f, 0f)

        // Copy last TRACE_DISPLAY_LEN samples into the display array (right-aligned).
        val take = minOf(TRACE_DISPLAY_LEN, usableLen)
        val srcOffset = available - take
        for (i in 0 until take) display[i] = hpf[srcOffset + i]

        // Live BPM via simple peak detection on the usable HPF region.
        val fs = if (fsHz > 0f) fsHz else 60f
        var sum = 0.0
        var sumSq = 0.0
        for (i in usableStart until available) { val v = hpf[i]; sum += v; sumSq += v.toDouble() * v }
        val mean = sum / usableLen
        val std = sqrt((sumSq / usableLen - mean * mean).coerceAtLeast(0.0)).toFloat()
        if (std < 0.05f) return Triple(display, std, 0f)

        val prominence = 0.4f * std
        val minDist = (0.35f * fs).toInt().coerceAtLeast(8)  // ≤171 BPM ceiling
        val peaks = ArrayList<Int>()
        var i = usableStart + 1
        while (i < available - 1) {
            val v = hpf[i]
            if (v > hpf[i - 1] && v > hpf[i + 1] && v > prominence) {
                if (peaks.isEmpty() || i - peaks.last() >= minDist) {
                    peaks.add(i)
                } else if (v > hpf[peaks.last()]) {
                    peaks[peaks.size - 1] = i
                }
            }
            i++
        }
        if (peaks.size < 3) return Triple(display, std, 0f)
        val rrs = IntArray(peaks.size - 1) { peaks[it + 1] - peaks[it] }
        val sorted = rrs.sortedArray()
        val medRR = sorted[sorted.size / 2]
        val bpm = if (medRR > 0) 60f * fs / medRR else 0f
        return Triple(display, std, bpm)
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

    fun setCameraMode(mode: CameraMode) {
        if (_state.value.isRecording) return
        _state.value = _state.value.copy(cameraMode = mode)
    }

    fun appendCameraMetadata(meta: RawRecorder.CameraMetadata) {
        liveIso = meta.isoSensitivity
        liveExposureNs = meta.exposureTimeNs
        liveRGain = meta.rGain
        liveGGain = meta.gEvenGain
        liveBGain = meta.bGain
        liveAeState = meta.aeState
        liveAwbState = meta.awbState
        if (_state.value.isRecording) recorder.appendCameraMetadata(meta)
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
            notes = combinedNotes,
            cameraMode = _state.value.cameraMode.name.lowercase()
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

                // Estimate current sample rate for the live-BPM calculation. We
                // can't trust the camera's nominal 60 FPS — fall back to it only
                // when we don't yet have a wall-clock-derived estimate.
                val measuredFs = if (firstSampleNs > 0L && lastSampleNs > firstSampleNs
                    && sampleCount > 1L)
                    (sampleCount - 1) / ((lastSampleNs - firstSampleNs) / 1e9f) else 0f
                val fsForBpm = if (measuredFs > 1f) measuredFs else TARGET_FPS.toFloat()
                val (ppgTrace, traceStd, liveBpm) = computeLiveTrace(bestIdx, fsForBpm)
                val isoNow = liveIso
                val expNow = liveExposureNs
                val rgNow = liveRGain
                val ggNow = liveGGain
                val bgNow = liveBGain
                val aeNow = liveAeState
                val awbNow = liveAwbState

                if (s.isRecording) {
                    val elapsed = (System.nanoTime() - startNs) / 1e9f
                    _state.value = s.copy(
                        elapsedSec = elapsed,
                        progress = (elapsed / s.targetSec).coerceIn(0f, 1f),
                        sampleCount = sampleCount,
                        sampleRateHz = measuredFs,
                        centerLumaR = latestR,
                        centerLumaG = latestG,
                        centerLumaB = latestB,
                        tileAc = tileAc,
                        bestTileRow = bestRow,
                        bestTileCol = bestCol,
                        bestTileAc = bestAc,
                        bestTileAcMaxSeen = bestTileAcMaxSeen,
                        ppgTrace = ppgTrace,
                        ppgTraceFs = fsForBpm,
                        liveBpm = liveBpm,
                        liveIso = isoNow,
                        liveExposureNs = expNow,
                        liveRGain = rgNow,
                        liveGGain = ggNow,
                        liveBGain = bgNow,
                        liveAeState = aeNow,
                        liveAwbState = awbNow,
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
                        bestTileAcMaxSeen = bestTileAcMaxSeen,
                        ppgTrace = ppgTrace,
                        ppgTraceFs = fsForBpm,
                        liveBpm = liveBpm,
                        liveIso = isoNow,
                        liveExposureNs = expNow,
                        liveRGain = rgNow,
                        liveGGain = ggNow,
                        liveBGain = bgNow,
                        liveAeState = aeNow,
                        liveAwbState = awbNow
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
        private const val RING_BUFFER_SIZE = 360 // 6 s @ 60 Hz — feeds live BPM + trace
        private const val HEATMAP_WINDOW = 120   // 2 s window for the heatmap std
        const val TRACE_DISPLAY_LEN = 240        // 4 s of trace drawn in the strip chart
        // 1st-order IIR HPF: alpha = exp(-2π·fc/fs) with fc≈0.7 Hz, fs≈60 Hz.
        // Cuts respiration / vasomotion / AE drift below 42 BPM, keeps cardiac.
        private const val HPF_ALPHA = 0.929f
        private const val HPF_WARMUP = 12  // ~0.2 s settling at 60 Hz

        const val SWEEP_PRESS_END = 5f
        const val SWEEP_RELEASE_END = 10f
        const val SWEEP_VARY_END = 25f
        const val SWEEP_MIN_DURATION = 60f
    }
}
