package com.jamesm92.nomadportal.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
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
 * Deliberately compact — Material3's default `OutlinedTextField` height
 * (~56dp) read as too tall for what's just a list filter, not a primary
 * input. Smaller text style + explicit height + smaller icons + tighter
 * outer padding bring it down to ~44dp.
 *
 * `LocalMinimumInteractiveComponentSize provides 0.dp` around the whole
 * field matters, not just cosmetic: Material3's trailing-icon `IconButton`
 * (Clear) always reserves its own 48dp touch target internally, and that
 * reservation doesn't shrink just because the field around it is
 * constrained to 44dp — the field's own internal layout math ends up
 * trying to fit a 48dp-tall slot into a 44dp box, which is what was
 * actually clipping the bottom of the placeholder/value text (not padding
 * added by this composable itself — there wasn't any to remove). Zeroing
 * the minimum touch target here lets every internal slot size to its
 * real content instead.
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
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .height(44.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.8f,
            ),
            placeholder = {
                Text(placeholder, style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.8f,
                ))
            },
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                    }
                }
            },
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
        )
    }
}
