package com.jamesm92.nomadportal.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jamesm92.nomadportal.R

// porting-notes.md §5: Roboto Mono Nerd Font — its Nerd Font glyph set
// makes box-drawing and Braille characters (common in Micron-rendered
// ASCII art) render flush with no sub-pixel gaps, unlike a generic
// monospace font. Used for this app's whole typography, not just Micron
// content — matches the original NomadPortal web app's terminal aesthetic
// throughout, not a Micron-only special case. Same font file passed as
// both `fontFamily`/`monospaceFontFamily` to micron2compose's `MicronPage`
// (see ui/browser/BrowserScreen.kt) — this app has no separate "regular
// sans" family to distinguish it from.
val NomadMono = FontFamily(Font(R.font.roboto_mono_nerd_font))

/**
 * [scale] is a user-adjustable multiplier (Settings → text size, backed
 * by [com.jamesm92.nomadportal.data.SettingsRepository.textScale]) —
 * `1.0` reproduces the sizes below exactly. All 15 Material 3 type-scale
 * roles are defined here (each `* scale`) so any screen that needs a
 * text size other than "default body"/"default title" picks the real
 * named role that fits (`bodyMedium`, `labelSmall`, etc.) instead of
 * hand-deriving a fraction of `bodyLarge.fontSize` — the redesign's own
 * `android-compose-app-design` skill flags that percentage-of-bodyLarge
 * pattern by name as an anti-pattern, and this app's own screens had
 * drifted into it at 61 call sites across 16 files before this. Every
 * role still scales with the user's text-size setting exactly like
 * `bodyLarge`/`titleLarge` always did — that's preserved by construction
 * (every role goes through this same `* scale`), not something the
 * migration had to work around.
 *
 * Sizes/line-heights are Material 3's own defaults for every role
 * (confirmed the app's pre-existing `bodyLarge`=16/24sp and
 * `titleLarge`=22/28sp already matched M3's defaults exactly — this is
 * "fill in the rest the same way," not a new scale). `NomadMono`
 * throughout, matching this app's own "whole app, not just body text"
 * font choice (see [NomadMono]'s own doc comment). Weight: title-tier
 * roles are `Bold` (matching `titleLarge`'s pre-existing choice — this
 * app's single bundled font file synthesizes bold fine, already proven
 * by `titleLarge` today); every other tier is `Normal` — no third,
 * `Medium`-weight tier is introduced, since M3's own default label-role
 * weight (`Medium`) isn't a distinction this app has ever made or has a
 * dedicated font file for.
 */
fun nomadTypography(scale: Float = 1f): Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = NomadMono,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp * scale,
        lineHeight = 64.sp * scale,
    ),
    displayMedium = TextStyle(
        fontFamily = NomadMono,
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp * scale,
        lineHeight = 52.sp * scale,
    ),
    displaySmall = TextStyle(
        fontFamily = NomadMono,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp * scale,
        lineHeight = 44.sp * scale,
    ),
    headlineLarge = TextStyle(
        fontFamily = NomadMono,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp * scale,
        lineHeight = 40.sp * scale,
    ),
    headlineMedium = TextStyle(
        fontFamily = NomadMono,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp * scale,
        lineHeight = 36.sp * scale,
    ),
    headlineSmall = TextStyle(
        fontFamily = NomadMono,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp * scale,
        lineHeight = 32.sp * scale,
    ),
    titleLarge = TextStyle(
        fontFamily = NomadMono,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp * scale,
        lineHeight = 28.sp * scale,
    ),
    titleMedium = TextStyle(
        fontFamily = NomadMono,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp * scale,
        lineHeight = 24.sp * scale,
    ),
    titleSmall = TextStyle(
        fontFamily = NomadMono,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp * scale,
        lineHeight = 20.sp * scale,
    ),
    bodyLarge = TextStyle(
        fontFamily = NomadMono,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp * scale,
        lineHeight = 24.sp * scale,
    ),
    bodyMedium = TextStyle(
        fontFamily = NomadMono,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp * scale,
        lineHeight = 20.sp * scale,
    ),
    bodySmall = TextStyle(
        fontFamily = NomadMono,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp * scale,
        lineHeight = 16.sp * scale,
    ),
    labelLarge = TextStyle(
        fontFamily = NomadMono,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp * scale,
        lineHeight = 20.sp * scale,
    ),
    labelMedium = TextStyle(
        fontFamily = NomadMono,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp * scale,
        lineHeight = 16.sp * scale,
    ),
    labelSmall = TextStyle(
        fontFamily = NomadMono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp * scale,
        lineHeight = 16.sp * scale,
    ),
)

/** `scale = 1.0` convenience default — most call sites want
 * [NomadPortalTheme]'s own `textScale` param instead of this directly. */
val NomadTypography = nomadTypography()
