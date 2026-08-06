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

val NomadTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = NomadMono,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = NomadMono,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
)
