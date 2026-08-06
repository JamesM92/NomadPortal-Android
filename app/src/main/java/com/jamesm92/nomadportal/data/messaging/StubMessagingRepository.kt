package com.jamesm92.nomadportal.data.messaging

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Fake, in-memory [MessagingRepository] — no real LXMF delivery. Exists so
 * the Messages screens have something real to render and interact with
 * before the core extraction lands. See that interface's doc comment for
 * why this is a real implementation to build against, not throwaway.
 */
class StubMessagingRepository(private val scope: CoroutineScope) : MessagingRepository {
    private val contacts = listOf(
        Contact("a3f1", "Anselm", ContactIcon.Appearance("mesh", Color(0xFF7EC8A0))),
        Contact("b7d2", "quinn_relay", ContactIcon.Appearance("router", Color(0xFF5BA3C9))),
        Contact("c9e4", "nyx", ContactIcon.None),
    )

    private val messagesByContact: Map<String, MutableStateFlow<List<Message>>> = mapOf(
        "a3f1" to MutableStateFlow(seedThread(firstIsSent = false)),
        "b7d2" to MutableStateFlow(seedThread(firstIsSent = true)),
        "c9e4" to MutableStateFlow(emptyList()),
    )

    private val unreadCounts = MutableStateFlow(mapOf("a3f1" to 2, "b7d2" to 0, "c9e4" to 0))

    override fun conversations(): Flow<List<ConversationSummary>> {
        val messageFlows: List<Flow<List<Message>>> = contacts.map { messagesByContact.getValue(it.lxmfHash) }
        return combine(messageFlows) { it.toList() }.combine(unreadCounts) { messageLists, unread ->
            contacts.mapIndexed { index, contact ->
                ConversationSummary(
                    contact = contact,
                    lastMessage = messageLists[index].lastOrNull(),
                    unreadCount = unread[contact.lxmfHash] ?: 0,
                )
            }
        }
    }

    override fun messages(contactHash: String): StateFlow<List<Message>> =
        (messagesByContact[contactHash] ?: MutableStateFlow(emptyList())).asStateFlow()

    override suspend fun sendMessage(contactHash: String, content: String) {
        val flow = messagesByContact[contactHash] ?: return
        val message = Message(
            id = "local-${System.nanoTime()}",
            content = content,
            timestampMillis = System.currentTimeMillis(),
            isSent = true,
            deliveryState = DeliveryState.QUEUED,
        )
        flow.value = flow.value + message

        // Simulates the queued -> delivered transition a real LXMF send
        // goes through, so the delivery-state UI has something real to
        // show rather than being permanently stuck on "queued".
        scope.launch {
            delay(1200)
            flow.value = flow.value.map {
                if (it.id == message.id) it.copy(deliveryState = DeliveryState.DELIVERED) else it
            }
        }
    }

    override suspend fun markRead(contactHash: String) {
        unreadCounts.value = unreadCounts.value + (contactHash to 0)
    }

    override fun contact(contactHash: String): Contact? =
        contacts.find { it.lxmfHash == contactHash }

    private fun seedThread(firstIsSent: Boolean): List<Message> {
        val now = System.currentTimeMillis()
        val texts = listOf(
            "Got a path to the relay node, hop count looks stable.",
            "Good — cache time on that page is 12h so should hold.",
            "Noted. I'll ping if the announce goes stale.",
        )
        return texts.mapIndexed { i, text ->
            val isSent = if (i == 0) firstIsSent else i % 2 == 1
            Message(
                id = "seed-$i",
                content = text,
                timestampMillis = now - (texts.size - i) * 60_000L,
                isSent = isSent,
                deliveryState = if (isSent) DeliveryState.DELIVERED else null,
            )
        }
    }
}
