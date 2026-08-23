package com.jamesm92.nomadportal.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Tap-outside-to-dismiss for any screen that holds a text field. Compose
 * gives a focused text field no built-in way to lose focus (and hide the
 * keyboard it opened) from a tap elsewhere on the screen — every other
 * native Android text input does this by default, so its absence here
 * reads as a bug ("any time I click a text box I can't deselect it or
 * make the keyboard go away"). Apply to the screen's outermost
 * Column/Box, not to individual rows: a plain `detectTapGestures` here
 * only ever fires for taps children didn't already consume (Compose
 * dispatches pointer events to the innermost matching handler first, so
 * a row's own `clickable` or a text field gaining focus both still work
 * exactly as before) — it's purely a fallback for "tapped empty space."
 *
 * [onDismiss] is an extra hook for a screen that tracks its own
 * "which thing is being edited" state *separately* from Compose's own
 * ambient focus system — [RichTextPageEditor]'s `focusedBlockId` is the
 * real case this was added for: clearing ambient focus alone doesn't
 * reset that state (nothing in this modifier can reach into a caller's
 * own `remember`ed state), so without this hook a block stayed
 * permanently swapped into its editable `BasicTextField` even after a
 * tap on blank space, since there was no path back to the read-only
 * rendered view at all except deleting the block. Runs after the real
 * focus-clear/keyboard-hide, never instead of it.
 */
fun Modifier.dismissKeyboardOnTap(onDismiss: () -> Unit = {}): Modifier = composed {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    pointerInput(Unit) {
        detectTapGestures(onTap = {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            onDismiss()
        })
    }
}
