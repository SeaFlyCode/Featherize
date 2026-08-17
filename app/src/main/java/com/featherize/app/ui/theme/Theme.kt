package com.featherize.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val FeatherizeColors = darkColorScheme(
    primary = MintPrimary,
    onPrimary = MintOnPrimary,
    primaryContainer = MintPrimaryContainer,
    onPrimaryContainer = MintPrimary,
    secondary = GreenSecondary,
    onSecondary = OnSecondary,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceContainerDark,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighDark,
    outline = OutlineDark,
    error = ErrorRed,
    onError = OnErrorDark,
)

/** Featherize always ships its dark mint identity — no light mode, no Material You dynamic tint. */
@Composable
fun FeatherizeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FeatherizeColors,
        typography = FeatherizeTypography,
        content = content,
    )
}
