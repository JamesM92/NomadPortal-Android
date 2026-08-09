package com.jamesm92.nomadportal.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Shared filter box for [com.jamesm92.nomadportal.ui.browser.NodeListScreen]
 * and [com.jamesm92.nomadportal.ui.messages.ConversationListScreen] — both
 * lists are unbounded and grow into the hundreds within minutes on a busy
 * hub (see the orchestration-design memory), so finding one specific
 * node/contact by scrolling alone isn't viable. Filters client-side
 * against the already-polled list (no new orchestrator bridge call) —
 * both lists are small enough in memory that this is cheap, and it keeps
 * this in sync with the same poll cadence the rest of the row data uses.
 *
 * Built on `BasicTextField` + `OutlinedTextFieldDefaults.DecorationBox`
 * rather than the convenience `OutlinedTextField` composable — that
 * convenience overload has no `contentPadding` parameter at all, so no
 * amount of outer `.height()`/`.padding()` tuning on it can ever remove
 * its own internal padding (same real root cause diagnosed for
 * `BrowserScreen`'s address bar; see that file's `CompactAddressField`
 * for the fuller writeup). Here `contentPadding` is set directly, and
 * there's no outer height constraint — the field sizes itself to the
 * text's real line height instead of guessing a fixed dp value.
 *
 * `LocalMinimumInteractiveComponentSize provides 0.dp` still matters:
 * without it, the trailing Clear `IconButton`'s reserved 48dp touch
 * target would make this field taller than its text actually needs,
 * even with `contentPadding` otherwise minimal.
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val interactionSource = remember { MutableInteractionSource() }
    val textStyle = MaterialTheme.typography.bodyMedium.copy(
        color = MaterialTheme.colorScheme.onSurface,
    )

    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            textStyle = textStyle,
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            interactionSource = interactionSource,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // Without this, the keyboard's "search" action button did
            // nothing at all — there was no handler for ImeAction.Search,
            // so tapping it (or just wanting the keyboard gone after
            // typing a filter) had no effect. The filtering itself is
            // already live as-you-type via onQueryChange; this button's
            // only job is to let the keyboard go away once you're done.
            keyboardActions = KeyboardActions(onSearch = {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
            }),
            decorationBox = { innerTextField ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = query,
                    innerTextField = innerTextField,
                    enabled = true,
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    interactionSource = interactionSource,
                    placeholder = {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    Icons.Filled.Clear,
                                    contentDescription = "Clear search",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    },
                    // Almost nothing left, same as the address bar — a
                    // couple dp just so the cursor/descenders never touch
                    // the outline.
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
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
}
