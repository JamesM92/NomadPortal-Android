package com.jamesm92.nomadportal.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A number-of-minutes cell, built on `BasicTextField` +
 * `OutlinedTextFieldDefaults.DecorationBox` rather than the convenience
 * `OutlinedTextField` — that convenience composable exposes no
 * `contentPadding`, so a compact table cell built on it clips text no
 * matter how the outer `Modifier` is tuned (see the
 * `android-compose-compact-fields` skill for the full writeup; same
 * root cause and fix as `BrowserScreen`'s address bar and
 * `SearchField`). Commits on focus loss or the keyboard's Done action,
 * clamped to [1, 1440] minutes — or snapped to 0 when [allowZero] and
 * the typed value is 0/blank/invalid.
 *
 * Promoted here from SettingsScreen.kt (its original home, still where
 * most call sites live) once HomeScreen.kt's hosted-node announce
 * config needed the identical field a fourth/fifth time — same
 * promote-after-repeated-need convention as [CompactTextField].
 */
@Composable
fun MinutesField(
    seconds: Int,
    allowZero: Boolean,
    onCommit: (seconds: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(seconds) { mutableStateOf((seconds / 60).toString()) }
    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }

    fun commit() {
        val minutes = text.toIntOrNull()
        val clampedMinutes = when {
            minutes == null -> seconds / 60
            minutes <= 0 -> if (allowZero) 0 else 1
            else -> minutes.coerceAtMost(24 * 60)
        }
        text = clampedMinutes.toString()
        onCommit(clampedMinutes * 60)
    }

    BasicTextField(
        value = text,
        onValueChange = { new -> if (new.length <= 5 && new.all { it.isDigit() }) text = new },
        modifier = modifier.onFocusChanged { if (!it.isFocused) commit() },
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.75f,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        singleLine = true,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interactionSource,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            commit()
            focusManager.clearFocus()
        }),
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = text,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 3.dp),
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = true,
                        isError = false,
                        interactionSource = interactionSource,
                    )
                },
            )
        },
    )
}
