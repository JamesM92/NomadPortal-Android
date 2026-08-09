package com.jamesm92.nomadportal.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** The "Messages" bottom-nav icon, with a badge showing [unreadCount]
 * when non-zero — so an unread message is visible from the tab bar
 * itself, not just after opening the Messages tab. Caps the displayed
 * number at "99+" rather than growing the badge unboundedly for a very
 * large backlog.
 *
 * Deliberately just the badged icon content, no [androidx.compose.material3.IconButton]/
 * click handling of its own — [NavigationBarItem][androidx.compose.material3.NavigationBarItem]
 * (this composable's only caller, `NomadNavHost.kt`) is already the
 * clickable element; wrapping this in a second nested clickable would
 * be a real tap-target/accessibility bug (a "button inside a button"),
 * not just redundant. Before the bottom-nav redesign this used to wrap
 * itself in an `IconButton` for its old top-bar-icon call sites
 * (Home/Nodes) — those are gone now that cross-nav lives in one place. */
@Composable
fun MessagesIconWithBadge(unreadCount: Int) {
    BadgedBox(
        badge = {
            if (unreadCount > 0) {
                Badge { Text(if (unreadCount > 99) "99+" else unreadCount.toString()) }
            }
        },
    ) {
        Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null)
    }
}
