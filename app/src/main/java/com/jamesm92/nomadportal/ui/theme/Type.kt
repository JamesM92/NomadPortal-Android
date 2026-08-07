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
 * `1.0` reproduces the original fixed sizes below exactly. Every screen
 * that wants proportionally smaller/larger text (e.g. NodeListScreen's
 * two-line rows, BrowserScreen's Micron body text) derives its own size
 * as a fraction of `MaterialTheme.typography.bodyLarge.fontSize` rather
 * than a hardcoded sp value, so it scales along with this setting
 * instead of needing its own separate adjustable size.
 */
fun nomadTypography(scale: Float = 1f): Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = NomadMono,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp * scale,
        lineHeight = 24.sp * scale,
    ),
    titleLarge = TextStyle(
        fontFamily = NomadMono,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp * scale,
        lineHeight = 28.sp * scale,
    ),
)

/** `scale = 1.0` convenience default — most call sites want
 * [NomadPortalTheme]'s own `textScale` param instead of this directly. */
val NomadTypography = nomadTypography()
