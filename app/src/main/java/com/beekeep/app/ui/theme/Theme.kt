package com.beekeep.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Yellow = Color(0xFFFFC107)
private val Black = Color(0xFF171717)
private val White = Color(0xFFFFFFFF)
private val Cream = Color(0xFFFFF8E1)

private val Light = lightColorScheme(
    primary = Yellow,
    onPrimary = Black,
    secondary = Color(0xFFFFD54F),
    onSecondary = Black,
    background = White,
    surface = White,
    onSurface = Black,
    primaryContainer = Cream,
    onPrimaryContainer = Black,
    secondaryContainer = Color(0xFFFFECB3),
    onSecondaryContainer = Black
)

private val Dark = darkColorScheme(
    primary = Yellow,
    onPrimary = Black,
    secondary = Yellow,
    onSecondary = Black,
    background = Black,
    surface = Color(0xFF202020),
    onSurface = White,
    primaryContainer = Color(0xFF4D3D00),
    onPrimaryContainer = White,
    secondaryContainer = Color(0xFF5C4700),
    onSecondaryContainer = White
)

@Composable
fun BeeKeepTheme(darkMode: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkMode) Dark else Light, content = content)
}
