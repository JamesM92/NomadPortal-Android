# Changelog

This file documents all notable changes to NomadPortal-Android.
The format follows [Keep a Changelog](https://keepachangelog.com/), and `versionName`/`versionCode` in `app/build.gradle.kts` track the same number.

## [Unreleased]

## [0.0.3] - 2026-08-26

### Added

- RNode-over-USB connectivity + an official-firmware-only ESP32 flasher. Real hardware verification is still pending — this pass was compile/emulator-verified only, see the RNode plan's own risk list.
- Depend on micron2compose's actual published `v0.2.0` release instead of composite-building it from a sibling checkout.

### Fixed

- A real `imePadding()` double-counting bug that squeezed the message list and clipped the page editor's input field when the on-screen keyboard opened.
- A real Network → Sites stall/lag under load, fixed across several rounds: windowed/memoized rendering for large announce lists, a hot shared flow for the node poll (instead of restarting per tab switch), node deltas instead of full list rebuilds, and skipping the rebuild entirely when the list is unchanged.
- A page fetch no longer gets silently discarded when its triggering coroutine is cancelled (e.g. backgrounding mid-load).
- A real double status-bar inset that was appearing above every screen's top bar.

### Changed

- Real swipe-with-momentum tab switching on Sites and Messages.
- Replaced four separate per-interface auto-announce intervals with one shared one.
- Added a visible QR-share icon to Identities rows and fixed low scan-icon contrast.

## [0.0.2] - 2026-08-23

### Added

- A curated set of default favorited sites seeded on first launch.
- Allow favoriting a node this device has never heard announce.
- A real, external-visitor-only view counter for the self-hosted node.
- A real launcher icon and colored logo art.

### Fixed

- A real update-failure bug: builds now share one debug keystore instead of each machine generating its own random one, so an update from a different build machine actually installs.
- Link-carried field params being silently dropped.
- A crash on identity import; 3-hex Micron colors; own-hosted-node browsing; real incoming-call notifications.
- `collectAsState()` called inline on a repository flow was restarting its poll on every recomposition — fixed across every call site, now a standing project convention.
- Site-hosting empty-pages bug; hosted-page cache with pull-to-refresh.

### Changed

- Split Sites into real Favorites/Announces sub-tabs.
- Page editor: no-wrap, deeper TCP interface seeding, dark-by-default.

## [0.0.1] - 2026-08-19

Initial beta release. Highlights across the features that shipped before this tag:

- Chaquopy + Jetpack Compose app shell, with `rns`/`lxmf` verified running for real under Chaquopy on Android.
- Real TCP connectivity, verified against a live mesh; LXMF messaging; NomadNet site browsing via micron2compose.
- A self-hosted NomadNet node (SiteServer), with a rich/raw page editor.
- Real Bluetooth mesh connectivity (RNS_BLE_Wrapper integration).
- Voice calls: a real LXST-compatible call-signalling engine with two-way Opus audio, call history, and logging.
- A client-only remote shell over Reticulum (rnsh), with a device-credential gate.
- QR-code identity sharing (generate + scan an LXMF address).
- A guided first-run onboarding flow.
- A ground-up UI redesign: bottom navigation, the full Material 3 type scale, an 8dp spacing grid, and complete M3 color-scheme roles.
- Per-conversation disappearing messages; Columba-style identicons for contacts with no sent icon.
- Settings rebuilt as one scrollable page of collapsible sections.
- Connectivity/privacy controls: permissions flow, a Keystore-based panic wipe, and connectivity toggles.
