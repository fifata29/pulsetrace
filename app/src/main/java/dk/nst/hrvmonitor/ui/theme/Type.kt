package dk.nst.hrvmonitor.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val AppTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Light, fontSize = 96.sp, letterSpacing = (-2).sp),
    displayMedium = TextStyle(fontWeight = FontWeight.Light, fontSize = 64.sp, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.8.sp)
)
