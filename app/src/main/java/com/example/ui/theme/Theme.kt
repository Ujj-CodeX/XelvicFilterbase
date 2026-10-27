package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = XelvicCyan,
    onPrimary = Color(0xFF0A0E12),
    secondary = XelvicViolet,
    onSecondary = Color.White,
    tertiary = XelvicEmerald,
    background = XelvicBackground,
    onBackground = TextPrimary,
    surface = XelvicSurface,
    onSurface = TextPrimary,
    surfaceVariant = XelvicSurfaceVariant,
    onSurfaceVariant = TextSecondary
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
