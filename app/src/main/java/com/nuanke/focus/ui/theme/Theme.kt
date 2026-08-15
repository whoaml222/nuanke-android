package com.nuanke.focus.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

val Terracotta = Color(0xFFE66F42)
val SunGold = Color(0xFFF3A63A)
val Sage = Color(0xFF667A55)
val SageLight = Color(0xFF93A56B)
val WarmCanvas = Color(0xFFFFF8F0)
val WarmSurface = Color(0xFFFFFDF9)
val Cocoa = Color(0xFF45372E)

private val LightColors = lightColorScheme(
    primary = Terracotta,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCC),
    onPrimaryContainer = Color(0xFF6B260E),
    secondary = Sage,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EBCF),
    onSecondaryContainer = Color(0xFF283512),
    tertiary = SunGold,
    background = WarmCanvas,
    onBackground = Cocoa,
    surface = WarmSurface,
    onSurface = Cocoa,
    surfaceVariant = Color(0xFFF4EAE1),
    outline = Color(0xFF97877A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB59A),
    secondary = Color(0xFFBECBA4),
    tertiary = Color(0xFFFFC46B),
)

private val NuankeShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun NuankeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = NuankeShapes,
        content = content,
    )
}

