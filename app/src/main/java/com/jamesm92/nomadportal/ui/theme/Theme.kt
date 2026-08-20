package com.jamesm92.nomadportal.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.jamesm92.nomadportal.data.ThemeMode

// Was dark-only by design (matches NomadPortal's terminal aesthetic —
// see Color.kt) — a real light variant was added once Settings' own
// theme-mode toggle needed one (closing a real Columba-parity gap; see
// NomadLightColorScheme below and NomadPortalTheme's own ThemeMode
// handling for isSystemInDarkTheme() actually being consulted now).
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
private val NomadDarkColorScheme = darkColorScheme(
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

// Light counterpart — see Color.kt's own doc comment on NomadBgLight/
// NomadBg2Light/NomadBg3Light/NomadBorderLight for why only background/
// surface/border/body-text tokens are genuinely new here: the accent
// hues (primary/secondary/tertiary/error) and NomadTextDim are reused
// as-is from the dark scheme for brand consistency across both themes,
// and NomadBg (already near-black, #131313) doubles as this scheme's
// own primary body-text color rather than a separately-invented
// "NomadTextLight" that would just duplicate it.
private val NomadLightColorScheme = lightColorScheme(
    primary = NomadAccent,
    onPrimary = NomadBg,
    primaryContainer = lerp(NomadBgLight, NomadAccent, 0.2f),
    onPrimaryContainer = NomadBg,
    inversePrimary = lerp(NomadAccent, Color.Black, 0.25f),

    secondary = NomadAccent2,
    onSecondary = NomadBg,
    secondaryContainer = lerp(NomadBgLight, NomadAccent2, 0.2f),
    onSecondaryContainer = NomadBg,

    tertiary = NomadPortalPurple,
    onTertiary = NomadBg,
    tertiaryContainer = lerp(NomadBgLight, NomadPortalPurple, 0.2f),
    onTertiaryContainer = NomadBg,

    error = NomadError,
    onError = NomadBg,
    errorContainer = lerp(NomadBgLight, NomadError, 0.2f),
    onErrorContainer = NomadBg,

    background = NomadBgLight,
    onBackground = NomadBg,

    surface = NomadBgLight,
    onSurface = NomadBg,
    surfaceVariant = NomadBg3Light,
    onSurfaceVariant = NomadTextDim,
    surfaceTint = NomadAccent,

    outline = NomadBorderLight,
    outlineVariant = lerp(NomadBgLight, NomadBorderLight, 0.5f),
    scrim = Color.Black,

    inverseSurface = NomadBg,
    inverseOnSurface = NomadBgLight,

    surfaceDim = NomadBg3Light,
    surfaceBright = NomadBgLight,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = NomadBgLight,
    surfaceContainer = NomadBg2Light,
    surfaceContainerHigh = NomadBg3Light,
    surfaceContainerHighest = lerp(NomadBg3Light, NomadBg, 0.08f),
)

/**
 * @param themeMode [ThemeMode.SYSTEM] follows the OS's own light/dark
 * setting via [isSystemInDarkTheme]; [ThemeMode.LIGHT]/[ThemeMode.DARK]
 * pin it regardless of the OS. Defaults to [ThemeMode.DARK] here — matches
 * [com.jamesm92.nomadportal.data.SettingsRepository.themeMode]'s own real
 * default (see that property's doc comment for why); MainActivity always
 * passes an explicit value from that Flow in practice, so this default is
 * only ever exercised by a caller that doesn't (a Preview, a future test).
 * @param textScale User-adjustable multiplier (Settings → text size) —
 * see [nomadTypography]/[com.jamesm92.nomadportal.data.SettingsRepository.textScale].
 */
@Composable
fun NomadPortalTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    textScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (isDark) NomadDarkColorScheme else NomadLightColorScheme,
        typography = nomadTypography(textScale),
        content = content,
    )
}
