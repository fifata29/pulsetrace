package dk.nst.hrvmonitor.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dk.nst.hrvmonitor.ui.theme.SurfaceElev
import dk.nst.hrvmonitor.viewmodel.MeasurementViewModel
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
            CameraBinding(viewModel)
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
        Spacer(Modifier.height(16.dp))

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
                else "Cover the rear camera with a fingertip",
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
 * Binds CameraX with torch on and a frame analyzer that streams samples to the ViewModel.
 * No preview is shown — the camera is used purely as a sensor.
 */
@Composable
private fun CameraBinding(viewModel: MeasurementViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val executor = Executors.newSingleThreadExecutor()
        providerFuture.addListener({
            val provider = providerFuture.get()
            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                )
                .build()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(resolution)
                .build()
                .also { it.setAnalyzer(executor, viewModel.analyzer) }

            try {
                provider.unbindAll()
                val camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    analysis
                )
                camera.cameraControl.enableTorch(true)
            } catch (_: Exception) {
                // ignored — surfaced via UI quality indicator
            }
        }, ContextCompat.getMainExecutor(context))
    }
}

