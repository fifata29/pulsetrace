package dk.nst.hrvmonitor.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dk.nst.hrvmonitor.data.SessionRecorder
import dk.nst.hrvmonitor.data.StateTag
import dk.nst.hrvmonitor.ppg.HrvCalculator
import dk.nst.hrvmonitor.ppg.PulseMorphology
import dk.nst.hrvmonitor.ppg.QualityScorer
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
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Two-phase measurement:
 *   Phase 1 (Searching, [SEARCH_SEC] s) — full tile grid is buffered; RoiSelector picks the
 *   best contiguous cluster based on AC amplitude × spatial coherence.
 *   Phase 2 (Measuring, until [TARGET_GOOD_SEC] s of good signal) — selected tiles are
 *   averaged per frame, fed to the SignalProcessor for HRV/BPM extraction.
 */
class MeasurementViewModel(application: Application) : AndroidViewModel(application) {

    enum class Phase { Idle, Settling, Searching, Measuring, Done }

    /** Body site. Drives the channel used for the chart trace and morphology
     *  computation. Peak detection always runs on red regardless. */
    enum class Site { Fingertip, Forearm }

    /** Cardiac Snapshot is a chained two-stage workflow: fingertip → forearm,
     *  combining each site's strongest biomarkers into one composite report
     *  (HR/HRV from fingertip + morphology from forearm). */
    enum class SnapshotState { Inactive, Stage1Active, Stage1DonePending, Stage2Active, Done }

    data class UiState(
        val phase: Phase = Phase.Idle,
        val isMeasuring: Boolean = false,                // = phase ∈ {Settling, Searching, Measuring}
        val site: Site = Site.Fingertip,
        val targetGoodSec: Float = TARGET_GOOD_SEC,
        val targetSearchSec: Float = SEARCH_SEC,
        val targetSettleSec: Float = SETTLE_SEC,
        val elapsedSec: Float = 0f,
        val goodSec: Float = 0f,                         // accumulated good time in measuring phase
        val settleProgress: Float = 0f,                  // 0..1 settle phase
        val searchProgress: Float = 0f,                  // 0..1 in search phase
        val measureProgress: Float = 0f,                 // 0..1 in measuring phase
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
        val report: Report? = null,
        // Position-scout overlay during Settle + Search phases. Per-tile rolling
        // AC amplitude (last ~2 s) of the active channel (R for fingertip, G for
        // forearm). Lets the user visually find the lateral sweet-spot before
        // the ROI gets locked.
        val tileAc: FloatArray = FloatArray(GRID_COLS * GRID_ROWS),
        val bestTileRow: Int = -1,
        val bestTileCol: Int = -1,
        // Cardiac Snapshot state — when active, drives a chained two-stage flow
        // (fingertip then forearm). [snapshotStage1Report] holds stage 1 between
        // stages so [composite] can be built after stage 2 finalises.
        val snapshotState: SnapshotState = SnapshotState.Inactive,
        val snapshotStage1Report: Report? = null,
        val composite: CompositeReport? = null
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
        val spectralBpm: Float = 0f,
        val tag: StateTag? = null,
        val morphology: PulseMorphology.Result? = null,
        val site: Site = Site.Fingertip,
        // True when peak-derived BPM and spectral BPM agree within ±20 %. False
        // means the peak detector and spectral cross-check disagree by enough
        // that the BPM number shouldn't be trusted; the UI surfaces a warning
        // and downstream HRV metrics are similarly suspect.
        val bpmConfident: Boolean = true
    )

    /** Composite of fingertip + forearm reports produced by the Cardiac Snapshot
     *  workflow. Each metric is sourced from the site that's most reliable for
     *  it: HR / HRV from fingertip (red, no morphology), morphology from
     *  forearm (green, no saturation). */
    data class CompositeReport(
        val fingertip: Report,
        val forearm: Report
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

    // Per-tile ring buffer of the active channel (R or G) for the live position-
    // scout overlay during Settle + Search. 192 × 128 floats = ~96 KB.
    private val scoutRing: Array<FloatArray> =
        Array(GRID_COLS * GRID_ROWS) { FloatArray(SCOUT_RING_SIZE) }
    @Volatile private var scoutRingWritten: Long = 0L
    @Volatile private var scoutChannelIsGreen: Boolean = false

    val analyzer = TileGridAnalyzer(GRID_COLS, GRID_ROWS) { sample ->
        nTilesTotal = sample.tilesR.size
        // Always estimate coverage from a centered tile so the UI can give feedback
        // during Settling — we just don't analyse anything else.
        val centerIdx = (GRID_ROWS / 2) * GRID_COLS + (GRID_COLS / 2)
        val centerY = sample.tilesY.getOrNull(centerIdx) ?: 0f
        latestCoverage = (centerY / 200f).coerceIn(0f, 1f)
        when (phaseRef) {
            Phase.Settling -> {
                // Camera + torch are on, AE/AWB unlocked. We populate the live
                // position-scout ring so the user can slide laterally and watch
                // the heat-map find the pulse region BEFORE the ROI gets locked.
                writeScoutRing(sample)
            }
            Phase.Searching -> {
                // Buffer full-grid frames for offline tile selection AND keep the
                // live scout going so the overlay stays responsive.
                synchronized(searchBuffer) {
                    searchBuffer.addLast(sample)
                    if (searchBuffer.size > SEARCH_BUFFER_MAX) searchBuffer.removeFirst()
                }
                writeScoutRing(sample)
            }
            Phase.Measuring -> {
                val tiles = roiTiles
                if (tiles.isEmpty()) return@TileGridAnalyzer
                var sumR = 0f; var sumG = 0f; var sumY = 0f; var n = 0
                for (idx in tiles) {
                    val r = sample.tilesR.getOrNull(idx) ?: continue
                    val g = sample.tilesG.getOrNull(idx) ?: continue
                    val y = sample.tilesY.getOrNull(idx) ?: continue
                    if (r >= 254f) continue
                    sumR += r; sumG += g; sumY += y; n++
                }
                if (n == 0) return@TileGridAnalyzer
                val red = sumR / n
                val green = sumG / n
                val luma = sumY / n

                // Coverage = current luma as a fraction of the phase-1 baseline.
                // This self-calibrates per recording, so dim ROIs aren't penalised.
                val ref = lumaThreshold
                val coverage = if (ref > 0f) (luma / ref).coerceIn(0f, 1.5f) else 0f
                latestCoverage = coverage.coerceIn(0f, 1f)

                recorder.appendSample(
                    SessionRecorder.SampleRow(
                        tNs = sample.timestampNs, red = red, green = green,
                        luma = luma, coverage = latestCoverage
                    )
                )

                val good = luma >= ref || ref <= 0f
                if (good) {
                    val ts = sample.timestampNs
                    val gap = ts - lastGoodTNs
                    if (lastGoodTNs > 0L && gap in 1L..MAX_GOOD_GAP_NS) goodNs += gap
                    lastGoodTNs = ts
                    processor.addSample(ts, red, green, latestCoverage)
                } else {
                    lastGoodTNs = 0L
                }
            }
            else -> {}
        }
    }

    fun setSite(site: Site) {
        if (_state.value.isMeasuring) return
        _state.value = _state.value.copy(site = site)
        processor.setUseGreen(site == Site.Forearm)
        scoutChannelIsGreen = site == Site.Forearm
    }

    /** Begin a Cardiac Snapshot — stage 1 is fingertip, then user is prompted
     *  to switch to forearm for stage 2, then a composite report is shown. */
    fun startCardiacSnapshot() {
        if (_state.value.isMeasuring) return
        _state.value = _state.value.copy(
            site = Site.Fingertip,
            snapshotState = SnapshotState.Stage1Active,
            snapshotStage1Report = null,
            composite = null,
            report = null
        )
        processor.setUseGreen(false)
        scoutChannelIsGreen = false
        start()
    }

    /** After stage 1 completes the user is prompted to reposition; this kicks
     *  off stage 2 (forearm). */
    fun continueSnapshotStage2() {
        if (_state.value.snapshotState != SnapshotState.Stage1DonePending) return
        _state.value = _state.value.copy(
            site = Site.Forearm,
            snapshotState = SnapshotState.Stage2Active,
            phase = Phase.Idle
        )
        processor.setUseGreen(true)
        scoutChannelIsGreen = true
        start()
    }

    fun cancelSnapshot() {
        if (_state.value.snapshotState == SnapshotState.Inactive) return
        _state.value = _state.value.copy(
            snapshotState = SnapshotState.Inactive,
            snapshotStage1Report = null,
            composite = null
        )
    }

    private fun writeScoutRing(sample: TileGridAnalyzer.TileSample) {
        val n = sample.tilesR.size
        val slot = (scoutRingWritten % SCOUT_RING_SIZE).toInt()
        val src = if (scoutChannelIsGreen) sample.tilesG else sample.tilesR
        for (i in 0 until n) scoutRing[i][slot] = src[i]
        scoutRingWritten++
    }

    private fun computeScoutTileAc(): Triple<FloatArray, Int, Int> {
        val nTiles = GRID_COLS * GRID_ROWS
        val out = FloatArray(nTiles)
        val have = if (scoutRingWritten >= SCOUT_RING_SIZE.toLong())
            SCOUT_RING_SIZE else scoutRingWritten.toInt()
        if (have < 8) return Triple(out, -1, -1)
        var bestIdx = -1
        var bestAc = 0f
        for (i in 0 until nTiles) {
            val ring = scoutRing[i]
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
        val bestRow = if (bestIdx >= 0) bestIdx / GRID_COLS else -1
        val bestCol = if (bestIdx >= 0) bestIdx % GRID_COLS else -1
        return Triple(out, bestRow, bestCol)
    }

    fun start() {
        if (_state.value.phase != Phase.Idle && _state.value.phase != Phase.Done) return
        val currentSite = _state.value.site
        processor.reset()
        processor.setUseGreen(currentSite == Site.Forearm)
        scoutChannelIsGreen = currentSite == Site.Forearm
        scoutRingWritten = 0L
        startNs = System.nanoTime()
        measureStartNs = 0L
        goodNs = 0L
        lastGoodTNs = 0L
        latestCoverage = 0f
        roiTiles = IntArray(0)
        synchronized(searchBuffer) { searchBuffer.clear() }
        phaseRef = Phase.Settling

        // Reset measurement-specific fields but PRESERVE site, snapshotState,
        // snapshotStage1Report, composite. Using UiState() fresh would clobber
        // the snapshot bookkeeping that startCardiacSnapshot() / continueSnapshotStage2()
        // sets up before calling start().
        _state.value = _state.value.copy(
            phase = Phase.Settling,
            isMeasuring = true,
            elapsedSec = 0f,
            goodSec = 0f,
            settleProgress = 0f,
            searchProgress = 0f,
            measureProgress = 0f,
            isGoodSignal = false,
            sampleRateHz = 0f,
            coverage = 0f,
            signal = emptyList(),
            peaks = emptyList(),
            rrMs = emptyList(),
            metrics = HrvCalculator.Metrics(null, null, null, null, 0, 0),
            roi = null,
            report = null,
            bestTileRow = -1,
            bestTileCol = -1
        )

        refreshJob = viewModelScope.launch(Dispatchers.Default) {
            // ---- Phase 0: Settle (camera + AE/AWB converge, finger placement) ----
            val settleStart = System.nanoTime()
            while (phaseRef == Phase.Settling) {
                val elapsed = (System.nanoTime() - settleStart) / 1e9f
                val (acArr, bRow, bCol) = computeScoutTileAc()
                _state.value = _state.value.copy(
                    elapsedSec = (System.nanoTime() - startNs) / 1e9f,
                    settleProgress = (elapsed / SETTLE_SEC).coerceIn(0f, 1f),
                    coverage = latestCoverage,
                    tileAc = acArr,
                    bestTileRow = bRow,
                    bestTileCol = bCol
                )
                if (elapsed >= SETTLE_SEC) {
                    phaseRef = Phase.Searching
                    _state.value = _state.value.copy(
                        phase = Phase.Searching,
                        settleProgress = 1f
                    )
                    break
                }
                delay(REFRESH_MS)
            }
            val searchStart = System.nanoTime()
            // ---- Phase 1: Search ----
            while (phaseRef == Phase.Searching) {
                val elapsed = (System.nanoTime() - searchStart) / 1e9f
                val (acArr, bRow, bCol) = computeScoutTileAc()
                _state.value = _state.value.copy(
                    elapsedSec = (System.nanoTime() - startNs) / 1e9f,
                    searchProgress = (elapsed / SEARCH_SEC).coerceIn(0f, 1f),
                    coverage = latestCoverage,
                    tileAc = acArr,
                    bestTileRow = bRow,
                    bestTileCol = bCol
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
            Phase.Settling, Phase.Searching -> {
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
        _state.value = _state.value.copy(
            report = null,
            composite = null,
            snapshotState = SnapshotState.Inactive,
            snapshotStage1Report = null,
            phase = Phase.Idle
        )
    }

    /** Persist a state tag (Resting, Post-workout, etc.) to the just-recorded session. */
    fun setTagForLastSession(tag: StateTag) {
        val report = _state.value.report ?: return
        val updated = report.copy(tag = tag)
        _state.value = _state.value.copy(report = updated)
        report.sessionPath?.let { recorder.appendTagToSummary(it, tag) }
    }

    private suspend fun runRoiSelection() {
        val frames: List<TileGridAnalyzer.TileSample> = synchronized(searchBuffer) {
            searchBuffer.toList()
        }
        val result = RoiSelector.select(
            tilesPerFrame = frames,
            gridCols = GRID_COLS,
            gridRows = GRID_ROWS,
            topK = ROI_TOP_K,
            useGreen = _state.value.site == Site.Forearm
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
        // Open a recording session now that we know the ROI. Site + display
        // channel are written to the CSV header so analysis tools can branch
        // correctly when replaying old vs new sessions.
        val site = _state.value.site
        val displayChan = if (site == Site.Forearm) "G" else "R"
        recorder.start(site = site.name.lowercase(), displayChannel = displayChan)
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
        // Perfusion index = std(bandpassed) / mean(raw) × 100. Gold-standard
        // hardware SQI from clinical pulse oximetry.
        val perfusion = computePerfusionIndex(snap.samples)
        val qualityScore = QualityScorer.scoreFromInputs(
            bpm = metrics.bpm,
            spectralBpm = snap.spectralBpm,
            rrMs = snap.rrMs,
            sampleRateHz = snap.sampleRateHz,
            coverage = snap.coverage,
            validBeats = metrics.validBeats,
            totalBeats = metrics.totalBeats,
            timedOut = timedOut,
            perfusionIndex = perfusion
        )

        // Pulse-wave morphology: feed the despiked-detrended (NOT bandpassed) signal so
        // harmonics carrying the dicrotic notch are preserved. Peak indices come from
        // the heart-rate-bandpassed peak detector (cycle markers); PulseMorphology
        // snaps each one to the nearest local max on the morphology signal internally.
        val morphology = if (snap.samples.size > 32 && snap.peaks.size >= 4) {
            val morph = FloatArray(snap.samples.size) { snap.samples[it].morphology }
            val firstT = snap.samples.first().tSec
            val dt = (snap.samples.last().tSec - firstT) / (snap.samples.size - 1).coerceAtLeast(1)
            val peakIdx = if (dt > 0f)
                snap.peaks.map { ((it.tSec - firstT) / dt).toInt().coerceIn(0, snap.samples.size - 1) }.toIntArray()
            else IntArray(0)
            PulseMorphology.compute(morph, snap.sampleRateHz, peakIdx)
        } else null
        val site = _state.value.site
        val displayChan = if (site == Site.Forearm) "G" else "R"
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
            spectralBpm = snap.spectralBpm,
            qualityScore = qualityScore,
            morphology = morphology,
            site = site.name.lowercase(),
            displayChannel = displayChan
        )

        // BPM confidence: peak BPM agrees with spectral BPM within ±20 %. The
        // spectral check uses a tight 0.7-2.5 Hz scan window so this disagrees
        // only when peak detection found a wrong rhythm (over-counted dicrotic
        // notches, missed beats, etc.). When false, the UI surfaces a warning
        // rather than reporting BPM/HRV as if they were trustworthy.
        val bpmConfident = run {
            val bpm = metrics.bpm
            if (bpm == null || snap.spectralBpm <= 1f) false
            else (abs(bpm - snap.spectralBpm) / bpm) <= 0.20f
        }

        val currentSite = _state.value.site
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
            spectralBpm = snap.spectralBpm,
            morphology = morphology,
            site = currentSite,
            bpmConfident = bpmConfident
        )

        // Snapshot routing — stage 1 result feeds into the composite; stage 2
        // result triggers the composite report instead of the single-site one.
        val snap1 = _state.value.snapshotStage1Report
        val snapState = _state.value.snapshotState
        val newSnapshotState: SnapshotState
        val newStage1: Report?
        val newComposite: CompositeReport?
        val newReport: Report?
        when (snapState) {
            SnapshotState.Stage1Active -> {
                newSnapshotState = SnapshotState.Stage1DonePending
                newStage1 = report
                newComposite = null
                newReport = null            // suppress single-site report; show prompt
            }
            SnapshotState.Stage2Active -> {
                newSnapshotState = SnapshotState.Done
                newStage1 = null
                newComposite = if (snap1 != null) CompositeReport(snap1, report) else null
                newReport = null            // composite takes over
            }
            else -> {
                newSnapshotState = SnapshotState.Inactive
                newStage1 = null
                newComposite = null
                newReport = report
            }
        }

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
            report = newReport,
            snapshotState = newSnapshotState,
            snapshotStage1Report = newStage1,
            composite = newComposite
        )
    }

    private fun computePerfusionIndex(samples: List<SignalProcessor.Sample>): Float {
        if (samples.size < 16) return 0f
        var rawSum = 0.0
        var filtSum = 0.0
        for (s in samples) { rawSum += s.raw; filtSum += s.filtered }
        val rawMean = rawSum / samples.size
        val filtMean = filtSum / samples.size
        if (rawMean < 1.0) return 0f
        var filtSS = 0.0
        for (s in samples) {
            val d = s.filtered - filtMean
            filtSS += d * d
        }
        val acStd = sqrt(filtSS / samples.size)
        return (100.0 * acStd / rawMean).toFloat()
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
        const val SETTLE_SEC = 10f
        const val SEARCH_SEC = 10f
        const val TARGET_GOOD_SEC = 50f
        private const val MAX_MEASURE_WALL_SEC = 180f
        const val GRID_COLS = 16
        const val GRID_ROWS = 12
        private const val GOOD_COVERAGE = 0.85f
        private const val GOOD_LUMA_FRACTION = 0.5f      // current luma must be ≥ 50% of phase-1 baseline
        private const val MAX_GOOD_GAP_NS = 250_000_000L
        private const val REFRESH_MS = 100L
        private const val ROI_TOP_K = 14
        private const val SEARCH_BUFFER_MAX = 360       // cap memory if fs spikes (12 s at 30 Hz)
        private const val SCOUT_RING_SIZE = 128         // ~2 s @ 60 Hz, ring per tile for live AC
    }
}
