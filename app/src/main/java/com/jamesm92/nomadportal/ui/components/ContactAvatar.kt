package com.jamesm92.nomadportal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.data.messaging.Contact
import com.jamesm92.nomadportal.data.messaging.ContactIcon
import com.jamesm92.nomadportal.ui.theme.NomadBg3

/**
 * Renders a [Contact]'s appearance (porting-notes.md §4: LXMF's `0x04`
 * icon-appearance and `0x06` raw-image fields). [ContactIcon.RawImage] is
 * not decoded/rendered yet — falls back to initials like
 * [ContactIcon.None], since faking bitmap decoding for a contact icon that
 * doesn't exist yet (no real LXMF wiring) isn't worth doing before there's
 * real image data to decode.
 */
@Composable
fun ContactAvatar(contact: Contact, modifier: Modifier = Modifier) {
    val backgroundColor = when (val icon = contact.icon) {
        is ContactIcon.Appearance -> icon.color
        else -> NomadBg3
    }
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = contact.displayName.take(1).uppercase(),
            style = MaterialTheme.typography.titleLarge,
        )
    }
}
