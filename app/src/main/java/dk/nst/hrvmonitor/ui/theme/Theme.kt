package dk.nst.hrvmonitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = Pulse,
    onPrimary = OnSurface,
    secondary = Accent,
    onSecondary = OnSurface,
    background = BackgroundDark,
    onBackground = OnSurface,
    surface = SurfaceDark,
    onSurface = OnSurface,
    surfaceVariant = SurfaceElev,
    onSurfaceVariant = OnSurfaceMuted,
    error = Bad
)

@Composable
fun HrvMonitorTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        content = content
    )
}
