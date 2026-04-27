package com.jakob.dmd.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DiscordDarkScheme = darkColorScheme(
    primary = DmdAccent,
    onPrimary = DmdFg,
    secondary = DmdAccent2,
    onSecondary = DmdFg,
    background = DmdBg,
    onBackground = DmdFg,
    surface = DmdBg2,
    onSurface = DmdFg,
    surfaceVariant = DmdBg3,
    onSurfaceVariant = DmdFgDim,
    error = DmdRed,
    onError = DmdFg,
)

@Composable
fun DmdTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Always-dark by design — Discord palette doesn't have a sensible light mode.
    MaterialTheme(
        colorScheme = DiscordDarkScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}
