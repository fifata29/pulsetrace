package dk.nst.hrvmonitor.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dk.nst.hrvmonitor.data.SessionRecorder
import dk.nst.hrvmonitor.ppg.HrvCalculator
import dk.nst.hrvmonitor.ppg.RoiSelector
import dk.nst.hrvmonitor.ppg.SignalProcessor
import dk.nst.hrvmonitor.ppg.TileGridAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Two-phase measurement:
 *   Phase 1 (Searching, [SEARCH_SEC] s) — full tile grid is buffered; RoiSelector picks the
 *   best contiguous cluster based on AC amplitude × spatial coherence.
 *   Phase 2 (Measuring, until [TARGET_GOOD_SEC] s of good signal) — selected tiles are
 *   averaged per frame, fed to the SignalProcessor for HRV/BPM extraction.
 */
class MeasurementViewModel(application: Application) : AndroidViewModel(application) {

    enum class Phase { Idle, Searching, Measuring, Done }

    data class UiState(
        val phase: Phase = Phase.Idle,
        val isMeasuring: Boolean = false,                // = phase ∈ {Searching, Measuring}
        val targetGoodSec: Float = TARGET_GOOD_SEC,
        val targetSearchSec: Float = SEARCH_SEC,
        val elapsedSec: Float = 0f,
        val goodSec: Float = 0f,                         // accumulated good time in measuring phase
        val searchProgress: Float = 0f,                  // 0..1 in phase 1
        val measureProgress: Float = 0f,                 // 0..1 in phase 2
        val isGoodSignal: Boolean = false,
        val sampleRateHz: Float = 0f,
        val coverage: Float = 0f,                        // most recent coverage, 0..1
        val signal: List<SignalProcessor.Sample> = emptyList(),
        val peaks: List<SignalProcessor.Peak> = emptyList(),
        val rrMs: List<Float> = emptyList(),
        val metrics: HrvCalculator.Metrics = HrvCalculator.Metrics(null, null, null, null, 0, 0),
        val gridCols: Int = GRID_COLS,
        val gridRows: Int = GRID_ROWS,
        val roi: RoiInfo? = null,
        val report: Report? = null
    )

    /** Bounding box (inclusive) of selected tiles, plus diagnostic info from selection. */
    data class RoiInfo(
        val rowStart: Int,
        val rowEnd: Int,
        val colStart: Int,
        val colEnd: Int,
        val tileIndices: IntArray,
        val bestScore: Float,
        val medianFreqHz: Float,
        val acceptable: Boolean
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    data class Report(
        val durationSec: Float,
        val goodSec: Float,
        val targetGoodSec: Float,
        val timedOut: Boolean,
        val sampleRateHz: Float,
        val coverage: Float,
        val metrics: HrvCalculator.Metrics,
        val rrMs: List<Float>,
        val peaks: List<SignalProcessor.Peak>,
        val sessionPath: String?,
        val roi: RoiInfo?,
        val spectralBpm: Float = 0f
    )

    private val processor = SignalProcessor(windowSeconds = 60f)
    private val recorder = SessionRecorder(application.applicationContext)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private var startNs: Long = 0
    private var measureStartNs: Long = 0
    private var goodNs: Long = 0L
    private var lastGoodTNs: Long = 0L

    @Volatile private var latestCoverage: Float = 0f
    @Volatile private var phaseRef: Phase = Phase.Idle
    @Volatile private var roiTiles: IntArray = IntArray(0)
    @Volatile private var nTilesTotal: Int = 0
    @Volatile private var baselineLuma: Float = 0f
    @Volatile private var lumaThreshold: Float = 0f

    private val searchBuffer = ArrayDeque<TileGridAnalyzer.TileSample>()

    val analyzer = TileGridAnalyzer(GRID_COLS, GRID_ROWS) { sample ->
        nTilesTotal = sample.tilesR.size
        when (phaseRef) {
            Phase.Searching -> {
                // Buffer full-grid frames for offline tile selection. Bounded.
                synchronized(searchBuffer) {
                    searchBuffer.addLast(sample)
                    if (searchBuffer.size > SEARCH_BUFFER_MAX) searchBuffer.removeFirst()
                }
            }
            Phase.Measuring -> {
                val tiles = roiTiles
                if (tiles.isEmpty()) return@TileGridAnalyzer
                var sumR = 0f; var sumY = 0f; var n = 0
                for (idx in tiles) {
                    val r = sample.tilesR.getOrNull(idx) ?: continue
                    val y = sample.tilesY.getOrNull(idx) ?: continue
                    if (r >= 254f) continue
                    sumR += r; sumY += y; n++
                }
                if (n == 0) return@TileGridAnalyzer
                val red = sumR / n
                val luma = sumY / n

                // Coverage = current luma as a fraction of the phase-1 baseline.
                // This self-calibrates per recording, so dim ROIs aren't penalised.
                val ref = lumaThreshold
                val coverage = if (ref > 0f) (luma / ref).coerceIn(0f, 1.5f) else 0f
                latestCoverage = coverage.coerceIn(0f, 1f)

                recorder.appendSample(
                    SessionRecorder.SampleRow(
                        tNs = sample.timestampNs, red = red, luma = luma, coverage = latestCoverage
                    )
                )

                val good = luma >= ref || ref <= 0f
                if (good) {
                    val ts = sample.timestampNs
                    val gap = ts - lastGoodTNs
                    if (lastGoodTNs > 0L && gap in 1L..MAX_GOOD_GAP_NS) goodNs += gap
                    lastGoodTNs = ts
                    processor.addSample(ts, red, latestCoverage)
                } else {
                    lastGoodTNs = 0L
                }
            }
            else -> {}
        }
    }

    fun start() {
        if (_state.value.phase != Phase.Idle && _state.value.phase != Phase.Done) return
        processor.reset()
        startNs = System.nanoTime()
        measureStartNs = 0L
        goodNs = 0L
        lastGoodTNs = 0L
        latestCoverage = 0f
        roiTiles = IntArray(0)
        synchronized(searchBuffer) { searchBuffer.clear() }
        phaseRef = Phase.Searching

        _state.value = UiState(
            phase = Phase.Searching,
            isMeasuring = true
        )

        refreshJob = viewModelScope.launch(Dispatchers.Default) {
            // ---- Phase 1: Search ----
            while (phaseRef == Phase.Searching) {
                val elapsed = (System.nanoTime() - startNs) / 1e9f
                _state.value = _state.value.copy(
                    elapsedSec = elapsed,
                    searchProgress = (elapsed / SEARCH_SEC).coerceIn(0f, 1f),
                    coverage = latestCoverage
                )
                if (elapsed >= SEARCH_SEC) {
                    runRoiSelection()
                    break
                }
                delay(REFRESH_MS)
            }

            // ---- Phase 2: Measure ----
            if (phaseRef == Phase.Measuring) {
                measureStartNs = System.nanoTime()
                while (phaseRef == Phase.Measuring) {
                    val snap = processor.snapshot()
                    val metrics = HrvCalculator.compute(snap.rrMs)
                    val measureElapsed = (System.nanoTime() - measureStartNs) / 1e9f
                    val goodSec = goodNs / 1e9f
                    val coverageNow = latestCoverage

                    _state.value = _state.value.copy(
                        elapsedSec = (System.nanoTime() - startNs) / 1e9f,
                        goodSec = goodSec,
                        measureProgress = (goodSec / TARGET_GOOD_SEC).coerceIn(0f, 1f),
                        sampleRateHz = snap.sampleRateHz,
                        coverage = coverageNow,
                        isGoodSignal = coverageNow >= GOOD_COVERAGE,
                        signal = snap.samples,
                        peaks = snap.peaks,
                        rrMs = snap.rrMs,
                        metrics = metrics
                    )

                    if (goodSec >= TARGET_GOOD_SEC) {
                        finalizeMeasurement(measureElapsed, goodSec, timedOut = false)
                        break
                    }
                    if (measureElapsed >= MAX_MEASURE_WALL_SEC) {
                        finalizeMeasurement(measureElapsed, goodSec, timedOut = true)
                        break
                    }
                    delay(REFRESH_MS)
                }
            }
        }
    }

    fun stop() {
        when (phaseRef) {
            Phase.Searching -> {
                phaseRef = Phase.Idle
                refreshJob?.cancel()
                refreshJob = null
                _state.value = _state.value.copy(phase = Phase.Idle, isMeasuring = false)
            }
            Phase.Measuring -> {
                val measureElapsed = (System.nanoTime() - measureStartNs) / 1e9f
                val goodSec = goodNs / 1e9f
                finalizeMeasurement(measureElapsed, goodSec, timedOut = goodSec < TARGET_GOOD_SEC)
            }
            else -> {}
        }
    }

    fun dismissReport() {
        _state.value = _state.value.copy(report = null, phase = Phase.Idle)
    }

    private suspend fun runRoiSelection() {
        val frames: List<TileGridAnalyzer.TileSample> = synchronized(searchBuffer) {
            searchBuffer.toList()
        }
        val result = RoiSelector.select(
            tilesPerFrame = frames,
            gridCols = GRID_COLS,
            gridRows = GRID_ROWS,
            topK = ROI_TOP_K
        )
        val info = RoiInfo(
            rowStart = result.bboxRowStart,
            rowEnd = result.bboxRowEnd,
            colStart = result.bboxColStart,
            colEnd = result.bboxColEnd,
            tileIndices = result.tileIndices,
            bestScore = result.bestScore,
            medianFreqHz = result.medianFreqHz,
            acceptable = result.acceptable
        )
        roiTiles = result.tileIndices
        baselineLuma = result.baselineLuma
        lumaThreshold = (result.baselineLuma * GOOD_LUMA_FRACTION).coerceAtLeast(15f)
        // Open a recording session now that we know the ROI.
        recorder.start()
        phaseRef = Phase.Measuring
        _state.value = _state.value.copy(
            phase = Phase.Measuring,
            roi = info,
            isMeasuring = true,
            sampleRateHz = result.sampleRateHz
        )
        // Discard buffered phase-1 frames to free memory.
        synchronized(searchBuffer) { searchBuffer.clear() }
    }

    private fun finalizeMeasurement(measureElapsed: Float, goodSec: Float, timedOut: Boolean) {
        phaseRef = Phase.Done
        refreshJob?.cancel()
        refreshJob = null

        val snap = processor.snapshot()
        val metrics = HrvCalculator.compute(snap.rrMs)

        val roiState = _state.value.roi
        val recRoi = roiState?.let {
            SessionRecorder.RoiInfo(
                rowStart = it.rowStart,
                rowEnd = it.rowEnd,
                colStart = it.colStart,
                colEnd = it.colEnd,
                tileIndices = it.tileIndices,
                gridCols = GRID_COLS,
                gridRows = GRID_ROWS,
                bestScore = it.bestScore,
                medianFreqHz = it.medianFreqHz,
                acceptable = it.acceptable
            )
        }
        val session = recorder.stop(
            durationSec = measureElapsed,
            sampleRateHz = snap.sampleRateHz,
            coverage = snap.coverage,
            signal = snap.samples,
            peaks = snap.peaks,
            rrMs = snap.rrMs,
            metrics = metrics,
            roi = recRoi,
            goodSec = goodSec,
            targetGoodSec = TARGET_GOOD_SEC,
            timedOut = timedOut,
            spectralBpm = snap.spectralBpm
        )

        val report = Report(
            durationSec = measureElapsed,
            goodSec = goodSec,
            targetGoodSec = TARGET_GOOD_SEC,
            timedOut = timedOut,
            sampleRateHz = snap.sampleRateHz,
            coverage = snap.coverage,
            metrics = metrics,
            rrMs = snap.rrMs,
            peaks = snap.peaks,
            sessionPath = session?.dir?.absolutePath,
            roi = _state.value.roi,
            spectralBpm = snap.spectralBpm
        )

        _state.value = _state.value.copy(
            phase = Phase.Done,
            isMeasuring = false,
            elapsedSec = (System.nanoTime() - startNs) / 1e9f,
            goodSec = goodSec,
            measureProgress = (goodSec / TARGET_GOOD_SEC).coerceIn(0f, 1f),
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
        const val SEARCH_SEC = 10f
        const val TARGET_GOOD_SEC = 50f
        private const val MAX_MEASURE_WALL_SEC = 180f
        const val GRID_COLS = 16
        const val GRID_ROWS = 12
        private const val GOOD_COVERAGE = 0.85f
        private const val GOOD_LUMA_FRACTION = 0.5f      // current luma must be ≥ 50% of phase-1 baseline
        private const val MAX_GOOD_GAP_NS = 250_000_000L
        private const val REFRESH_MS = 100L
        private const val ROI_TOP_K = 10
        private const val SEARCH_BUFFER_MAX = 360       // cap memory if fs spikes (12 s at 30 Hz)
    }
}
