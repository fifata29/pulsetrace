package dk.nst.hrvmonitor.ui

import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size as AndroidSize
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
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
import dk.nst.hrvmonitor.ui.theme.Pulse
import dk.nst.hrvmonitor.ui.theme.SurfaceDark
import dk.nst.hrvmonitor.ui.theme.SurfaceElev
import dk.nst.hrvmonitor.ui.theme.Warn
import dk.nst.hrvmonitor.viewmodel.CalibrationViewModel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CalibrationScreen(
    onBack: () -> Unit,
    viewModel: CalibrationViewModel = viewModel()
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
    var cameraInfoLine by remember { mutableStateOf("") }

    // Bind camera + torch + permanent analyzer once when entering this screen.
    LaunchedEffect(Unit) {
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
            Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(30, 30)
                )
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
                cameraInfoLine = describeCamera(cam)
            } catch (_: Exception) {}
        }, ContextCompat.getMainExecutor(context))
    }

    // Apply / release AE+AWB lock when isRecording flips.
    LaunchedEffect(state.isRecording, camera) {
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
                    onClick = if (state.isRecording) viewModel::stop else viewModel::start,
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
                        if (state.isRecording) "Stop calibration"
                        else "Start 2-minute recording",
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
            Header(onBack = onBack)
            Spacer(Modifier.height(8.dp))

            // Camera preview with 16x12 grid overlay.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(SurfaceDark)
            ) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )
                GridOverlay(state.gridCols, state.gridRows)
            }
            Spacer(Modifier.height(10.dp))

            StatusRow(state)
            if (cameraInfoLine.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    cameraInfoLine,
                    color = OnSurfaceMuted,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(Modifier.height(10.dp))

            ProgressBlock(state)

            Spacer(Modifier.weight(1f))

            ExplainerOrPath(state)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Spacer(Modifier.width(4.dp))
        Column {
            Text(
                "Calibration",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                "Records the full 16×12 tile grid for 2 min so we can find the best ROI for this device.",
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun GridOverlay(cols: Int, rows: Int) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val gridColor = Color.White.copy(alpha = 0.18f)
        for (i in 1 until cols) {
            val x = w * i / cols
            drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
        }
        for (i in 1 until rows) {
            val y = h * i / rows
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
    }
}

@Composable
private fun StatusRow(state: CalibrationViewModel.UiState) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceElev)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val lumaColor = when {
            state.centerLuma > 200f -> Bad     // saturated
            state.centerLuma > 80f -> Good
            state.centerLuma > 40f -> Warn
            else -> OnSurfaceMuted
        }
        Box(Modifier.size(8.dp).clip(CircleShape).background(lumaColor))
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                !state.isRecording -> "Place finger on lens, then tap Start"
                state.centerLuma > 200f -> "Centre saturated — that's expected on this phone"
                state.centerLuma < 40f -> "Lens not covered — press finger over camera & flash"
                else -> "Recording — keep finger steady"
            },
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.weight(1f))
        Text(
            "${state.sampleRateHz.roundToInt()} Hz · ${state.sampleCount}",
            color = OnSurfaceMuted,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun ProgressBlock(state: CalibrationViewModel.UiState) {
    Column {
        Row {
            Text(
                "${formatTime(state.elapsedSec)} / ${formatTime(state.targetSec)}",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (state.isRecording) "AE/AWB locked" else "AE/AWB unlocked",
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
private fun ExplainerOrPath(state: CalibrationViewModel.UiState) {
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
                "How calibration works",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "When you tap Start, AE/AWB lock at the current exposure (so the camera " +
                    "stops fighting the pulse). For 2 minutes the app records the mean red value " +
                    "of every cell in a 16×12 grid (≈ 3 MB). Pull the CSV and share it for analysis " +
                    "to find the highest-SNR region on this phone.",
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)
            )
        }
    }
}

private fun formatTime(sec: Float): String {
    val s = sec.coerceAtLeast(0f).toInt()
    val m = s / 60
    val r = s % 60
    return "%d:%02d".format(m, r)
}

@OptIn(ExperimentalCamera2Interop::class)
private fun describeCamera(cam: Camera): String {
    return try {
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val id = info.cameraId
        val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull()
        val zoom = cam.cameraInfo.intrinsicZoomRatio
        val facing = info.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
        val facingStr = when (facing) {
            CameraCharacteristics.LENS_FACING_BACK -> "BACK"
            CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
            else -> "?"
        }
        val lensHint = when {
            zoom == null -> ""
            zoom < 0.7f -> " — ultrawide"
            zoom < 1.4f -> " — main"
            else -> " — telephoto"
        }
        val zoomStr = zoom?.let { "%.2f×".format(it) } ?: "?"
        val focalStr = focal?.let { "%.2f mm".format(it) } ?: "?"
        "Camera $facingStr id=$id · zoom $zoomStr · focal $focalStr$lensHint"
    } catch (_: Throwable) {
        "Camera info unavailable"
    }
}
