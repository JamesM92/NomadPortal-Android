package com.jamesm92.nomadportal.ui.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.data.messaging.MessagingRepository
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.components.ContactAvatar
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import kotlinx.coroutines.launch

/**
 * Every contact this device has blocked — Columba's own real
 * `PrivacyCard`'s "blocked users list" (verified against its source
 * during this session's own Columba settings audit). Deliberately not
 * built on [ConversationRow] (Messages' own row, with call/favorite/
 * mark-unread actions) — none of those make sense for someone you've
 * blocked; the one real action here is unblocking them. Entirely
 * client-side: [MessagingRepository.conversations] already returns
 * every known contact (including blocked ones, per its own doc
 * comment), and [MessagingRepository.setBlocked] already exists —
 * no new backend needed.
 */
@Composable
fun BlockedContactsScreen(repository: MessagingRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val conversations by repository.conversations().collectAsState(initial = emptyList())
    val blocked = conversations.filter { it.contact.isBlocked }

    Scaffold(
        topBar = {
            AdaptiveTopAppBar(
                title = { Text("Blocked contacts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (blocked.isEmpty()) {
                Text(
                    text = "No blocked contacts",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NomadTextDim,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(blocked, key = { it.contact.lxmfHash }) { summary ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ContactAvatar(summary.contact)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = summary.contact.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = summary.contact.lxmfHash,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NomadTextDim,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = {
                                scope.launch { repository.setBlocked(summary.contact.lxmfHash, false) }
                            }) { Text("Unblock") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
