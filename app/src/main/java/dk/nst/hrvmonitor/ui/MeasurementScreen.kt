package dk.nst.hrvmonitor.ui

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.util.Size as AndroidSize
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dk.nst.hrvmonitor.ui.components.BpmHero
import dk.nst.hrvmonitor.ui.components.HrvMetricsRow
import dk.nst.hrvmonitor.ui.components.QualityBar
import dk.nst.hrvmonitor.ui.components.ReportSheet
import dk.nst.hrvmonitor.ui.components.SignalChart
import dk.nst.hrvmonitor.ui.theme.Accent
import dk.nst.hrvmonitor.ui.theme.OnSurfaceMuted
import dk.nst.hrvmonitor.ui.theme.Pulse
import dk.nst.hrvmonitor.ui.theme.SurfaceDark
import dk.nst.hrvmonitor.ui.theme.SurfaceElev
import dk.nst.hrvmonitor.viewmodel.MeasurementViewModel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Composable
fun MeasurementScreen(
    onOpenCalibrate: () -> Unit = {},
    viewModel: MeasurementViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCamera = granted }

    LaunchedEffect(Unit) {
        if (!hasCamera) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (hasCamera) {
                BottomActionBar(
                    isMeasuring = state.isMeasuring,
                    onStart = { viewModel.start() },
                    onStop = { viewModel.stop() }
                )
            }
        }
    ) { padding ->
        if (hasCamera) {
            ContentLayout(state, viewModel, padding, onOpenCalibrate)
        } else {
            PermissionRequest(
                modifier = Modifier.padding(padding),
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) }
            )
        }
    }

    state.report?.let { report ->
        ReportSheet(report = report, onDismiss = viewModel::dismissReport)
    }
}

@Composable
private fun ContentLayout(
    state: MeasurementViewModel.UiState,
    viewModel: MeasurementViewModel,
    padding: androidx.compose.foundation.layout.PaddingValues,
    onOpenCalibrate: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Header(state.phase, onOpenCalibrate)
        Spacer(Modifier.height(8.dp))

        CameraSection(
            phase = state.phase,
            roi = state.roi,
            gridCols = state.gridCols,
            gridRows = state.gridRows,
            analyzer = viewModel.analyzer
        )
        Spacer(Modifier.height(8.dp))

        BpmHero(
            bpm = state.metrics.bpm,
            phase = state.phase,
            elapsedSec = state.elapsedSec,
            goodSec = state.goodSec,
            targetGoodSec = state.targetGoodSec,
            targetSearchSec = state.targetSearchSec,
            searchProgress = state.searchProgress,
            measureProgress = state.measureProgress,
            isGoodSignal = state.isGoodSignal
        )
        Spacer(Modifier.height(8.dp))

        QualityBar(coverage = state.coverage, sampleRateHz = state.sampleRateHz)
        Spacer(Modifier.height(8.dp))

        SignalChart(
            samples = state.signal,
            peaks = state.peaks,
            modifier = Modifier.weight(1f, fill = true)
        )
        Spacer(Modifier.height(8.dp))

        HrvMetricsRow(
            rmssd = state.metrics.rmssdMs,
            sdnn = state.metrics.sdnnMs,
            pnn50 = state.metrics.pnn50
        )
    }
}

@Composable
private fun Header(phase: MeasurementViewModel.Phase, onOpenCalibrate: () -> Unit) {
    val active = phase == MeasurementViewModel.Phase.Searching ||
        phase == MeasurementViewModel.Phase.Measuring
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "PulseTrace",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                when (phase) {
                    MeasurementViewModel.Phase.Searching ->
                        "Finding the pulse region — hold finger steady"
                    MeasurementViewModel.Phase.Measuring ->
                        "Measuring HRV in the highlighted region"
                    else ->
                        "Tap Start: 10 s search + 50 s of clean recording"
                },
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.labelSmall
            )
        }
        if (!active) {
            IconButton(onClick = onOpenCalibrate) {
                Icon(Icons.Filled.Tune, contentDescription = "Calibrate", tint = Color.White)
            }
        }
    }
}

@Composable
private fun BottomActionBar(
    isMeasuring: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Button(
            onClick = if (isMeasuring) onStop else onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isMeasuring) SurfaceElev else Pulse,
                contentColor = Color.White
            )
        ) {
            Icon(
                imageVector = if (isMeasuring) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = null
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (isMeasuring) "Stop" else "Start measurement",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Camera permission required",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "PulseTrace measures your pulse from tiny color changes in your fingertip — it needs the rear camera and torch.",
            color = OnSurfaceMuted,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onRequest,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Pulse, contentColor = Color.White)
        ) { Text("Grant access") }
    }
}

/**
 * Live camera preview + lifecycle.
 *  - Camera + torch are bound while [phase] is Searching or Measuring.
 *  - AE/AWB are unlocked during Searching (let exposure converge), locked during
 *    Measuring (so the camera stops fighting the pulse modulation).
 *  - Phase 1: faint full-frame grid overlay so user sees the analysis window.
 *  - Phase 2: the chosen ROI bounding box drawn over the preview.
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
private fun CameraSection(
    phase: MeasurementViewModel.Phase,
    roi: MeasurementViewModel.RoiInfo?,
    gridCols: Int,
    gridRows: Int,
    analyzer: ImageAnalysis.Analyzer,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
        }
    }
    val analysisExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val cameraRef = remember { mutableStateOf<Camera?>(null) }

    val active = phase == MeasurementViewModel.Phase.Searching ||
        phase == MeasurementViewModel.Phase.Measuring

    Box(
        modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceDark)
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )
        when (phase) {
            MeasurementViewModel.Phase.Searching -> {
                Canvas(Modifier.fillMaxSize()) {
                    val gc = Color.White.copy(alpha = 0.18f)
                    for (i in 1 until gridCols) {
                        val x = size.width * i / gridCols
                        drawLine(gc, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    }
                    for (i in 1 until gridRows) {
                        val y = size.height * i / gridRows
                        drawLine(gc, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    }
                }
            }
            MeasurementViewModel.Phase.Measuring -> {
                if (roi != null) {
                    Canvas(Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val left = w * roi.colStart / gridCols
                        val right = w * (roi.colEnd + 1) / gridCols
                        val top = h * roi.rowStart / gridRows
                        val bottom = h * (roi.rowEnd + 1) / gridRows
                        drawRect(
                            color = Pulse.copy(alpha = 0.85f),
                            topLeft = Offset(left, top),
                            size = Size(right - left, bottom - top),
                            style = Stroke(width = 3f)
                        )
                        drawRect(
                            color = Pulse.copy(alpha = 0.18f),
                            topLeft = Offset(left, top),
                            size = Size(right - left, bottom - top)
                        )
                    }
                }
            }
            else -> {
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Fingerprint,
                        contentDescription = null,
                        tint = OnSurfaceMuted,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Camera off", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Tap Start — first 10 s find the optimal pulse region, then 50 s of measurement",
                            color = OnSurfaceMuted,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }

    // Bind / unbind camera and apply AE/AWB lock based on phase.
    LaunchedEffect(active) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = try { providerFuture.get() } catch (_: Exception) { return@addListener }
            if (active) {
                val resolution = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            AndroidSize(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(resolution)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, analyzer) }
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
                    cameraRef.value = cam
                } catch (_: Exception) {}
            } else {
                cameraRef.value = null
                try { provider.unbindAll() } catch (_: Exception) {}
            }
        }, ContextCompat.getMainExecutor(context))
    }

    LaunchedEffect(phase, cameraRef.value) {
        val cam = cameraRef.value ?: return@LaunchedEffect
        val locked = phase == MeasurementViewModel.Phase.Measuring
        try {
            val c2 = Camera2CameraControl.from(cam.cameraControl)
            c2.captureRequestOptions = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, locked)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, locked)
                .build()
        } catch (_: Throwable) {}
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
}
