# Task: Build nomadportal-android

A native Android app for browsing, hosting, and editing NomadNet (Reticulum)
pages — a from-scratch rewrite inspired by `jamesm92/nomadportal` (my
existing Flask-based web portal), not a port of its Flask/Jinja layer.

**Architecture decided:** Chaquopy-embedded Python backend (RNS/LXMF core
logic) + native Kotlin/Jetpack Compose UI. Explicitly *not* the WebView-
wrapped-Flask-app alternative we also considered — Compose was chosen
specifically because its declarative model handles mixed text-and-
interactive-widget layouts natively, which matters a lot once page-hosting
with form fields is involved (see micron2compose section below).

## What carries over from NomadPortal, and what doesn't

NomadPortal is really two things bolted together: (1) RNS/LXMF link
handling, node discovery/favorites, and `/page`+`/file` request/response
logic, and (2) Flask routes + Jinja templates + HTML output. Only (2) is
Flask-specific.

- **Reusable close to as-is:** the RNS/LXMF link-establishment and page-
  request logic, node discovery via announces, favorites/blocklist state
  model — this is transport-layer Python with no web-framework dependency.
  Extract it into a clean, UI-agnostic module before touching any UI code,
  so it can run identically under Chaquopy regardless of what calls it.
- **Rebuilt from scratch:** all rendering and UI — see `micron2compose`
  below and the Compose screens themselves.

## micron2compose — the third Micron rendering target

Two other Micron converters already exist for other targets in this project
family: `Micron2HTML` (mine, for NomadPortal's web UI) and `micron2kivy`
(built for a possible Sideband PR — a different, lower-priority track, see
"Relationship to the Sideband PR track" below). `micron2compose` is a new,
third target. The Micron *tokenizer/parser* is shared knowledge across all
three — only the emission layer changes:

- **Colors/bold/italic/underline** → Compose `AnnotatedString` with
  `SpanStyle`s (color, `fontWeight`, `fontStyle`, `textDecoration`) applied
  over character ranges — a natural fit, arguably the cleanest of the three
  targets for inline styling.
- **Links** → use `AnnotatedString`'s `stringAnnotations` (tagged ranges
  queryable on tap via `ClickableText`) rather than Kivy's external
  `[ref=id]` + side-table approach — Compose's own annotation mechanism
  covers what the link-table hack was working around in Kivy. Still worth
  returning a small result object (`ConvertResult` with `text:
  AnnotatedString` and structured link metadata) rather than a bare string,
  matching the shape used for the other two targets, for consistency and
  because partials still need out-of-band `refresh`/`fields`/`pid` metadata
  no annotation naturally carries.
- **Tables** → same ASCII-art monospace-block approach as the other two
  targets ports directly; render in a `Text` composable with a monospace
  `FontFamily`. This was already the easy part for micron2kivy too — no new
  work here.
- **Anchors** → Compose has no equivalent to Kivy's native `[anchor=]` +
  `label.anchors` position lookup. Implement scroll-to-anchor via tracked
  item indices in a `LazyColumn` (if the page is rendered as a list of
  blocks) or a manually tracked character-offset-to-scroll-position map
  with `BringIntoViewRequester` if rendered as one continuous `Text`. Decide
  which page-layout model (block list vs. single flow) early, since it
  determines which of these approaches applies.
- **Form fields — this is where Compose genuinely wins over the Kivy
  target.** Kivy's `Label` markup can't embed live widgets inline in text
  flow at all, forcing a placeholder-only v1 there. Compose has no such
  restriction: a `Column` mixing `Text` and `TextField`/`Checkbox`/
  `RadioButton` composables in sequence, built by walking the parsed Micron
  AST and switching between "emit styled text" and "emit an input
  composable" as field markers are encountered, is ordinary Compose — not a
  workaround. This means form-field pages don't need to be deferred to a
  "v2" the way they did for micron2kivy; build real interactive fields from
  the start here if time allows, since the architecture supports it
  naturally.
- Keep the same defensive posture as the other two converters: escape
  correctly for the target (Compose text doesn't have Kivy's `[`/`]`
  markup-injection concern since you're building `AnnotatedString`
  programmatically rather than parsing a markup string, but still validate/
  sanitize anything derived from untrusted `.mu` content before using it in
  URLs, file paths, or annotation tags).

## Page editor

- **Split-pane**: monospace multiline `TextField` for raw Micron source,
  live-rendered preview alongside it using `micron2compose`, both bound to
  shared state. Debounce re-parsing (a few hundred ms via `snapshotFlow` +
  `debounce`) rather than re-rendering every keystroke.
- **Parser must render best-effort on malformed input** — an editor's
  preview is constantly looking at half-typed syntax; never crash or blank
  the preview mid-edit, render whatever can be parsed.
- **Formatting toolbar**: buttons for bold/italic/underline/color/link/
  divider/table/anchor that wrap the current selection or insert a template
  at the cursor — Micron's terse backtick syntax is easy to get wrong by
  hand, this earns its keep more than in a typical text editor.
- **File tree = the actual pages directory.** NomadNet's own hosting model
  already maps folder structure 1:1 to URL structure (see hosting section
  below) — the editor's file browser and the node's served content are the
  same directory, no separate "publish" step. New file → new page,
  immediately live.
- **Auth toggle on save** writes/updates a `.allowed` file alongside the
  page, matching NomadNet's real convention rather than inventing a custom
  scheme — keeps the node compatible with the wider ecosystem's
  expectations.
- **Atomic writes**: write to a temp file, then rename over the target,
  rather than writing in place — request handlers may run on the RNS driver
  thread concurrently with a save, and a half-written file must never be
  what gets served.
- Worth a quick hands-on look at Nomad Navigator's built-in Micron editor
  before designing screens in detail — it's an existing, validated UX
  reference for this exact feature from the same ecosystem.

## Self-hosted node — deliberate scope decisions

- **Static `.mu` files only. No executable/dynamic pages.** Desktop
  NomadNet's dynamic-page model (chmod +x, shebang, shell out to an
  interpreter) is a remote-code-execution primitive by design, and doesn't
  map to Android's sandbox anyway — dropping it removes an entire risk
  category rather than just mitigating one, which matters a lot more next
  to a phone's contacts/camera/messages than next to a dedicated desktop
  NomadNet install. This was a deliberate, considered trade-off, not a
  shortcut — don't revisit it without a real reason to.
- **Path traversal protection is mandatory and not automatic just because
  exec is disabled** — normalize and validate every requested path against
  the pages-root directory before any file read.
- **`.allowed`-gated auth must be enforced on every request path that could
  reach a protected file**, not just at a directory-listing layer.
- **Basic resource-exhaustion limits**: cap concurrent Links/requests per
  peer, since even static serving can be abused by connection/request
  flooding — independent of the static-vs-dynamic decision.
- **Request handlers run synchronously on the RNS driver thread** — don't
  do slow I/O inline in the callback; if content generation ever needs to
  be more than a direct file read, decouple through a cache rather than
  blocking that thread.
- Protocol reference: NomadNet's actual node-hosting model — RNS Link
  request/response on the `nomadnetwork.node` aspect, pages folder mapped
  1:1 onto `/page/` URL structure. Build against this exact model, and test
  the hosting side against a real independent client (desktop NomadNet, or
  the lightweight `nomadnet-serve`/browsing pair from the Rust
  reimplementation of the protocol) rather than only your own app, so you
  get an external correctness check from a client you didn't write.

## Chaquopy / packaging specifics

- Chaquopy embeds a real CPython interpreter via a Gradle plugin — your
  extracted NomadPortal core logic and its dependencies (`rns`, `lxmf`) run
  largely unchanged inside the app's own process.
- **Check Chaquopy's current license terms before committing** — it has a
  free tier oriented at open-source/non-commercial use and a paid tier for
  some commercial scenarios; confirm this matches your plans.
- **Android scoped storage**: file paths need adapting to app-private
  internal storage rather than assuming a normal filesystem (Android 10+
  enforces this) — this affects both the pages directory and any
  RNS/LXMF config/identity storage.
- Since this app is an independent implementation (not a fork of
  Sideband's source), **Sideband's CC BY-NC-SA license does not apply to
  this project at all** — you're free to license nomadportal-android
  however you want. The only hard rule: don't copy actual Sideband source
  files as a starting point for anything, which would make it a derivative
  work subject to those terms. RNS/LXMF themselves are MIT-licensed
  (plus an anti-AI-training/anti-AI-contribution clause that doesn't
  restrict what you build with them), so depending on them as ordinary
  libraries is unrestricted.

## Prior art worth studying

- **`torlando-tech/reticulum-android`** — a native Android app wrapping
  reference Python RNS via Chaquopy specifically (same embedding choice as
  this project), already shipping a Bluetooth-and-USB bridge for RNode.
  Directly relevant as a reference for the general Chaquopy-embedding
  pattern on Android, independent of the Bluetooth-mesh work described
  below. Check its license before reusing code.
- Note: **any Kotlin interop code written against `pyjnius`/python-for-
  android (e.g. patterns seen in Sideband's own source) does not port
  directly to Chaquopy** — Chaquopy has its own separate Java/Kotlin
  interop mechanism. Don't assume Sideband's native-bridge code is reusable
  here without translation.

## Relationship to other tracks in this project family

- **The Sideband-PR track (`micron2kivy`) is now secondary, not the primary
  path.** It was originally scoped for a possible contribution to Sideband,
  but going independent with nomadportal-android avoids Sideband's CC
  BY-NC-SA license entanglement and the process friction around
  contributing to that project entirely. `micron2kivy` still exists as a
  standalone library if that track is ever picked back up, but
  nomadportal-android does not depend on it — this project uses
  `micron2compose` instead.
- **The Bluetooth-mesh RNS interface is a separate repo**, covered in its
  own handoff doc, built as an Android library module (Kotlin BLE/GATT +
  Chaquopy glue) that plugs into any Chaquopy-embedded RNS instance as an
  additional `Interface`. This app's core Python logic doesn't need to know
  anything about *which* interfaces are configured — integrating that
  wrapper later should be a config-level addition (add another interface
  to the Reticulum config the embedded RNS instance loads), not a redesign
  of anything in this app. Don't block progress on this app waiting for
  that repo — they can proceed in parallel and merge later.

## Suggested sequencing

1. Extract NomadPortal's UI-agnostic core logic into its own module first,
   with no Chaquopy/Android dependency yet — validate it still runs and
   passes any existing tests as plain Python.
2. Get that core running under Chaquopy in a bare-bones Android shell (even
   a placeholder single-screen UI is fine for this step) — this de-risks
   the "does RNS/LXMF actually run correctly on Android via Chaquopy"
   question before investing in UI work, and is shared risk with the
   WebView-based alternative we considered, so it's worth nailing down
   regardless of which UI path had been chosen.
3. Build `micron2compose` as its own well-tested module, against real `.mu`
   fixtures pulled from live nodes — independent of any screen work.
4. Build the browsing screens (address bar, rendered page, back/forward)
   using `micron2compose`'s output.
5. Build the hosting request-handler (static-only, with the security
   constraints above) and validate it against an external client.
6. Build the editor last, once both rendering and hosting are solid, since
   it depends on both (live preview needs the renderer; saving needs to be
   safe against the concurrently-running request handler).

## Main menu / connectivity & privacy controls (added Aug 2026)

Requirements gathered during initial project scaffolding. **Now
implemented** as of the connectivity/privacy work session (see
`connectivity/InterfaceController.kt` + `ui/settings/SettingsScreen.kt`) —
the actual RNS interface control is still a `NoopInterfaceController` stub
pending the core extraction, but the toggle UI, permission flow, and
persisted-intent plumbing are real and built against the interface a real
controller will implement later.

- **Explicit, authoritative interface toggles in the main menu**: separate
  on/off switches for **Bluetooth mesh**, **TCP**, **RNode**, and **local
  Wi-Fi discovery**. "Authoritative" means each toggle actually tears
  down/brings up the corresponding RNS `Interface` — not just a UI
  preference the backend might not honor.
  - Toggling **TCP** off shuts down all of this app's internet-based RNS
    activity (TCP client/server interfaces). No mesh traffic over the
    internet while off.
  - Toggling **Bluetooth mesh** off shuts down the BLE mesh RNS interface
    (the separate Bluetooth-mesh interface module/repo referenced elsewhere
    in this doc) — but does **not** touch an RNode connected over
    Bluetooth. Those are two independent things sharing the same radio:
    BLE-mesh-as-an-RNS-interface vs. an RNode device that happens to use a
    Bluetooth transport. Don't conflate them in the implementation the way
    their shared radio might tempt you to.
  - Toggling **RNode** off shuts down the RNode interface specifically,
    regardless of whether that RNode is connected via USB or Bluetooth.
  - **Toggling local Wi-Fi discovery** off shuts down RNS's `AutoInterface`
    (IPv6 link-local multicast peer discovery on the current LAN) —
    distinct from TCP, which is for reaching specific configured remote
    addresses, not auto-discovering nearby nodes on the same network. Off
    by default (unlike TCP): it announces this device's presence to every
    network it joins via multicast, which is more like Bluetooth
    mesh/RNode's exposure profile than TCP's. Uses a normal
    (`CHANGE_WIFI_MULTICAST_STATE`) manifest permission, not a runtime one
    — doesn't touch Wi-Fi scanning or location at all, added Aug 2026 per
    explicit user request for this toggle.
  - These four toggles are independent and orthogonal — any combination of
    on/off states must be valid and immediately reflected in which RNS
    interfaces are actually live.
- **Explicit toggle for hosting a NomadNet node on this device at all**,
  separate from the three interface toggles above and separate from
  browsing capability. Browsing the mesh must work with hosting off; the
  three connectivity toggles gate *which interfaces exist*, this one gates
  *whether this device answers page/file requests over whichever
  interfaces are up*.
- **All Android runtime permissions are optional.** The app must function
  as intended whether any given permission is granted or denied — nothing
  should hard-block on a permission the user declined. This has a concrete
  implication for the Bluetooth-mesh work specifically: Android's BLE scan
  APIs normally require `ACCESS_FINE_LOCATION` unless the
  `BLUETOOTH_SCAN` permission is declared with
  `android:usesPermissionFlags="neverForLocation"` (API 31+) — use that
  flag rather than requesting location, per the next bullet.
- **Never request location permission, under any circumstances**, even as
  a side effect of a BLE API's default permission requirements. If a
  library or API forces a location-permission requirement with no opt-out,
  that's a blocker worth resolving (find another way to do the BLE
  operation, or drop the feature) rather than adding the permission.
  - **Resolved (Aug 2026)**: `neverForLocation` on `BLUETOOTH_SCAN` only
    exists on API 31+; below that, Android ties BLE scan results to
    location permission at the OS level with no bypass. Rather than gate
    the Bluetooth-mesh feature behind a runtime SDK check on an app that
    otherwise supports older devices, **`minSdk` was raised to 31**
    (`app/build.gradle.kts`) so the no-location requirement holds
    everywhere the app runs, full stop. This does mean Android 7-11
    devices can't install the app at all — a deliberate tradeoff, not an
    oversight, made explicitly to keep the "never request location" rule
    absolute rather than conditional.
- **Panic wipe**: triple-tapping the app's main logo wipes all local app
  data, mirroring Bitchat's panic-wipe UX convention. Specific requirements
  on the wipe itself:
  - **Multi-pass wipe**, not a plain file delete — a bare `delete()`/`rm`
    just unlinks directory entries and leaves the underlying storage bytes
    recoverable by anyone with raw flash/filesystem access; the actual
    identity key material and message content need to be overwritten, not
    just unreferenced. (Flash-storage wear-leveling means even a careful
    multi-pass overwrite is not a hard guarantee at the physical layer the
    way it might be on spinning disk — worth being honest with the user
    about that limit rather than overpromising "unrecoverable," but the
    implementation should still do real overwrite passes, not skip that
    step because it isn't a 100% guarantee.)
  - **Resolved (Aug 2026): layer in cryptographic erasure ahead of
    multi-pass overwrite, not instead of it.** Multi-pass overwrite is
    slow and its physical-layer guarantee is unreliable on flash (above).
    Android Keystore-backed key destruction doesn't have either problem —
    delete a hardware-backed key and any data encrypted with it becomes
    permanently unrecoverable ciphertext instantly, independent of data
    volume (same technique Android itself uses for fast FBE factory
    resets). Implemented in `security/SecureKeystore.kt` +
    `panicwipe/PanicWipe.kt`: key wipe runs first (fast, real, functional
    today even with no real keys yet), multi-pass file overwrite runs
    after as defense-in-depth for whatever isn't Keystore-encrypted. **This
    has a real implication for the core extraction (sequencing step 1):
    RNS identity storage and any LXMF message persistence should be
    written encrypted-at-rest via a Keystore-backed key
    (`SecureKeystore.getOrCreateKey`, or `androidx.security.crypto`'s
    `EncryptedFile`/`MasterKey` built on the same mechanism) from the
    start**, not added later — that's what makes the panic wipe on that
    data actually instant and reliable rather than falling back to
    "overwrite however many megabytes of message history this device has
    accumulated."
  - **Every app-related cache**, not just the "obviously important" state
    (identity keys, contacts/favorites) — this includes any RNS/LXMF
    on-disk caches, link/path caches, rendered-page caches, etc. "All local
    app data" means all of it.
  - **Regenerate a brand-new RNS identity that is completely unrelated to
    the old one** — not derived from it, not recoverable from anything left
    on the device. A fresh keypair with no cryptographic or stored linkage
    back to the wiped identity.
  - **Resolved (Aug 2026): no confirmation dialog** — the third tap wipes
    immediately, matching Bitchat's actual behavior. A confirmation dialog
    defeats the point of a panic button (speed matters in the scenario this
    exists for); the friction that prevents accidental triggering is the
    gesture itself (3 taps on one small element within a short window),
    not a modal. If this turns out to be too easy to trigger by accident in
    practice, revisit — but don't default to a confirmation dialog just
    because it feels safer on paper.

## Link activation safety (added Aug 2026)

Requirement gathered before the browsing screens exist (blocked on
`micron2compose`) — captured here so it's not lost before that work
starts.

**A link tap must not immediately activate the link when the target is a
file download or a destination outside the mesh (an external web
URL/`http(s)://` link embedded in a `.mu` page).** Show an explicit warning
first — filename + MIME type for downloads (this is also already required
by `porting-notes.md` §4's "always confirm before pulling an arbitrary
binary" — that section and this requirement are the same rule, just
stated from two angles), and a "this leaves the mesh and opens an external
browser" warning for outbound web links, since that's a materially
different trust boundary than navigating to another NomadNet node.
Ordinary in-mesh page-to-page navigation (`` `[label`hash:/page/foo.mu] ``)
doesn't need this — the warning is specifically for the two riskier link
shapes, not a blanket confirm-every-link interstitial that would make
normal browsing tedious. `micron2compose`'s link metadata (see its own
handoff doc) needs to expose enough about a link's target shape — file
vs. page vs. external URL — for the browsing screen to tell these apart
before activation, not just after.

## Out of scope for this repo

- The Bluetooth-mesh interface implementation itself (separate repo/doc)
- `micron2kivy` / anything Sideband-PR-specific (separate, secondary track)
- Executable/dynamic page support (deliberately excluded, see above)
