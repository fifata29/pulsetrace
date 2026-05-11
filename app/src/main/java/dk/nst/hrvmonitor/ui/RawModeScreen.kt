package dk.nst.hrvmonitor.ui

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Range
import android.util.Size as AndroidSize
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dk.nst.hrvmonitor.ui.theme.Accent
import dk.nst.hrvmonitor.ui.theme.Bad
import dk.nst.hrvmonitor.ui.theme.Good
import dk.nst.hrvmonitor.ui.theme.OnSurfaceMuted
import dk.nst.hrvmonitor.ui.theme.SurfaceDark
import dk.nst.hrvmonitor.ui.theme.SurfaceElev
import dk.nst.hrvmonitor.ui.theme.Warn
import dk.nst.hrvmonitor.data.RawRecorder
import dk.nst.hrvmonitor.viewmodel.RawModeViewModel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

// "Manual" mode on OnePlus 11: we wanted AE_OFF + AWB_OFF + fixed ISO/exposure/
// gains, but the HAL silently ignored CONTROL_AWB_MODE=OFF and our colour-gain
// overrides (AWB stayed CONVERGED, gains drifted 0.7..1.2 / 2.6..4.5). Result
// was unusably dark and non-deterministic frames.
//
// What works WITH the HAL: keep AE on but pull the exposure target down by
// ~2 stops via CONTROL_AE_EXPOSURE_COMPENSATION. Units are
// CONTROL_AE_COMPENSATION_STEP (typically 1/2 EV on this device), so -6 lands
// at -3 EV. Keeps R/G/B off the 254 saturation ceiling on fingertip while
// staying bright enough on forearm.
private const val MANUAL_EC_STEPS = -6

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun RawModeScreen(
    onBack: () -> Unit,
    viewModel: RawModeViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
        }
    }
    val analysisExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    var camera by remember { mutableStateOf<Camera?>(null) }

    // Camera binding — re-runs when cameraMode changes so manual-mode capture
    // request options (CONTROL_AE_EXPOSURE_COMPENSATION = -6 ≈ -3 EV) get baked
    // into the ImageAnalysis builder. Auto mode keeps the previous behaviour
    // (AE/AWB locks set via captureRequestOptions when recording starts).
    LaunchedEffect(state.cameraMode) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = try { providerFuture.get() } catch (_: Exception) { return@addListener }
            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        AndroidSize(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                ).build()

            val analysisBuilder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(resolution)
            val extender = Camera2Interop.Extender(analysisBuilder)
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(RawModeViewModel.TARGET_FPS, RawModeViewModel.TARGET_FPS)
            )

            if (state.cameraMode == RawModeViewModel.CameraMode.Manual) {
                extender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, MANUAL_EC_STEPS
                )
            }

            // Per-frame metadata capture callback — fires on every completed
            // capture, lets us record what ISO/exposure/AE-state/AWB-state the
            // sensor reported for that frame.
            extender.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
                    viewModel.appendCameraMetadata(
                        RawRecorder.CameraMetadata(
                            timestampNs = System.nanoTime(),
                            sensorTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L,
                            isoSensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                            exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                            frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L,
                            aeState = result.get(CaptureResult.CONTROL_AE_STATE) ?: -1,
                            awbState = result.get(CaptureResult.CONTROL_AWB_STATE) ?: -1,
                            rGain = gains?.red ?: 0f,
                            gEvenGain = gains?.greenEven ?: 0f,
                            gOddGain = gains?.greenOdd ?: 0f,
                            bGain = gains?.blue ?: 0f
                        )
                    )
                }
            })

            val analysis = analysisBuilder.build()
                .also { it.setAnalyzer(analysisExecutor, viewModel.analyzer) }

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            try {
                provider.unbindAll()
                val cam = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, analysis
                )
                cam.cameraControl.enableTorch(true)
                camera = cam
            } catch (_: Exception) {}
        }, ContextCompat.getMainExecutor(context))
    }

    // Lock AE/AWB the moment recording starts, regardless of mode. In Auto this
    // freezes whatever the AE happened to converge on. In Manual (AE on, biased
    // dimmer via exposure compensation) it freezes the dimmed exposure target so
    // it can't drift mid-recording.
    LaunchedEffect(state.isRecording, state.cameraMode, camera) {
        val cam = camera ?: return@LaunchedEffect
        val c2 = Camera2CameraControl.from(cam.cameraControl)
        val opts = CaptureRequestOptions.Builder().apply {
            setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, state.isRecording)
            setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, state.isRecording)
        }.build()
        c2.captureRequestOptions = opts
    }

    DisposableEffect(Unit) {
        onDispose {
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                try { providerFuture.get().unbindAll() } catch (_: Exception) {}
            }, ContextCompat.getMainExecutor(context))
            analysisExecutor.shutdown()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Button(
                    onClick = if (state.isRecording) viewModel::stop else { { viewModel.start() } },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isRecording) SurfaceElev else Accent,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = if (state.isRecording) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.isRecording) "Stop recording"
                        else "Start ${state.targetSec.roundToInt()}s recording",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            RawHeader(onBack)
            Spacer(Modifier.height(8.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(SurfaceDark)
            ) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                HeatmapOverlay(state, Modifier.fillMaxSize())
                BestTileMarker(state, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(10.dp))

            if (!state.isRecording) {
                SiteRow(state.site, viewModel::setSite)
                Spacer(Modifier.height(8.dp))
                CameraModeRow(state.cameraMode, viewModel::setCameraMode)
                Spacer(Modifier.height(8.dp))
                DurationRow(state.targetSec, viewModel::setDurationSec)
                Spacer(Modifier.height(8.dp))
                SweepToggleRow(state.sweepMode, viewModel::setSweepMode)
                Spacer(Modifier.height(10.dp))
            }

            ChannelRow(state)
            Spacer(Modifier.height(8.dp))

            LiveDiagnosticPanel(state)
            Spacer(Modifier.height(8.dp))

            PulseStrengthBar(state)
            Spacer(Modifier.height(8.dp))

            PositionScoutHint(state)
            Spacer(Modifier.height(10.dp))

            if (state.isRecording && state.sweepPrompt.isNotBlank()) {
                SweepPromptCard(state.sweepPrompt)
                Spacer(Modifier.height(10.dp))
            }

            ProgressBlock(state)

            Spacer(Modifier.weight(1f))

            ExplainerOrPath(state)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun RawHeader(onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Spacer(Modifier.width(4.dp))
        Column {
            Text(
                "Raw Mode",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                "60 FPS, R+G+B per tile, 16×12 grid. For research recordings.",
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SiteRow(selected: RawModeViewModel.Site, onPick: (RawModeViewModel.Site) -> Unit) {
    Column {
        Text("Site", color = OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (s in RawModeViewModel.Site.values()) {
                val label = when (s) {
                    RawModeViewModel.Site.Fingertip -> "Fingertip"
                    RawModeViewModel.Site.Palm -> "Palm"
                    RawModeViewModel.Site.ForearmVolar -> "Forearm volar"
                    RawModeViewModel.Site.ForearmDorsal -> "Forearm dorsal"
                    RawModeViewModel.Site.Other -> "Other"
                }
                Chip(text = label, selected = s == selected) { onPick(s) }
            }
        }
    }
}

@Composable
private fun DurationRow(current: Float, onPick: (Float) -> Unit) {
    Column {
        Text("Duration", color = OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in listOf(60f, 120f, 180f, 240f)) {
                Chip(text = "${s.roundToInt()}s", selected = (s - current).let { if (it < 0) -it else it } < 0.5f) {
                    onPick(s)
                }
            }
        }
    }
}

@Composable
private fun CameraModeRow(
    selected: RawModeViewModel.CameraMode,
    onSet: (RawModeViewModel.CameraMode) -> Unit
) {
    Column {
        Text("Camera mode", color = OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in RawModeViewModel.CameraMode.values()) {
                val label = when (m) {
                    RawModeViewModel.CameraMode.Auto -> "Auto"
                    RawModeViewModel.CameraMode.Manual -> "Manual · −2 EV"
                }
                Chip(text = label, selected = m == selected) { onSet(m) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (selected == RawModeViewModel.CameraMode.Manual)
                "AE biased −2 stops via exposure compensation, locked on Start. Keeps the red channel off the 254 saturation ceiling on fingertip — full AE_OFF doesn't work on this device's HAL."
            else
                "Camera auto-adjusts ISO + exposure + colour gains. AE/AWB lock when recording starts, but freeze at whatever the AE happened to pick.",
            color = OnSurfaceMuted,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun SweepToggleRow(on: Boolean, onSet: (Boolean) -> Unit) {
    Column {
        Text("Pressure sweep", color = OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(text = "Off", selected = !on) { onSet(false) }
            Chip(text = "On — guided", selected = on) { onSet(true) }
        }
        if (on) {
            Spacer(Modifier.height(4.dp))
            Text(
                "5 s firm · 5 s released · 15 s slow vary · then hold at strongest pulse",
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun PulseStrengthBar(state: RawModeViewModel.UiState) {
    // Best-tile AC: std of last ~2 s of green-channel mean for the strongest
    // tile in the grid. Independent of how many other tiles are dark/saturated,
    // so it tracks "how good is the best spot right now" rather than a noisy
    // whole-frame average.
    val ps = state.bestTileAc
    val ref = state.bestTileAcMaxSeen.coerceAtLeast(0.5f)
    val frac = (ps / ref).coerceIn(0f, 1f)
    val barColor = when {
        ps > 1.5f -> Good
        ps > 0.6f -> Warn
        else -> OnSurfaceMuted
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceElev)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pulse strength — best tile (G)", color = Color.White, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(
                "%.2f / %.2f".format(ps, ref),
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { frac },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = barColor,
            trackColor = Color.White.copy(alpha = 0.10f)
        )
    }
}

@Composable
private fun PositionScoutHint(state: RawModeViewModel.UiState) {
    val haveBest = state.bestTileRow >= 0 && state.bestTileAc > 0.3f
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceElev)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Position scout", color = Color.White, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(
                if (haveBest) "best tile row ${state.bestTileRow}, col ${state.bestTileCol} · AC ${"%.2f".format(state.bestTileAc)}"
                else "no signal yet",
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (haveBest)
                "Slide the phone laterally — the bright region on the preview shows where the pulse is. " +
                    "When the heat-map is bright and centered, you've found the lateral sweet-spot."
            else
                "Place the phone on skin and wait ~2 s. The preview will overlay a heat-map showing " +
                    "which areas are picking up the pulse. Slide laterally to maximize the bright region.",
            color = OnSurfaceMuted,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)
        )
    }
}

/**
 * Live diagnostic panel — three rows of information that update every 100 ms
 * even when not recording, so the user can debug what the camera is doing and
 * whether the signal contains a pulse before committing to a recording.
 *
 *   Row 1 — scrolling PPG waveform (last ~4 s of best-tile, high-passed at
 *           ~0.7 Hz). Pulse is a periodic wiggle here, not on the raw RGB
 *           numbers (those are dominated by AC + DC drift).
 *   Row 2 — live BPM derived from peak detection on the trace; pulse-amplitude
 *           std and the strength-vs-max ratio.
 *   Row 3 — camera metadata as reported by CaptureResult: ISO, exposure time
 *           in ms, AWB R/G/B gains, AE/AWB state. If we asked for Manual and
 *           AWB state is anything other than 0 (INACTIVE) the HAL has
 *           overridden us — the headline diagnostic.
 */
@Composable
private fun LiveDiagnosticPanel(state: RawModeViewModel.UiState) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceElev)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Live signal — best tile (HPF G)",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (state.liveBpm > 0f) "${state.liveBpm.roundToInt()} bpm"
                else "— bpm",
                color = if (state.liveBpm > 0f) Good else OnSurfaceMuted,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
        }
        Spacer(Modifier.height(6.dp))
        PpgStripChart(
            trace = state.ppgTrace,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceDark)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Camera (live from CaptureResult)",
            color = OnSurfaceMuted,
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(4.dp))
        val expMs = state.liveExposureNs / 1e6f
        val aeLabel = aeStateLabel(state.liveAeState)
        val awbLabel = awbStateLabel(state.liveAwbState)
        val awbBad = state.cameraMode == RawModeViewModel.CameraMode.Manual &&
            state.liveAwbState !in setOf(0, 3) && state.liveAwbState != -1
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetaCell("ISO", "${state.liveIso}")
            MetaCell("Exp", "%.2f ms".format(expMs))
            MetaCell("AE", aeLabel, tint = if (state.isRecording && state.liveAeState != 3) Warn else null)
            MetaCell("AWB", awbLabel, tint = if (awbBad) Warn else null)
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetaCell("R gain", "%.2f".format(state.liveRGain))
            MetaCell("G gain", "%.2f".format(state.liveGGain))
            MetaCell("B gain", "%.2f".format(state.liveBGain))
            MetaCell("FPS", "%.0f".format(state.sampleRateHz))
        }
    }
}

@Composable
private fun MetaCell(label: String, value: String, tint: Color? = null) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, color = OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
        Text(
            value,
            color = tint ?: Color.White,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

private fun aeStateLabel(s: Int): String = when (s) {
    -1 -> "—"
    0 -> "INACT"
    1 -> "SRCH"
    2 -> "CONV"
    3 -> "LOCK"
    4 -> "FLASH"
    5 -> "PRECP"
    else -> s.toString()
}

private fun awbStateLabel(s: Int): String = when (s) {
    -1 -> "—"
    0 -> "INACT"
    1 -> "SRCH"
    2 -> "CONV"
    3 -> "LOCK"
    else -> s.toString()
}

/**
 * Scrolling polyline of the HPF'd green-channel signal. Auto-scales vertically
 * to the peak-to-peak of the current trace so a weak pulse is still visible.
 * Zero-line drawn for reference.
 */
@Composable
private fun PpgStripChart(trace: FloatArray, modifier: Modifier) {
    Canvas(modifier) {
        val n = trace.size
        if (n < 2) return@Canvas
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (v in trace) { if (v < lo) lo = v; if (v > hi) hi = v }
        if (!lo.isFinite() || !hi.isFinite() || hi - lo < 1e-4f) return@Canvas
        val pad = (hi - lo) * 0.15f
        lo -= pad; hi += pad
        val w = size.width
        val h = size.height
        // Zero line.
        val zeroY = h - (0f - lo) / (hi - lo) * h
        if (zeroY in 0f..h) {
            drawLine(
                color = Color.White.copy(alpha = 0.10f),
                start = Offset(0f, zeroY),
                end = Offset(w, zeroY),
                strokeWidth = 1f
            )
        }
        val path = androidx.compose.ui.graphics.Path()
        val xStep = w / (n - 1).toFloat()
        for (i in 0 until n) {
            val v = trace[i]
            val y = h - (v - lo) / (hi - lo) * h
            val x = i * xStep
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = Color(0xFF81C784),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f)
        )
    }
}

/**
 * Translucent heat-map overlay rendered on top of the camera preview. Each tile
 * is filled with a colour whose alpha tracks its rolling green-channel AC,
 * normalized to the strongest tile in the current frame. This lets the user
 * slide the phone laterally and watch the bright region track the actual pulse,
 * rather than guessing from raw brightness which only reflects the flash spot.
 */
@Composable
private fun HeatmapOverlay(state: RawModeViewModel.UiState, modifier: Modifier) {
    val tileAc = state.tileAc
    val cols = state.gridCols
    val rows = state.gridRows
    if (tileAc.isEmpty()) return
    var maxAc = 0f
    for (v in tileAc) if (v > maxAc) maxAc = v
    if (maxAc < 0.05f) return
    Canvas(modifier) {
        val tileW = size.width / cols
        val tileH = size.height / rows
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val v = (tileAc[r * cols + c] / maxAc).coerceIn(0f, 1f)
                if (v < 0.15f) continue
                // viridis-ish gradient from dim teal -> green -> yellow
                val red = (v * v).coerceIn(0f, 1f)
                val grn = (0.4f + 0.6f * v).coerceIn(0f, 1f)
                val blu = (0.5f - 0.5f * v).coerceIn(0f, 1f)
                val alpha = (0.05f + v * 0.45f).coerceIn(0f, 0.55f)
                drawRect(
                    color = Color(red, grn, blu, alpha),
                    topLeft = Offset(c * tileW, r * tileH),
                    size = Size(tileW, tileH)
                )
            }
        }
    }
}

/** Outlines the single best-AC tile so the user knows where the live pulse-strength
 *  bar's reading is coming from. */
@Composable
private fun BestTileMarker(state: RawModeViewModel.UiState, modifier: Modifier) {
    val r = state.bestTileRow
    val c = state.bestTileCol
    if (r < 0 || c < 0 || state.bestTileAc < 0.3f) return
    val cols = state.gridCols
    val rows = state.gridRows
    Canvas(modifier) {
        val tileW = size.width / cols
        val tileH = size.height / rows
        val stroke = 3f
        drawRect(
            color = Color.White,
            topLeft = Offset(c * tileW + stroke / 2, r * tileH + stroke / 2),
            size = Size(tileW - stroke, tileH - stroke),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
        )
    }
}

@Composable
private fun SweepPromptCard(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Accent)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        )
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Accent else SurfaceElev)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun ChannelRow(state: RawModeViewModel.UiState) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceElev)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChannelDot("R", state.centerLumaR, Color(0xFFE57373))
        Spacer(Modifier.width(12.dp))
        ChannelDot("G", state.centerLumaG, Color(0xFF81C784))
        Spacer(Modifier.width(12.dp))
        ChannelDot("B", state.centerLumaB, Color(0xFF64B5F6))
        Spacer(Modifier.weight(1f))
        Text(
            "${state.sampleRateHz.roundToInt()} Hz · ${state.sampleCount}",
            color = OnSurfaceMuted,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun ChannelDot(label: String, value: Float, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val alarm = when {
            value > 250f -> Bad      // saturated
            value > 30f -> Good
            value > 8f -> Warn
            else -> OnSurfaceMuted
        }
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(alarm)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            "$label ${value.roundToInt()}",
            color = color,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun ProgressBlock(state: RawModeViewModel.UiState) {
    Column {
        Row {
            Text(
                "${formatRawTime(state.elapsedSec)} / ${formatRawTime(state.targetSec)}",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (state.isRecording) "AE/AWB locked · ${state.targetFps} fps target"
                else "AE/AWB will lock on Start",
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { state.progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = Accent,
            trackColor = Color.White.copy(alpha = 0.10f)
        )
    }
}

@Composable
private fun ExplainerOrPath(state: RawModeViewModel.UiState) {
    val csv = state.csvPath
    val complete = !state.isRecording && state.elapsedSec > 0f && csv != null
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceElev)
            .padding(14.dp)
    ) {
        if (complete && csv != null) {
            Text("Recording saved", color = OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(2.dp))
            Text(csv, color = Color.White, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp))
            Spacer(Modifier.height(8.dp))
            Text("Pull from your PC:", color = OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
            Text(
                "adb pull \"$csv\"",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)
            )
        } else {
            Text(
                "What Raw Mode captures",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "60 FPS · 16×12 tile grid · per-tile R, G, B mean and within-tile std. " +
                    "Heat-map overlays the preview with which tiles are picking up pulse signal — " +
                    "slide the phone laterally to maximize the bright region before tapping Start.",
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)
            )
        }
    }
}

private fun formatRawTime(sec: Float): String {
    val s = sec.coerceAtLeast(0f).toInt()
    val m = s / 60
    val r = s % 60
    return "%d:%02d".format(m, r)
}
