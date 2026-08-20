package com.jamesm92.nomadportal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A plain filled circle, [color]-coded — real/live vs. dead/failed, or
 * similar. Promoted here from a private copy in NetworkScreen.kt (its
 * interface-status rows) once BrowserScreen.kt's own page-fetch status
 * indicator (cached-vs-live, per that screen's own doc comment) became a
 * second real call site — this project's own "promote only after real
 * reuse" convention (see the nomadportal-android-conventions skill).
 */
@Composable
fun StatusDot(color: Color, size: Dp = 10.dp) {
    Canvas(modifier = Modifier.size(size).clip(CircleShape)) {
        drawCircle(color = color)
    }
}
