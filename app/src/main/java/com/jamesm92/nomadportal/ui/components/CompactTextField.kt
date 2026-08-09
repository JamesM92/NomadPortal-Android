package com.jamesm92.nomadportal.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.ui.theme.NomadTextDim

/**
 * Shared small text field — the same real fix as
 * [SearchField]/`BrowserScreen`'s address bar/Settings' `MinutesField`
 * (see the `android-compose-compact-fields` skill for the root cause
 * writeup, and don't re-derive this construction locally again if a new
 * compact field is ever needed): the convenience `OutlinedTextField`
 * exposes no `contentPadding`, so no amount of outer `Modifier` tuning
 * on it can stop it clipping text or looking oversized in a dense row —
 * `BasicTextField` + `OutlinedTextFieldDefaults.DecorationBox` is the
 * only construction that actually exposes that control. This was
 * previously re-derived per call site (`MinutesField` in
 * `SettingsScreen.kt` being the most recent); promoted here once a
 * third/fourth need for it (the TCP connections table) came up, so
 * future compact fields reuse this instead of copying the pattern
 * again.
 *
 * Deliberately no built-in numeric clamping/commit-on-blur logic (unlike
 * `MinutesField`, which stays local to `SettingsScreen.kt` for that
 * reason) — this is a plain live-`onValueChange` field; callers that
 * need commit-on-blur semantics wrap it themselves (see
 * `TcpConnectionsTable`'s row fields).
 */
@Composable
fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isError: Boolean = false,
    textAlign: TextAlign = TextAlign.Start,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val interactionSource = remember { MutableInteractionSource() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = TextStyle(
            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
            textAlign = textAlign,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        singleLine = true,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interactionSource,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                isError = isError,
                placeholder = placeholder?.let {
                    {
                        Text(
                            it,
                            style = TextStyle(
                                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                                color = NomadTextDim,
                            ),
                        )
                    }
                },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = true,
                        isError = isError,
                        interactionSource = interactionSource,
                    )
                },
            )
        },
    )
}
