package com.javimetallab.morselightcrypt.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Colour of the light itself, wherever the screen shows it lit. */
val LampColor = Color(0xFFFFC94D)

// Same palette as PaperCript.
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0B1B33),
    secondary = Color(0xFF9DD1A5),
    background = Color(0xFF111119),
    surface = Color(0xFF1B1B2A),
    surfaceVariant = Color(0xFF262636),
    error = Color(0xFFFF8A80)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2D5FA8),
    secondary = Color(0xFF2E7D4F),
    background = Color(0xFFF6F6FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE6E6EE)
)

/** Dots, dashes and ciphertext: monospaced and widely spaced. */
val MorseStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 18.sp,
    lineHeight = 26.sp,
    letterSpacing = 2.sp,
    fontWeight = FontWeight.Medium
)

@Composable
fun MorseLightCryptTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = Typography(),
        content = content
    )
}
