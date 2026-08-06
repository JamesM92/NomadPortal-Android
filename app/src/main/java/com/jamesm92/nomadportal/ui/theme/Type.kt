package com.jamesm92.nomadportal.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// TODO(porting-notes.md §5): bundle Roboto Mono Nerd Font as this app's
// monospace family once Micron rendering (micron2compose) needs its
// box-drawing/Braille glyph coverage for flush-aligned ASCII art. Using the
// platform monospace font for now — fine for this placeholder screen, but
// swap before any real Micron content is rendered.
val NomadMono = FontFamily.Monospace

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
