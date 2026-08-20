package com.jamesm92.nomadportal.data.identity

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.Flow

/**
 * Multi-identity management — see [Identity]'s own doc comment for the
 * single-active-identity model this is built around. [RealIdentityRepository]
 * is the only implementation; there's no stub, since this ships straight
 * against the real `orchestrator.py` bridge functions built alongside it
 * (no earlier stub-then-real phase the way [com.jamesm92.nomadportal.data.messaging.MessagingRepository]
 * had).
 */
interface IdentityRepository {
    /** Every identity on this device, oldest-created-first — polled,
     * same convention as every other repository's own list-shaped
     * `Flow` in this app (no push mechanism exists on the Python side
     * for any of this). */
    fun identities(): Flow<List<Identity>>

    /** Creates a brand-new identity (a fresh RNS keypair) — does NOT
     * switch to it; call [switchActiveIdentity] separately, matching
     * Columba's own real create-then-activate split. Returns the new
     * identity's id on success; throws on failure (identity storage not
     * ready yet). */
    suspend fun createIdentity(name: String): String

    /**
     * Switches which identity is active — a real, state-changing action:
     * cleanly deactivates the current identity's LXMF router and
     * activates the target's (see orchestrator.py's
     * `switch_active_identity()` own doc comment for the real
     * teardown/activate mechanics), matching Columba's exact
     * single-active model. A no-op if [identityId] is already active.
     * Every other repository's polling `Flow`s (conversations,
     * announceStatus, etc.) reflect the switch on their own next poll
     * tick — no separate invalidation call needed.
     *
     * Throws on failure (identity not found, messaging not ready) with
     * a UI-displayable reason.
     */
    suspend fun switchActiveIdentity(identityId: String)

    /** Blank names are rejected (returns false), same convention as
     * [com.jamesm92.nomadportal.data.messaging.MessagingRepository.setContactName]. */
    suspend fun renameIdentity(identityId: String, name: String): Boolean

    suspend fun setIdentityIcon(identityId: String, glyphName: String, foreground: Color, background: Color): Boolean

    /**
     * Permanently deletes an identity's keypair/metadata AND its own
     * message history/contacts/favorites — a real cascade delete,
     * matching Columba's own real `IdentityRepository.deleteIdentity()`.
     * Re-importing a `.identity` export of a deleted identity later
     * only recreates the bare keypair — its history is genuinely gone,
     * not recoverable, same guarantee the panic wipe already gives at
     * the whole-device level (see orchestrator.py's `delete_identity()`
     * own doc comment). If it was the active one, this app always falls
     * back to another identity (creating a fresh one if none remain)
     * rather than leaving no identity at all.
     */
    suspend fun deleteIdentity(identityId: String)

    /**
     * Imports an existing identity from raw `.identity` key-file bytes
     * (this device's own file picker, or a file exported from Columba —
     * confirmed cross-compatible, same raw RNS private-key byte format
     * both apps use). Does NOT switch to it — same create-then-activate
     * split as [createIdentity]. Returns the identity's id on success;
     * throws with a UI-displayable reason on a malformed file.
     */
    suspend fun importIdentity(keyBytes: ByteArray, name: String): String

    /** Raw bytes of one identity's own `.identity` key file, for a
     * caller to write to a temp file and hand to the system share sheet
     * (same [com.jamesm92.nomadportal.ui.messages.AttachmentFileProvider]
     * pattern already established for sharing app-owned files) — null
     * if the identity doesn't exist. */
    suspend fun exportIdentity(identityId: String): ByteArray?
}
