package com.jamesm92.nomadportal.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.ui.theme.NomadTextDim

/**
 * Shared sort selector for [com.jamesm92.nomadportal.ui.browser.NodeListScreen]
 * and [com.jamesm92.nomadportal.ui.messages.ConversationListScreen] — sits
 * inline at the end of each screen's [SearchField] row, per explicit
 * request (tried as its own full-width row below the search bar first;
 * moved inline right after). Wraps its own content rather than filling
 * available width, so it sits compactly at the end of a `Row` shared
 * with the search field instead of claiming a whole row for itself. The
 * four options apply identically in concept to both screens (nodes and
 * contacts both carry a recency/name/hop-count/announce-count), even
 * though the two screens sort two different item types — each screen
 * supplies its own comparator, this only owns the selection UI/state.
 */
enum class SortOption(val label: String) {
    RECENT("Recent"),
    ALPHABETICAL("A>Z"),
    HOPS("Hops"),
    ANNOUNCES("Announces"),
}

@Composable
fun SortDropdown(
    selected: SortOption,
    onSelect: (SortOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // No "Sort:" prefix now that this sits inline next to the
            // search field instead of on its own full-width row — there
            // isn't room for it, and the icon's contentDescription still
            // carries that context for accessibility.
            Text(
                text = selected.label,
                style = MaterialTheme.typography.labelMedium,
                color = NomadTextDim,
            )
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = "Change sort order",
                tint = NomadTextDim,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortOption.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
