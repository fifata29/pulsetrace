package dk.nst.hrvmonitor.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size as AndroidSize
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import dk.nst.hrvmonitor.ui.theme.OnSurfaceMuted
import dk.nst.hrvmonitor.ui.theme.Pulse
import dk.nst.hrvmonitor.ui.theme.SurfaceDark
import dk.nst.hrvmonitor.ui.theme.SurfaceElev
import dk.nst.hrvmonitor.viewmodel.MeasurementViewModel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Composable
fun MeasurementScreen(viewModel: MeasurementViewModel = viewModel()) {
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
            ContentLayout(state, viewModel, padding)
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
    padding: androidx.compose.foundation.layout.PaddingValues
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Header(state.elapsedSec, state.isMeasuring)
        Spacer(Modifier.height(8.dp))

        CameraSection(
            isMeasuring = state.isMeasuring,
            analyzer = viewModel.analyzer
        )
        Spacer(Modifier.height(8.dp))

        BpmHero(
            bpm = state.metrics.bpm,
            isMeasuring = state.isMeasuring,
            elapsedSec = state.elapsedSec,
            goodSec = state.goodSec,
            targetGoodSec = state.targetGoodSec,
            progress = state.progress,
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
private fun Header(elapsed: Float, isMeasuring: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "PulseTrace",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                if (isMeasuring) "Measuring · hold steady"
                else "Tap Start, then cover the rear lens with a fingertip",
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.labelSmall
            )
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
                if (isMeasuring) "Stop" else "Start measurement (50 s good signal)",
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
 * Live camera preview + lifecycle. Camera is only bound (and torch only on) while
 * [isMeasuring] is true. While measuring, draws a centered ROI guide aligned with
 * PpgAnalyzer's analysis window.
 */
@Composable
private fun CameraSection(
    isMeasuring: Boolean,
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

    Box(
        modifier
            .fillMaxWidth()
            .height(95.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceDark)
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )
        if (isMeasuring) {
            Canvas(Modifier.fillMaxSize()) {
                val side = minOf(size.width, size.height) * 0.55f
                val topLeft = Offset((size.width - side) / 2, (size.height - side) / 2)
                drawRect(
                    color = Color.White.copy(alpha = 0.55f),
                    topLeft = topLeft,
                    size = Size(side, side),
                    style = Stroke(width = 2.5f)
                )
                val tick = side * 0.18f
                val cx = size.width / 2; val cy = size.height / 2
                drawLine(Color.White.copy(alpha = 0.55f),
                    Offset(cx - tick, cy), Offset(cx + tick, cy), strokeWidth = 2.5f)
                drawLine(Color.White.copy(alpha = 0.55f),
                    Offset(cx, cy - tick), Offset(cx, cy + tick), strokeWidth = 2.5f)
            }
        } else {
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
                        "Tap Start to enable preview, torch & measurement",
                        color = OnSurfaceMuted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }

    LaunchedEffect(isMeasuring) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = try { providerFuture.get() } catch (_: Exception) { return@addListener }
            if (isMeasuring) {
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

                val preview = Preview.Builder()
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                try {
                    provider.unbindAll()
                    val camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                    camera.cameraControl.enableTorch(true)
                } catch (_: Exception) {
                    // Surface via QualityBar — ignore here.
                }
            } else {
                try { provider.unbindAll() } catch (_: Exception) {}
            }
        }, ContextCompat.getMainExecutor(context))
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
