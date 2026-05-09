package dk.nst.hrvmonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import dk.nst.hrvmonitor.ui.CalibrationScreen
import dk.nst.hrvmonitor.ui.MeasurementScreen
import dk.nst.hrvmonitor.ui.theme.HrvMonitorTheme

private enum class Screen { Measure, Calibrate }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        setContent {
            HrvMonitorTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    var screen by remember { mutableStateOf(Screen.Measure) }
    when (screen) {
        Screen.Measure -> MeasurementScreen(
            onOpenCalibrate = { screen = Screen.Calibrate }
        )
        Screen.Calibrate -> CalibrationScreen(
            onBack = { screen = Screen.Measure }
        )
    }
}
