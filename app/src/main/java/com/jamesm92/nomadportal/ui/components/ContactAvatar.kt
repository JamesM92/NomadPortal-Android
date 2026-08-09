package com.jamesm92.nomadportal.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.jamesm92.nomadportal.data.messaging.Contact
import com.jamesm92.nomadportal.data.messaging.ContactIcon
import com.jamesm92.nomadportal.data.messaging.materialIconFor

/**
 * Renders a [Contact]'s appearance (porting-notes.md §4: LXMF's `0x04`
 * icon-appearance and `0x06` raw-image fields):
 * - [ContactIcon.Appearance]: a colored circle with the real icon glyph
 *   when [materialIconFor] resolves the name, otherwise the contact's
 *   initial letter — never a blank circle.
 * - [ContactIcon.RawImage]: decoded via Android's built-in
 *   [BitmapFactory] (covers PNG/JPEG/GIF/WEBP — no new dependency; an SVG
 *   payload, which BitmapFactory can't decode, falls back to the same
 *   [Identicon] treatment as a decode failure of any other kind).
 * - [ContactIcon.None]: a deterministic [Identicon] generated from
 *   [Contact.lxmfHash] — real per-contact distinctiveness for a contact
 *   that hasn't sent an icon, replacing the old same-grey-circle-for-
 *   everyone behavior (Columba-style, ported directly from its own real
 *   source — see [Identicon]'s own doc comment).
 */
@Composable
fun ContactAvatar(contact: Contact, modifier: Modifier = Modifier) {
    when (val icon = contact.icon) {
        is ContactIcon.Appearance -> {
            val vector = remember(icon.glyphName) { materialIconFor(icon.glyphName) }
            Box(
                modifier = modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(icon.backgroundColor),
                contentAlignment = Alignment.Center,
            ) {
                if (vector != null) {
                    Icon(
                        imageVector = vector,
                        contentDescription = null,
                        tint = icon.foregroundColor,
                        modifier = Modifier.size(24.dp),
                    )
                } else {
                    Text(
                        text = contact.displayName.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        color = icon.foregroundColor,
                    )
                }
            }
        }

        is ContactIcon.RawImage -> {
            // remember() keyed on the byte content (RawImage's own
            // content-aware equals/hashCode) so re-decoding only happens
            // when the bytes actually change across polls, not every
            // recomposition.
            val bitmap = remember(icon) {
                BitmapFactory.decodeByteArray(icon.bytes, 0, icon.bytes.size)
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = modifier.size(40.dp).clip(CircleShape),
                )
            } else {
                // Decode failure (e.g. an SVG payload — BitmapFactory
                // only handles raster formats) falls back to the same
                // Identicon treatment as ContactIcon.None below, rather
                // than rendering nothing.
                Identicon(hash = remember(contact.lxmfHash) { contact.lxmfHash.hexToByteArray() }, size = 40.dp, modifier = modifier)
            }
        }

        ContactIcon.None -> {
            Identicon(hash = remember(contact.lxmfHash) { contact.lxmfHash.hexToByteArray() }, size = 40.dp, modifier = modifier)
        }
    }
}
