package com.ecooctano.telemetry.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object CockpitColors {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF0D0D0D)
    val Card = Color(0xFF161616)
    val CardBorder = Color(0xFF2A2A2A)
    val Green = Color(0xFF00E676)
    val Amber = Color(0xFFFFC400)
    val Red = Color(0xFFFF5252)
    val Blue = Color(0xFF40C4FF)
    val Label = Color(0xFF9E9E9E)
    val Value = Color(0xFFFFFFFF)
}

private val CockpitScheme = darkColorScheme(
    primary = CockpitColors.Green,
    onPrimary = Color.Black,
    secondary = CockpitColors.Amber,
    onSecondary = Color.Black,
    tertiary = CockpitColors.Blue,
    onTertiary = Color.Black,
    background = CockpitColors.Background,
    onBackground = CockpitColors.Value,
    surface = CockpitColors.Surface,
    onSurface = CockpitColors.Value,
    surfaceVariant = CockpitColors.Card,
    onSurfaceVariant = CockpitColors.Label,
    surfaceContainer = CockpitColors.Surface,
    error = CockpitColors.Red,
    onError = Color.Black,
    outline = CockpitColors.CardBorder,
)

/** Tema escuro de alto contraste para leitura sob sol na pista. */
@Composable
fun EcoOctanoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CockpitScheme,
        typography = Typography(),
        content = content,
    )
}
