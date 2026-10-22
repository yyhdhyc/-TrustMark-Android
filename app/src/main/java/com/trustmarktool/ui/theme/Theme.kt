package com.trustmarktool.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val ColorWhite = Color(0xFFFFFFFF)

private val LightColors = lightColorScheme(
    primary = Teal40,
    onPrimary = ColorWhite,
    primaryContainer = Color(0xFF9CF2E3),
    onPrimaryContainer = Teal10,
    secondary = Slate40,
    onSecondary = ColorWhite,
    secondaryContainer = Color(0xFFCCE8E1),
    onSecondaryContainer = Slate10,
    tertiary = Blue40,
    onTertiary = ColorWhite,
    tertiaryContainer = Color(0xFFC2E8FB),
    onTertiaryContainer = Color(0xFF001F2A),
    background = NeutralLight,
    onBackground = Color(0xFF191C1B),
    surface = SurfaceLight,
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF6F7977),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Teal80,
    onPrimary = Teal10,
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFF74F8E5),
    secondary = Slate80,
    onSecondary = Slate10,
    secondaryContainer = Color(0xFF334B47),
    onSecondaryContainer = Color(0xFFCCE8E1),
    tertiary = Blue80,
    onTertiary = Color(0xFF073543),
    tertiaryContainer = Color(0xFF264B5B),
    onTertiaryContainer = Color(0xFFC2E8FB),
    background = NeutralDark,
    onBackground = Color(0xFFE0E3E1),
    surface = SurfaceDark,
    onSurface = Color(0xFFE0E3E1),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBEC9C5),
    outline = Color(0xFF899390),
    error = Color(0xFFFFB4AB),
)

@Composable
fun TrustMarkToolTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = TrustMarkTypography,
        content = content,
    )
}
