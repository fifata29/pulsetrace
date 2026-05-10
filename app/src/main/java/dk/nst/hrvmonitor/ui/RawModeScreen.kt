package dk.nst.hrvmonitor.ui

import android.hardware.camera2.CaptureRequest
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import dk.nst.hrvmonitor.viewmodel.RawModeViewModel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

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
                    Range(RawModeViewModel.TARGET_FPS, RawModeViewModel.TARGET_FPS)
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
            } catch (_: Exception) {}
        }, ContextCompat.getMainExecutor(context))
    }

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
            }
            Spacer(Modifier.height(10.dp))

            if (!state.isRecording) {
                SiteRow(state.site, viewModel::setSite)
                Spacer(Modifier.height(8.dp))
                DurationRow(state.targetSec, viewModel::setDurationSec)
                Spacer(Modifier.height(8.dp))
                SweepToggleRow(state.sweepMode, viewModel::setSweepMode)
                Spacer(Modifier.height(10.dp))
            }

            ChannelRow(state)
            Spacer(Modifier.height(8.dp))

            PulseStrengthBar(state)
            Spacer(Modifier.height(8.dp))

            PressureBar(state)
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

@Composable
private fun SiteRow(selected: RawModeViewModel.Site, onPick: (RawModeViewModel.Site) -> Unit) {
    Column {
        Text("Site", color = OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in RawModeViewModel.Site.values()) {
                Chip(text = s.name, selected = s == selected) { onPick(s) }
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
    val ps = state.pulseStrengthG
    val ref = state.pulseStrengthMaxSeen.coerceAtLeast(0.5f)
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
            Text("Pulse strength (G)", color = Color.White, style = MaterialTheme.typography.labelMedium)
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
private fun PressureBar(state: RawModeViewModel.UiState) {
    val haveRange = state.sessionMaxDcG - state.sessionMinDcG > 1.5f
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceElev)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pressure (relative)", color = Color.White, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(
                if (haveRange) "%d%%  · DC G %.0f".format((state.pressureFrac * 100).roundToInt(), state.medianDcG)
                else "vary pressure to calibrate",
                color = OnSurfaceMuted,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { if (haveRange) state.pressureFrac else 0f },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = Accent,
            trackColor = Color.White.copy(alpha = 0.10f)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (haveRange) "Range G ${state.sessionMinDcG.roundToInt()} → ${state.sessionMaxDcG.roundToInt()}"
            else "Will populate once you've pressed firm + released during a session",
            color = OnSurfaceMuted,
            style = MaterialTheme.typography.labelSmall
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
                    "AE/AWB lock at Start. Tap Start with the chosen body site in good contact " +
                    "with the lens (light pressure for forearm, full coverage for fingertip). " +
                    "240 s of forearm at 60 FPS = ~80 MB.",
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
