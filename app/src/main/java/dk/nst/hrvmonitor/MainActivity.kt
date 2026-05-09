package dk.nst.hrvmonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import dk.nst.hrvmonitor.ui.MeasurementScreen
import dk.nst.hrvmonitor.ui.theme.HrvMonitorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        setContent {
            HrvMonitorTheme {
                MeasurementScreen()
            }
        }
    }
}
