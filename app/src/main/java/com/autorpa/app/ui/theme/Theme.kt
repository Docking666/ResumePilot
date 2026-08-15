package com.autorpa.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7C4DFF),
    secondary = Color(0xFF448AFF),
    tertiary = Color(0xFF00BCD4),
    background = Color(0xFF1A1A2E),
    surface = Color(0xFF16213E),
    surfaceVariant = Color(0xFF0F3460),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFFE0E0E0),
    onSurface = Color(0xFFE0E0E0),
    error = Color(0xFFCF6679),
    outline = Color(0xFF3A3A5C)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF7C4DFF),
    secondary = Color(0xFF448AFF),
    tertiary = Color(0xFF00BCD4),
    background = Color(0xFFF5F5F5),
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    error = Color(0xFFB00020)
)

@Composable
fun AutoRPATheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}