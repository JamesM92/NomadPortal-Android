package com.jamesm92.nomadportal.data.identity

import com.jamesm92.nomadportal.data.messaging.ContactIcon

/**
 * One of this device's own LXMF messaging identities — Settings'
 * "Identities" management screen, matching Columba's real
 * `IdentityManagerScreen`/`IdentityRepository` (verified against its
 * source: `allIdentities`/`activeIdentity` flows, `switchActiveIdentity`
 * deactivating every other identity, cascade-ish delete). This is a
 * genuinely different axis from [com.jamesm92.nomadportal.data.messaging.Contact]
 * (someone *else's* identity you've heard announce/messaged) — an
 * `Identity` here is one of *this device's own* keypairs.
 *
 * Exactly one identity is ever [isActive] at a time (this app's
 * single-active-identity model — see orchestrator.py's own
 * `_active_user_sub` doc comment for why, and `switch_active_identity()`
 * for the real teardown/activate mechanics). Node hosting's own
 * identity (`site_identity.id`) is a completely separate, untouched
 * axis — not represented here at all, matching Columba's own
 * `IdentityManagerScreen` (which has no hosting concept either).
 */
data class Identity(
    /** The identity's own RNS keypair hash — a genuinely different
     * value from [lxmfAddress] (that's the derived "lxmf.delivery"
     * *destination* hash), same distinction
     * [com.jamesm92.nomadportal.data.messaging.AnnounceStatus.identityHash]
     * already draws elsewhere in this app. */
    val id: String,
    val name: String,
    /** Null only if this identity's own RNS.Identity file couldn't be
     * loaded (e.g. a corrupt/missing key file) — an honest gap, not
     * expected in normal use. */
    val lxmfAddress: String?,
    /** A static property of the keypair itself, not tied to whether
     * this identity has a live router right now — unlike
     * [com.jamesm92.nomadportal.data.messaging.AnnounceStatus.publicKeyHex]
     * (only ever populated for the *active* identity), this is
     * available for every identity, letting the Identities screen offer
     * QR sharing regardless of which one is currently active. Same
     * null-only-on-load-failure caveat as [lxmfAddress]. */
    val publicKeyHex: String?,
    /** Null until the user (or this feature's own default-seeding) sets
     * one — same "no fabricated default" convention as
     * [com.jamesm92.nomadportal.data.messaging.AnnounceStatus.iconAppearance]. */
    val iconAppearance: ContactIcon.Appearance?,
    val isActive: Boolean,
    val createdAtMillis: Long,
)
