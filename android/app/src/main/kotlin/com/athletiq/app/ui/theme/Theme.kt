package com.athletiq.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AthletiqLightColorScheme = lightColorScheme(
    primary = AthletiqColors.PrimaryCobalt,
    onPrimary = Color.White,
    primaryContainer = AthletiqColors.SurfaceContainer,
    onPrimaryContainer = AthletiqColors.PrimaryDark,
    secondary = AthletiqColors.SlateNavy,
    onSecondary = Color.White,
    secondaryContainer = AthletiqColors.SurfaceSoftBlue,
    onSecondaryContainer = AthletiqColors.TextPrimary,
    tertiary = AthletiqColors.EmeraldValid,
    onTertiary = Color.White,
    error = AthletiqColors.CrimsonError,
    onError = Color.White,
    background = AthletiqColors.SurfaceCanvas,
    onBackground = AthletiqColors.TextPrimary,
    surface = AthletiqColors.SurfaceCard,
    onSurface = AthletiqColors.TextPrimary,
    surfaceVariant = AthletiqColors.SurfaceSoftBlue,
    onSurfaceVariant = AthletiqColors.TextSecondary,
    outline = AthletiqColors.SurfaceBorder,
)

@Composable
fun AthletiqTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AthletiqLightColorScheme,
        typography = AthletiqTypography,
        content = content,
    )
}
