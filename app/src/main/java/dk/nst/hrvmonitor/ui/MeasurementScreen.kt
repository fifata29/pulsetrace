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
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import dk.nst.hrvmonitor.ui.components.RrTachogram
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

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (hasCamera) {
            ContentLayout(state, viewModel)
        } else {
            PermissionRequest(onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) })
        }
    }
}

@Composable
private fun ContentLayout(
    state: MeasurementViewModel.UiState,
    viewModel: MeasurementViewModel
) {
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(scroll)
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Header(state.elapsedSec, state.isMeasuring)
        Spacer(Modifier.height(14.dp))

        CameraSection(
            isMeasuring = state.isMeasuring,
            analyzer = viewModel.analyzer
        )
        Spacer(Modifier.height(14.dp))

        BpmHero(bpm = state.metrics.bpm)
        Spacer(Modifier.height(14.dp))

        QualityBar(coverage = state.coverage, sampleRateHz = state.sampleRateHz)
        Spacer(Modifier.height(14.dp))

        SectionLabel("PPG signal — filtered (red) over raw (blue), peaks aligned")
        Spacer(Modifier.height(8.dp))
        SignalChart(samples = state.signal, peaks = state.peaks)
        Spacer(Modifier.height(18.dp))

        SectionLabel("RR tachogram — beat-to-beat intervals")
        Spacer(Modifier.height(8.dp))
        RrTachogram(rrMs = state.rrMs)
        Spacer(Modifier.height(18.dp))

        SectionLabel("Heart rate variability")
        Spacer(Modifier.height(8.dp))
        HrvMetricsRow(
            rmssd = state.metrics.rmssdMs,
            sdnn = state.metrics.sdnnMs,
            pnn50 = state.metrics.pnn50
        )
        BeatCount(state.metrics.validBeats, state.metrics.totalBeats)
        Spacer(Modifier.height(20.dp))

        StartStopButton(
            isMeasuring = state.isMeasuring,
            onStart = { viewModel.start() },
            onStop = { viewModel.stop() }
        )
        Spacer(Modifier.height(20.dp))
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
                if (isMeasuring) "Measuring · ${"%.1f".format(elapsed)}s"
                else "Tap Start, then cover the rear lens with a fingertip",
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        AnimatedVisibility(visible = isMeasuring) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Pulse.copy(alpha = 0.18f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    "REC",
                    color = Pulse,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = OnSurfaceMuted,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun BeatCount(valid: Int, total: Int) {
    Spacer(Modifier.height(8.dp))
    Text(
        "$valid valid beats of $total detected",
        color = OnSurfaceMuted,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun StartStopButton(
    isMeasuring: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
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

@Composable
private fun PermissionRequest(onRequest: () -> Unit) {
    Column(
        Modifier
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
 * [isMeasuring] is true. When idle, shows a placeholder so the user knows where the
 * preview will appear. While measuring, draws an ROI guide so the user can confirm
 * the fingertip is covering the analysis window.
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
            .height(170.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceDark)
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )
        if (isMeasuring) {
            // ROI guide overlay matches PpgAnalyzer's 40%-of-min-side ROI.
            Canvas(Modifier.fillMaxSize()) {
                val side = minOf(size.width, size.height) * 0.4f
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
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.Fingerprint,
                    contentDescription = null,
                    tint = OnSurfaceMuted,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Camera off",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Tap Start to enable preview, torch, and measurement",
                    color = OnSurfaceMuted,
                    style = MaterialTheme.typography.labelSmall
                )
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
