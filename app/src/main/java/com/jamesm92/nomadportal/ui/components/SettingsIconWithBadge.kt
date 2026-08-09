package com.jamesm92.nomadportal.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import com.jamesm92.nomadportal.ui.theme.NomadWarn

/** The "Settings" bottom-nav icon, with a plain warning-colored dot (no
 * count — this is a yes/no flag, not something to tally) when
 * [hasWarning] — currently only driven by
 * [com.jamesm92.nomadportal.connectivity.InterfaceController.hasDownTcpConnection],
 * but kept as a generic "something in Settings needs attention" signal
 * rather than a TCP-specific one, so a future second reason to flag
 * Settings doesn't need its own separate badge.
 *
 * Promoted here from a `HomeScreen`-private composable of the same name
 * once the bottom-nav redesign gave it a second real caller
 * (`NomadNavHost.kt`) — same reasoning [MessagesIconWithBadge] was
 * already promoted for. Deliberately just the badged icon content, no
 * [androidx.compose.material3.IconButton] of its own — see
 * [MessagesIconWithBadge]'s own doc comment for why. */
@Composable
fun SettingsIconWithBadge(hasWarning: Boolean) {
    BadgedBox(
        badge = {
            if (hasWarning) {
                Badge(containerColor = NomadWarn)
            }
        },
    ) {
        Icon(Icons.Filled.Settings, contentDescription = null)
    }
}
