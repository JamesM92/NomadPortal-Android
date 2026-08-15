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
