"""
nomadnet_web — UI-agnostic RNS/LXMF core, extracted from the original
NomadPortal Flask web app (jamesm92/nomadportal, MIT, same author/owner as
this project).

This is nomadportal_android_handoff.md's sequencing step 1: "Extract
NomadPortal's UI-agnostic core logic into its own module first, with no
Chaquopy/Android dependency yet — validate it still runs and passes any
existing tests as plain Python." That's exactly what this package is.

**What's here**: every module from the original `nomadnet_web` package
that had zero Flask coupling to begin with (confirmed by grepping for
Flask imports across the whole package before extracting anything — most
of it was already cleanly separated, this wasn't a strip-Flask-out task so
much as a leave-the-Flask-files-behind one):

- browser.py — NodeBrowser: RNS Link establishment/caching, announce-driven
  retry, node discovery, page/file fetching (porting-notes.md §2's
  reliability lessons live here).
- messaging.py — MessagingService: LXMF send/receive, icon-appearance
  encoding.
- lxmf_sync.py — PropagationSyncService: propagation-node outbound sync.
- lxmf_tracker.py — LXMFPeerTracker: peer delivery-state tracking.
- identity_store.py, contact_store.py, message_store.py, user_store.py —
  on-disk persistence for identity, contacts, message history, users.
- cache.py — PageCache.
- ui_settings.py — UISettings: the three-tier access model
  (full/gated/locked) from porting-notes.md §3.
- rate_limit.py, log_buffer.py, scanner.py, config_gen.py, site_server.py —
  supporting pieces (rate limiting, log ring-buffer, pluggable virus
  scanner, Reticulum config generation, static-file node-hosting server).

**What's deliberately NOT here**: `routes.py`, `admin_routes.py`,
`auth.py`, `csrf.py`, and the original `__init__.py`'s `create_app()` —
all genuinely Flask-specific (HTTP routing, session auth, CSRF, the Flask
app factory). This package's own `__init__.py` is a from-scratch minimal
replacement with no Flask dependency at all — Android-side orchestration
(wiring these classes together, equivalent to what `create_app()` did) is
separate work, not done here.

Package name kept as `nomadnet_web` (not renamed to `nomadportal_core`)
specifically so the original test suite could be copied over with zero
import-path edits — a deliberate way to keep this extraction's own
correctness-verification as low-risk as possible. Renaming happens (if at
all) when this gets wired into the Android app's Chaquopy config, as a
separate, deliberate step — not bundled into "did the extraction preserve
behavior."
"""

__version__ = "1.2.0"  # carried over from the source package's __version__
