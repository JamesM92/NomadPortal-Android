package com.jamesm92.nomadportal.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Dark-only by design (matches NomadPortal's terminal aesthetic — see
// Color.kt). isSystemInDarkTheme() is intentionally not branched on: there
// is no light variant to fall back to.
private val NomadColorScheme = darkColorScheme(
    primary = NomadAccent,
    secondary = NomadAccent2,
    background = NomadBg,
    surface = NomadBg2,
    surfaceVariant = NomadBg3,
    onBackground = NomadText,
    onSurface = NomadText,
    outline = NomadBorder,
    error = NomadError,
)

/** @param textScale User-adjustable multiplier (Settings → text size) —
 * see [nomadTypography]/[com.jamesm92.nomadportal.data.SettingsRepository.textScale]. */
@Composable
fun NomadPortalTheme(textScale: Float = 1f, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NomadColorScheme,
        typography = nomadTypography(textScale),
        content = content,
    )
}
