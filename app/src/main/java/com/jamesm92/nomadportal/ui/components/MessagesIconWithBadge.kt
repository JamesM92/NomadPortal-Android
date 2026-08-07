package com.jamesm92.nomadportal.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** The cross-nav "Messages" top-bar icon (Home, Nodes), with a badge
 * showing [unreadCount] when non-zero — so an unread message is visible
 * from screens other than Messages itself, not just after opening it.
 * Caps the displayed number at "99+" rather than growing the badge
 * unboundedly for a very large backlog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesIconWithBadge(unreadCount: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        BadgedBox(
            badge = {
                if (unreadCount > 0) {
                    Badge { Text(if (unreadCount > 99) "99+" else unreadCount.toString()) }
                }
            },
        ) {
            Icon(Icons.AutoMirrored.Filled.Message, contentDescription = "Messages")
        }
    }
}
