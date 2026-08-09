package com.jamesm92.nomadportal.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// Dark-only by design (matches NomadPortal's terminal aesthetic — see
// Color.kt). isSystemInDarkTheme() is intentionally not branched on: there
// is no light variant to fall back to.
//
// Redesign (color tokens): fills in the ~21 of Material 3's ~30
// colorScheme roles that were previously left at Compose's stock purple
// default — confirmed real, currently-visible impact, not just
// theoretical coverage: NavigationBar's selected-tab indicator
// (NomadNavHost.kt) reads secondaryContainer/onSecondaryContainer by
// default, and every HorizontalDivider in this app (used throughout
// list screens as row separators) reads outlineVariant by default —
// both were rendering in stock Material purple before this. Container/
// tonal roles are derived from this app's own existing 12-token palette
// (Color.kt) via lerp(background, accent, ratio) rather than hand-picked
// new hex literals — the android-compose-app-design skill's own "derive
// variants from theme colors, don't invent new literals" guidance,
// applied with a real interpolation utility instead of eyeballing.
private val NomadColorScheme = darkColorScheme(
    primary = NomadAccent,
    onPrimary = NomadBg,
    primaryContainer = lerp(NomadBg2, NomadAccent, 0.3f),
    onPrimaryContainer = NomadText,
    inversePrimary = lerp(NomadAccent, Color.White, 0.25f),

    secondary = NomadAccent2,
    onSecondary = NomadBg,
    secondaryContainer = lerp(NomadBg2, NomadAccent2, 0.3f),
    onSecondaryContainer = NomadText,

    // NomadPortalPurple — the app's own logo/portal-doorway accent
    // (AppLogo.kt), not a new invention — is the natural tertiary: a
    // real third brand hue distinct from the primary blue/secondary
    // green pair.
    tertiary = NomadPortalPurple,
    onTertiary = NomadBg,
    tertiaryContainer = lerp(NomadBg2, NomadPortalPurple, 0.3f),
    onTertiaryContainer = NomadText,

    error = NomadError,
    onError = NomadBg,
    errorContainer = lerp(NomadBg2, NomadError, 0.3f),
    onErrorContainer = NomadText,

    background = NomadBg,
    onBackground = NomadText,

    surface = NomadBg2,
    onSurface = NomadText,
    surfaceVariant = NomadBg3,
    onSurfaceVariant = NomadTextDim,
    surfaceTint = NomadAccent,

    outline = NomadBorder,
    outlineVariant = lerp(NomadBg2, NomadBorder, 0.5f),
    scrim = Color.Black,

    inverseSurface = NomadText,
    inverseOnSurface = NomadBg,

    // Elevation via tone, not shadow (Material 3's own model — see the
    // design skill's Rule 2): each tier a little lighter than the last,
    // derived from the existing background tokens/NomadText rather than
    // invented.
    surfaceDim = NomadBg,
    surfaceBright = NomadBg3,
    surfaceContainerLowest = NomadBg,
    surfaceContainerLow = NomadBg2,
    surfaceContainer = NomadBg3,
    surfaceContainerHigh = lerp(NomadBg3, NomadText, 0.06f),
    surfaceContainerHighest = lerp(NomadBg3, NomadText, 0.12f),
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
