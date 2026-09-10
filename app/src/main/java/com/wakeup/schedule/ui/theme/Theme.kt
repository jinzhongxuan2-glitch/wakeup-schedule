package com.wakeup.schedule.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// WakeUp 风格：青绿色主色 + 干净背景
val Teal = Color(0xFF26A69A)
val TealDark = Color(0xFF00897B)
val BgLight = Color(0xFFFAFAFA)
val BgDark = Color(0xFF121212)
val SurfaceDark = Color(0xFF1E1E1E)
val GridLineLight = Color(0xFFF0F0F0)
val GridLineDark = Color(0xFF2A2A2A)
val TextSecondaryLight = Color(0xFF9E9E9E)
val TextSecondaryDark = Color(0xFF8A8A8A)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB),
    onPrimaryContainer = Color(0xFF004D40),
    secondary = TealDark,
    background = BgLight,
    surface = Color.White,
    onBackground = Color(0xFF212121),
    onSurface = Color(0xFF212121),
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF616161),
    outline = Color(0xFFE0E0E0)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80CBC4),
    onPrimary = Color(0xFF00332E),
    primaryContainer = Color(0xFF00695C),
    onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = Color(0xFF80CBC4),
    background = BgDark,
    surface = SurfaceDark,
    onBackground = Color(0xFFECECEC),
    onSurface = Color(0xFFECECEC),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline = Color(0xFF3A3A3A)
)

@Composable
fun WakeUpTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
