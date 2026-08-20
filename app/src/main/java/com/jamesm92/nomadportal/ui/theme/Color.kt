package com.jamesm92.nomadportal.ui.theme

import androidx.compose.ui.graphics.Color

// Design tokens carried over from NomadPortal's web UI (porting-notes.md,
// section 5: "Design tokens") for visual parity with the original service.
// Dark, terminal-flavored aesthetic — this is a dark-only palette by design,
// not a placeholder waiting for a light variant.
val NomadBg = Color(0xFF131313)
val NomadBg2 = Color(0xFF1C1C1C)
val NomadBg3 = Color(0xFF252525)
val NomadBorder = Color(0xFF333333)
val NomadText = Color(0xFFC8C8C8)
val NomadTextDim = Color(0xFF666666)
val NomadAccent = Color(0xFF5BA3C9)
val NomadAccent2 = Color(0xFF7EC8A0)
val NomadWarn = Color(0xFFC8905B)
val NomadError = Color(0xFFC85B5B)

// Chat bubble tokens (porting-notes.md: sent/received message bubbles).
val NomadSentBubble = Color(0xFF173040)
val NomadSentBubbleBorder = Color(0xFF2A5570)

// Not a porting-notes.md token — new, added for the app's own logo mark
// (AppLogo.kt's tent-and-portal icon) only. Kept in the same muted/
// desaturated brightness range as the tokens above (one channel near
// 0xC8, the others lower) so it reads as part of the same palette family
// rather than a jarring one-off accent.
val NomadPortalPurple = Color(0xFF9B6BC8)

// Light-theme background/surface/border tiers — the real light palette
// added for Settings' theme-mode toggle (per explicit direction, closing
// a real Columba-parity gap; this app was dark-only before). Deliberately
// NOT a full parallel token set: the accent hues (NomadAccent/Accent2/
// PortalPurple/Warn/Error) and NomadTextDim are reused as-is in both
// themes (kept as brand-consistent, and NomadTextDim's medium-gray value
// already has workable contrast against both a near-black and a
// near-white background) — only background/surface/border/body-text
// actually need light-specific values, and NomadBg itself (#131313,
// already near-black) doubles as the light theme's own primary body-text
// color rather than inventing a separate "NomadTextLight" that would
// just duplicate it. See Theme.kt's own NomadLightColorScheme for the
// exact role mapping.
val NomadBgLight = Color(0xFFFAFAFA)
val NomadBg2Light = Color(0xFFF2F2F2)
val NomadBg3Light = Color(0xFFE8E8E8)
val NomadBorderLight = Color(0xFFD4D4D4)

// Identicon "kind" ring color — Contacts specifically (see Identicon.kt's
// own `ringColor` doc comment for the full 3-kind scheme: Sites reuse
// NomadPortalPurple, rnsh reuses NomadAccent, both already-established
// tokens with a real thematic fit; Contacts needed a genuinely new one
// since every other existing token already carries a real, different
// semantic elsewhere in this app — NomadAccent2 means "online/success"
// status, NomadWarn/NomadError mean warning/error status, and reusing
// either for "this is a contact" would collide with those meanings. A
// soft cyan/teal, distinct in hue from every token above it, same muted
// brightness range.
val NomadIdenticonRingContact = Color(0xFF5BC8C8)

// A 4th identicon "kind" ring — Relays (Network tab's own Relays filter,
// AnnounceItem.Relay/RelayNode), per explicit direction that relay rows
// should carry an identicon like every other announce-list row does, not
// a bare status dot. Same "genuinely new hue, not already carrying a
// different semantic elsewhere" reasoning as NomadIdenticonRingContact's
// own doc comment: Purple is Sites, NomadAccent is rnsh, the cyan/teal
// above is Contacts, Accent2/Warn/Error are all real status semantics —
// a muted gold/yellow is the one hue in this same brightness family
// nothing else here uses yet.
val NomadIdenticonRingRelay = Color(0xFFC8C85B)
