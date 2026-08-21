"""
NomadNet node server.

Serves pages and files from a local directory over the Reticulum network,
making this instance a first-class NomadNet node that any NomadNet client
can browse.

Pages live in <pages_dir>/ and are served at request path /page/<filename>.
Files live in <files_dir>/ and are served at request path /file/<filename>.
Sub-directories are supported; they are served at their relative path.

The node identity is persisted to <identity_file> so the destination hash
stays constant across restarts.

**NomadPortal-Android-specific hardening**: the original (Docker/desktop)
version of this module let an executable page file run as a subprocess
and served its stdout as the response — real NomadNet server behavior,
useful for dynamic pages on a machine the operator controls directly.
That capability has been removed entirely here, not merely left unused —
per explicit product direction, hosting from this app is deliberately
restricted to plain `.mu` markup only, no Python/executables. A phone is
a very different trust boundary than an operator's own server: the
site's content is authored (and potentially imported) through this app's
own editor, on a device the user carries around and may hand to others,
so "a page file can execute arbitrary code with this process's
permissions" is a real risk here in a way it wasn't for the original
desktop tool. Enforced at the serving layer (this file), not only in the
authoring UI, so it holds regardless of how a page file ended up on
disk.
"""

import json
import logging
import os
import threading
import time
from typing import Optional

log = logging.getLogger(__name__)

DEFAULT_ANNOUNCE_INTERVAL = 6 * 60 * 60  # 6 hours — matches NomadNet's own default
MIN_ANNOUNCE_INTERVAL     = 60           # 1 minute — guard against accidental flooding
MAX_ANNOUNCE_INTERVAL     = 24 * 60 * 60 # 24 hours — beyond this, peers' paths age out
RESCAN_INTERVAL    = 5  * 60   # re-scan pages/files every 5 minutes
START_ANNOUNCE_DELAY = 6        # seconds after start before first announce

# Served whenever no real index.mu exists in the pages directory (see
# _register_pages' own fallback route below) — a fresh install's pages
# directory starts empty, so this is what anyone browsing to a brand
# new NomadPortal-Android install's node actually sees, until/unless the
# operator replaces it with their own real index.mu via the file nav
# (SiteFilesScreen.kt). Written to double as an honest pitch for the
# app itself, per explicit direction — "for a new install the index.mu
# will be a sales pitch for the nomad portal android project, for
# anybody who may come across it." Every claim in it is something this
# app actually does, verified this session, not aspirational copy —
# including the "no Python/executables" line, which is exactly what
# this module's own hardening (see this file's module doc comment)
# makes true. Deliberately doesn't claim a download link/public release
# that doesn't exist yet.
#
# Markup verified directly against markqvist/NomadNet's own
# MicronParser.py (not guessed) — headings ('>'/'>>'), inline
# bold/italic/underline toggles ('`!'/'`*'/'`_', each a toggle, not a
# start/end pair — closed by repeating the same escape), centered/reset
# alignment ('`c'/'`a'), and horizontal dividers (a line whose *first*
# character is '-', unconditionally — confirmed real bullet points
# can't start with a literal "-" for exactly that reason, hence '•'
# below instead of "-" as this page's own list marker).
#
# _LOGO_ASCII_ART, below, is a real ASCII-art rendering of this app's
# own TentPortalMark (AppLogo.kt) — generated offline via
# github.com/JamesM92/Img2ContourAscii (contour/shape-matching, not
# plain brightness-to-character mapping) against a from-source PIL
# reproduction of that composable's exact Canvas geometry, at --cols 30
# with --color --palette-size 4 --hysteresis 0.5 (a small, real palette
# matched to the mark's own actual colors — NomadAccent's tent blue,
# NomadPortalPurple's doorway, the seam/guy-line shade, background —
# rather than one color per pixel). Went through two real size
# corrections, both from on-device reports, not guesses: --cols 52 first
# (measured noticeably wider than this app's own MIN_MICRON_CONTENT_WIDTH
# 320.dp phone-width floor — see BrowserScreen.kt), then --cols 28
# still reported as needing horizontal scroll to see fully. That second
# report turned up a real, separate bug rather than a sizing miss: the
# rich editor's own "true view" (RichTextPageEditor.kt's
# RealRenderedBlockRow) was rendering Micron content at bodyLarge
# (16sp), not BrowserScreen's own bodyMedium * 0.85 (~11.9sp) — a wider
# font than the real target renderer actually uses, so identical content
# needed more width there than it would in a real NomadNet client. Fixed
# there directly (font-size parity, not just this art's own size) plus
# --cols 20 here for real margin.
#
# Per explicit direction ("make the ascii art of our logo 50% bigger"),
# --cols 30 (a real, literal 50% bump from 20). Trying this first
# surfaced what looked like a real regression (the same phone this
# whole sizing history is about needed real horizontal scrolling to
# read even plain paragraph text below the art) — but that turned out
# to be a different, pre-existing bug this art's own resize just
# happened to expose: this page's prose paragraphs (see
# _DEFAULT_INDEX's own doc comment below) were single unwrapped lines
# ~500 characters long, already the real widest content on the page by
# a wide margin over anything the art itself was doing at any col
# count. Fixed that at the actual root (hard-wrapping the prose, not
# shrinking the art) — confirmed via an actual on-device fresh-install
# browse that --cols 30 fits fine once the prose isn't competing for
# width anymore. Also regenerated from a from-source PIL reproduction
# that had itself drifted stale relative to three later real on-device
# fixes to TentPortalMark's own geometry (stake Y, guy-line draw order,
# guy-anchor anti-aliasing nudge — see draw_logo.py's own doc comment,
# kept offline alongside the rest of this generation pipeline) — this
# art had quietly been built from the *old*, pre-fix logo shape ever
# since, without anyone noticing until this resync. That tool is
# GPL-3.0 — same real
# license-conflict reasoning already documented on DEFAULT_EXAMPLES_PAGE
# below applies here, so it's used purely as an offline one-time
# generator, never imported or shipped as a runtime dependency; only its
# plain-text *output* (not GPL-encumbered) is embedded, converted from
# the tool's own raw ANSI 24-bit-color escapes into Micron color markup,
# run-collapsed (Micron pays one escape per *run*, not per character) by
# a small offline script, not by hand.
#
# Real, on-device-reported compatibility bug, found after this had
# already shipped once: the script originally emitted Micron's precise
# 6-hex color form (`FTrrggbb...`f — confirmed genuinely spec-valid
# against NomadNet's own real MicronParser.py source), but a real other
# Micron viewer this page was actually browsed with rendered it as
# literal garbage text instead of a color (the raw hex digits leaking
# through), rather than just falling back to an uncolored render — that
# viewer doesn't implement the 6-hex form at all. Per explicit direction
# ("we should stick to the 3hex color codes for the default page as not
# all viewers are compatible with the high precision color"), the script
# now quantizes every color down to the older, more-widely-supported
# 3-hex shorthand (`Frgb...`f, each digit doubled) instead — a real
# interop fix, not a downgrade for its own sake; see the script's own
# updated doc comment (ansi_to_micron.py, kept offline alongside the
# rest of this generation pipeline, not part of this repo) for the exact
# quantization. DEFAULT_EXAMPLES_PAGE below still *demonstrates* the
# 6-hex form further down — that's deliberate: it's educational content
# explicitly labeled as an alternate, less-supported form, not this
# app's own default output, so it's a different case from this art.
# That same script also strips any glyph the tool colored to match the
# source image's own real background (#131313, this app's actual
# NomadBg) — palette quantization occasionally buckets faint/low-
# contrast pixels (the apex's thin tip, antialiasing) into that cluster
# and still assigns a real glyph character to the cell; coloring it that
# exact shade renders it genuinely invisible against this app's real
# background (not just faint), which read as a missing/skipped row
# rather than what it actually was — replaced with a plain space
# instead, visually identical either way. `--exclude` separately dropped
# backtick, backslash, AND double-quote from the character palette —
# backtick because a raw one in Micron source is always an escape-
# sequence opener, never a literal character, so one surviving in the
# art would corrupt whatever ran after it; backslash because an
# unescaped one inside this Python triple-quoted string would otherwise
# start a `\U########`-style unicode escape and fail to compile (an
# earlier pass caught this the hard way); double-quote because a
# generated `"""` mid-art would prematurely close this very string
# literal (caught the same way, on an earlier pass, at what would have
# been the artwork's own "'''" divider line). Left-aligned, not
# `c-centered — Micron's centering strips/recomputes leading whitespace
# per line, which would destroy this art's column alignment.
_LOGO_ASCII_ART = """              `F367->`f
             `F367.!!:`f
             `F5acU`f`F367>-`f`F5acU`f`F367_`f
            `F5acdU`f`F367>-`f`F5acU&`f
           `F5acdUU`f`F367>-`f`F5acUUb`f
          `F5acdUUU`f`F367>-`f`F5acUUUb`f
         `F5acuUUUU`f`F367>-`f`F5acUUUUo`f
        `F367v`f`F5acUUUUU`f`F367>-`f`F5acUUUUU`f`F367c`f
       `F367.`f`F5acUUUUAV`f`F96c:cf`f`F5acAUUUU`f`F367:`f
      `F367.`f`F5acUUUUV`f`F96cuiiiic|`f`F5acUUUU`f`F367.`f
      `F5acUUUUE`f`F96ciiiiiiic`f`F5acVUUU&`f
     `F367:`f`F5acUUUU`f`F96cniiiiiiii!`f`F5acUUUU`f`F367:`f
    `F367!-`f`F5acUUUU`f`F96c!iiiiiiii!`f`F5acUUUU`f`F367-!`f
   `F367v!`f `F5acUUUU`f`F96c!iiiiiiii!`f`F5acUUUU`f `F367!v`f
  `F367v!`f  `F5acUUUU`f`F96c!iiiiiiii!`f`F5acUUUU`f  `F367!v`f
 `F367:!`f   `F5acUUUU`f`F96c!iiiiiiii!`f`F5acUUUU`f   `F367!v`f
`F367:!`f    `F5acUUUU`f`F96c!iiiiiiii!`f`F5acUUUU`f    `F367!:`f
`F367!`f     `F5acYYYY`f`F96c!YYYYYYYY!`f`F5acYYYY`f     `F367!`f"""

# Real bug found while chasing what looked at first like a logo-sizing
# regression (a "make the art 50% bigger" request): the actual widest
# line on this whole page — by a wide margin, ~500 raw characters vs.
# the art's own ~50 — was always the prose paragraphs below, written as
# single long unwrapped lines. MicronPage renders with softWrap=false
# (confirmed via BrowserScreen.kt's own real horizontalScroll/
# TextMeasurer-sized-contentWidth setup — this app never reflows Micron
# text, matching real NomadNet client behavior, since Micron itself has
# no reflow concept), so the whole page's content width is sized to its
# single widest line — meaning these paragraphs, not the art, were what
# forced real horizontal scrolling even for plain text on an actual
# on-device fresh-install browse. Every paragraph/bullet below is now
# hard-wrapped at a real terminal-safe width (~44 characters, tuned
# down from an initial ~48 after an on-device check found even that
# still needed a few characters of horizontal scroll on the exact
# MIN_MICRON_CONTENT_WIDTH-floor phone this is all about) with
# literal newlines, matching how real NomadNet page authors actually
# write body text for a format that never reflows client-side — not a
# workaround, the correct way to author a Micron page. Re-wrap by hand
# if this content changes again; there's no automated re-wrap step.
#
# Headings need their *own*, much narrower budget than body text, for
# the same width-measurement reason — confirmed directly against
# BrowserScreen.kt's own real contentWidth calculation, not guessed:
# every block renders in the same monospace font, but a `>>` heading
# measures at 20sp and `>` at 24sp against body text's ~12sp
# (bodyMedium * 0.85), so a heading's real on-screen width is roughly
# 1.7x/2x its own character count, not 1x. A 34-character `>>` heading
# ("Why this page is boring on purpose") was, on its own, wider than
# every hard-wrapped body paragraph below it despite looking short —
# shortened to "Boring on purpose" instead of wrapped, since a heading
# wrapping onto a second line reads as two separate headings, not one.
# This app's own `>` H1 title line ("NomadPortal-Android") is close to
# this same real limit purely from the app's own name length — a
# structural cost of the H1 being what it is, not something wrapping
# can fix, and not something this pass changed.
_DEFAULT_INDEX = """>NomadPortal-Android
`cA native Android app for Reticulum & NomadNet`a

-

""" + _LOGO_ASCII_ART + """

-

`!Open beta`! -- this app works and this page is
proof, but it's still under active
development. Expect rough edges, and expect
things to keep changing. If that's not what
you're looking for today, check back later;
if it is, keep reading.

-

If you're reading this from another NomadNet
client, you've found a real, live instance
of NomadPortal-Android -- this page is being
served from an ordinary Android phone in
someone's pocket, not a server. It's a
native Kotlin/Compose app built around the
real, embedded Reticulum and LXMF reference
implementations (not a reimplementation),
for infrastructure-free mesh communication
-- a different project from the original
NomadPortal Docker server, which is built
for infrastructure you administer, not for
carrying around.

>>What it does

• Message people over LXMF: text, photos,
  audio, and files
• Browse and host NomadNet pages, like this
  one
• Connect over TCP and local Wi-Fi discovery,
  with Bluetooth mesh and RNode/LoRa support
  in progress
• A panic-wipe safeguard that securely erases
  identity and message data in seconds
• Never asks for location access, full stop

>>Why get it

If you're already on Reticulum, this is a
way to carry your identity, contacts, and a
node in your pocket instead of leaving them
on a machine somewhere -- no separate server
to run, no infrastructure to maintain, just
a phone. If you're new to Reticulum and
NomadNet, this page you're looking at right
now is a real example of what the network
actually looks like in practice: an ordinary
phone, hosting a real page, reachable over
whatever link happens to connect the two of
you.

There's no app-store listing yet -- get it
directly from the source, build it yourself,
and see exactly what's running:
https://github.com/JamesM92/NomadPortal-Android

>>Boring on purpose

Pages hosted by NomadPortal-Android are
plain Micron markup only -- no Python, no
executables, ever. A real NomadNet server
can run scripts to build pages dynamically;
this app deliberately never does, because a
phone carried in someone's pocket is a
different trust boundary than a server an
operator administers directly. What you're
reading is exactly the file it is, nothing
more.

>>Permissions

Every permission this app requests is
optional -- denying any of them just turns
that one feature off, the rest of the app
keeps working normally. It never requests
location access, under any circumstances.

-

`[See examples.mu on this node`/page/examples.mu]
for a quick tour of what this page's own
markup can do.
"""

# A real file (not a synthetic fallback like _DEFAULT_INDEX above) —
# seeded once, the first time a fresh install's pages directory is
# created (see orchestrator.py's _site_pages_dir()), so it shows up in
# the file nav like any other page: editable, deletable, a starting
# point rather than a hidden magic route. Original content, not a copy
# of NomadNet's own example guide — that project is GPL-3.0, a real
# license conflict with this one (PolyForm Noncommercial), so this
# demonstrates the same underlying Micron syntax with entirely original
# text instead. Every construct here is verified directly against
# MicronParser.py, not guessed.
DEFAULT_EXAMPLES_PAGE = """>Micron quick tour
`aA few of the formatting options available on this node's pages.

-

>>Headings

`*This line uses the `!>`! heading marker`*
`*Two markers ( `!>>`! ) make a smaller sub-heading, like the one above`*

>>Text styles

`!Bold`! text uses a backtick then an exclamation mark on each side.
`*Italic`* text uses a backtick then an asterisk.
`_Underline`_ text uses a backtick then an underscore.
Combine them: `!`_bold and underlined together`_`!

>>Colors

`Fa30Foreground color`f is set with a backtick, F, then a 3-digit shorthand hex code -- each digit doubled, so a30 becomes aa3300. This is the only form this app's own editor ever writes, and the only one used on this page.
`FT2e8b57Foreground color (precise)`f uses a backtick, F, T, then a full 6-digit hex code instead of the 3-digit shorthand -- real, spec-valid Micron, but not every client renders it; a real client tested against this app's own hosted pages showed raw hex digits instead of a color where this form was used, so this app's own editor deliberately never writes it, even though it would let you match a color exactly rather than one of 16 shades per channel.
`B502Background color`b works the same way, with B instead of F.
`BT1a1a2e`FTe0c068Foreground and background together`f`b combines both on the same run using the precise form above, just to demonstrate the syntax -- this app's own editor writes `B223`Fdb6 instead (both 3-digit, the nearest representable shade of each), which is what its Highlight button actually produces.

>>Alignment

`cThis line is centered.
`rThis line is right-aligned.

>>Dividers

A line whose first character is a dash becomes a horizontal rule, like the ones on this page:

-

>>What this page can't do

Micron also defines interactive fields -- text inputs, checkboxes, and radio buttons, opened with a backtick and a less-than sign and closed with a greater-than sign. They only do anything when a page is generated by a script on the server side, reading back whatever the visitor submitted. NomadPortal-Android never serves executable pages, by design -- every page here is a plain .mu file, nothing runs on this phone to process a submission -- so fields would render but silently go nowhere. This page leaves them out rather than showing something that looks interactive but isn't.

Everything above renders the same way on any real NomadNet client -- this page is just plain text with a few backtick-escaped instructions in it, nothing more.
"""


def seed_starter_content(pages_dir: str) -> None:
    """Writes index.mu and examples.mu the first time [pages_dir] is
    created — real, editable/deletable/visible-in-the-file-nav starter
    files. index.mu here is a real file with the exact same pitch
    content as _DEFAULT_INDEX above (not just that synthetic fallback,
    which only ever covers the case where no real index.mu exists at
    all) — a fresh install's file nav would otherwise show only
    examples.mu, with the actual index page nowhere visible/editable
    even though it's genuinely what's being served, which is exactly
    the confusing gap this closes. _DEFAULT_INDEX itself stays as a
    safety net for the case an operator deletes their index.mu.

    No-op per file if it already exists, so this is always safe to call
    on every startup, not just the genuinely-first one — callers don't
    need to track "was this directory actually new" themselves."""
    _seed_file(pages_dir, "index.mu", _DEFAULT_INDEX)
    _seed_file(pages_dir, "examples.mu", DEFAULT_EXAMPLES_PAGE)


def _seed_file(pages_dir: str, filename: str, content: str) -> None:
    path = os.path.join(pages_dir, filename)
    if os.path.exists(path):
        return
    try:
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(content)
    except OSError as exc:
        log.warning("Could not seed %s: %s", filename, exc)


class SiteServer:
    """Hosts a NomadNet node, serving pages and files over Reticulum."""

    def __init__(
        self,
        pages_dir: str,
        files_dir: str,
        identity_file: str,
        node_name: Optional[str] = None,
        auto_announce: bool = False,
        announce_interval: int = DEFAULT_ANNOUNCE_INTERVAL,
        stats_file: Optional[str] = None,
        own_identity_hashes: Optional[set] = None,
    ):
        # ``node_name=None`` means "auto-generate from the destination hash"
        # in start() — produces e.g. "NomadPortal-Android-4de" so multiple
        # NomadPortal-hosted nodes on the same network can be told apart
        # at a glance, and the name itself is unambiguous about which app
        # is serving it (per explicit direction).
        #
        # ``auto_announce`` defaults False so a vanilla NomadPortal install
        # is a *silent* host: it still serves pages to anyone who knows the
        # hash, but it won't spam the network with broadcast announces.
        # Operators who actually want to publish their site flip this on.
        # Manual announces (Admin → Dashboard → "Announce now") always work.
        #
        # ``announce_interval`` controls how often the background loop
        # re-announces when ``auto_announce`` is on. The value is in seconds
        # and is clamped to ``[MIN_ANNOUNCE_INTERVAL, MAX_ANNOUNCE_INTERVAL]``.
        # The background loop reads ``self._announce_interval`` per
        # iteration, so admin-UI live updates take effect on the next tick.
        self._pages_dir         = pages_dir
        self._files_dir         = files_dir
        self._identity_file     = identity_file
        self._node_name         = node_name
        self._auto_announce     = auto_announce
        self._announce_interval = max(MIN_ANNOUNCE_INTERVAL,
                                      min(MAX_ANNOUNCE_INTERVAL,
                                          int(announce_interval)))
        self._dest          = None
        self._identity      = None
        self._node_hash: Optional[str] = None
        self._last_announce = 0.0
        self._last_rescan   = 0.0
        self._running       = False
        # Tracked so stop() can deregister exactly what start()/the
        # rescan loop registered — RNS.Destination has no "deregister
        # everything" call, only per-path deregister_request_handler().
        self._registered_paths: set = set()

        # View counter (per explicit direction — "are we able to set up
        # a view counter for our hosted node," then corrected: "the view
        # counter is for people visiting the node from outside") — real
        # request counts, not a synthetic/estimated figure: incremented
        # in _serve_page/_serve_file only on an actual successful serve
        # (a 404 or a mid-read I/O error doesn't count as a real view),
        # AND only when the requester isn't this same device — see
        # own_identity_hashes below. Keyed by request path
        # ("/page/index.mu") so a future per-page breakdown is already
        # there if wanted, not just a bare total.
        #
        # own_identity_hashes: real hex identity hashes to exclude from
        # counting — this device's own messaging/LXMF identity (what
        # BrowserScreen's "identify to this node" toggle actually sends,
        # see that feature's own doc comment) plus this site's own
        # hosting identity, both passed in by orchestrator.py since
        # SiteServer itself only ever knows about the latter. Only
        # catches *identified* self-visits (remote_identity is None for
        # anonymous requests, self or otherwise, with no way to tell
        # them apart at this layer) — a real, honest partial guard, not
        # a claim of perfect self-exclusion. In practice, an anonymous
        # self-visit also requires this device's own browse-to-self path
        # to actually work, which it currently doesn't (a separate,
        # already-flagged bug) — so this covers the one case that's
        # realistically reachable today.
        self._own_identity_hashes = own_identity_hashes or set()
        #
        # stats_file=None (SiteServer's own unit tests, or any caller
        # that doesn't care about surviving a restart) means in-memory
        # only, counting from zero every time start() runs — real,
        # persisted counts are opt-in via a real path, same shape as
        # identity_file above, not a hidden requirement of this class.
        self._stats_file = stats_file
        self._page_views: dict = {}
        self._load_stats()

    def _load_stats(self) -> None:
        if not self._stats_file or not os.path.isfile(self._stats_file):
            return
        try:
            with open(self._stats_file, "r", encoding="utf-8") as fh:
                data = json.load(fh)
            loaded = data.get("page_views", {})
            if isinstance(loaded, dict):
                self._page_views = {str(k): int(v) for k, v in loaded.items()}
        except (OSError, ValueError, TypeError) as exc:
            log.warning("Could not load site view stats: %s", exc)

    def _save_stats(self) -> None:
        if not self._stats_file:
            return
        try:
            os.makedirs(os.path.dirname(self._stats_file), exist_ok=True)
            with open(self._stats_file, "w", encoding="utf-8") as fh:
                json.dump({"page_views": self._page_views}, fh)
        except OSError as exc:
            log.warning("Could not save site view stats: %s", exc)

    def _record_view(self, request_path: str, remote_identity=None) -> None:
        # remote_identity is an RNS.Identity, not a plain hash — .hash is
        # already the real raw bytes any other identity-hash comparison
        # in this codebase compares against (see e.g. browser.py's own
        # dest_hash handling), hex-encoded here to match
        # own_identity_hashes' own hex-string convention.
        if remote_identity is not None:
            try:
                if remote_identity.hash.hex() in self._own_identity_hashes:
                    return
            except AttributeError:
                pass
        self._page_views[request_path] = self._page_views.get(request_path, 0) + 1
        self._save_stats()

    def total_views(self) -> int:
        return sum(self._page_views.values())

    def page_views(self) -> dict:
        """A fresh copy — callers must not be able to mutate this
        instance's own real counts through the returned dict."""
        return dict(self._page_views)

    def start(self) -> str:
        """Start the node server. Returns the destination hexhash."""
        import RNS

        os.makedirs(self._pages_dir, exist_ok=True)
        os.makedirs(self._files_dir, exist_ok=True)

        # Load or create the persistent node identity. The actual path is
        # ``self._identity_file`` (operator-controlled config); we don't
        # echo it here because CodeQL's
        # ``py/clear-text-logging-sensitive-data`` rule heuristically
        # flags any variable named ``..._identity_file`` as "sensitive
        # data" being logged. Operators who need the path can grep
        # ``identity_file`` out of the config or run ``ls`` on
        # ``$RNS_CONFIG_DIR``.
        if os.path.exists(self._identity_file):
            self._identity = RNS.Identity.from_file(self._identity_file)
            log.info("Loaded site identity from disk")
        else:
            self._identity = RNS.Identity()
            self._identity.to_file(self._identity_file)
            log.info("Created and persisted a new site identity")

        # Register the nomadnetwork.node destination
        self._dest = RNS.Destination(
            self._identity,
            RNS.Destination.IN,
            RNS.Destination.SINGLE,
            "nomadnetwork",
            "node",
        )
        # No explicit set_proof_strategy() call, deliberately — verified
        # directly against markqvist/NomadNet's own real Node.py, which
        # doesn't set one either, leaving RNS's own default
        # (Destination.PROVE_NONE). An earlier version of this line set
        # PROVE_ALL, an unexplained deviation from the reference this app
        # otherwise mirrors closely; removing it fixed a real reported bug
        # ("other clients see the announce but the link times out") —
        # confirmed by the reporter reaching the hosted site successfully
        # from another client immediately after this change, on a build
        # with no other change made. RNS's own Destination.py source
        # shows proof_strategy is read only by outbound proof-of-delivery
        # replies to plain Packets, never consulted by
        # incoming_link_request()/Link.validate_request() — so the real
        # mechanism connecting PROVE_ALL to a broken Link handshake isn't
        # fully understood, but the fix is real and verified, not
        # theoretical.
        self._dest.set_link_established_callback(self._peer_connected)

        self._node_hash = self._dest.hexhash

        # Auto-generate a unique-by-default name when the operator hasn't set
        # one. "NomadPortal-Android" (not just "NomadPortal") makes it
        # unambiguous which app is serving the node at a glance, per
        # explicit direction; suffix is the last 3 hex chars of the
        # destination hash — short enough to fit naturally in the sidebar,
        # distinct enough that many NomadPortal-Android nodes on the same
        # network are individually addressable (4096 combinations).
        if not self._node_name:
            self._node_name = f"NomadPortal-Android-{self._node_hash[-3:]}"

        self._register_pages()
        self._register_files()

        # node_hash and node_name are public identifiers (broadcast in
        # every announce), so logging them is operationally safe. But
        # CodeQL's clear-text-logging-sensitive-data rule heuristically
        # tags ``self._node_hash`` as identity-related and persistently
        # flagged this line through both v0.9.21's variable-drop and
        # v0.9.22's .replace-barrier approaches. Operators can correlate
        # this NomadPortal with announces by checking
        # /config/reticulum/site_identity.id directly; the startup log
        # confirms readiness without echoing the hash.
        log.info("Site node ready")

        # Announce shortly after start and then on a timer
        self._running = True
        t = threading.Thread(target=self._background_jobs, daemon=True)
        t.start()

        return self._node_hash

    def stop(self) -> None:
        """Stop hosting: ends the background announce/rescan loop and
        deregisters every page/file path this instance registered, so
        this destination no longer answers requests. Does not (cannot,
        in this RNS version — no API for it) fully un-create the
        underlying `RNS.Destination`/identity; a peer who already
        cached this node's hash could still attempt a link, it would
        just find nothing registered to serve. Safe to call even if
        never started (e.g. the hosting toggle was flipped on then
        straight back off before start() finished)."""
        self._running = False
        if self._dest is not None:
            for path in list(self._registered_paths):
                try:
                    self._dest.deregister_request_handler(path)
                except Exception:
                    log.debug("Deregister failed for a registered path (see exception log)")
            self._registered_paths.clear()
        log.info("Site node stopped")

    def node_hash(self) -> Optional[str]:
        return self._node_hash

    def node_name(self) -> str:
        return self._node_name

    def files_dir(self) -> str:
        return self._files_dir

    def pages_dir(self) -> str:
        return self._pages_dir

    def fetch_page(self, path: str) -> tuple:
        """Serve a page directly from the filesystem (bypasses RNS link).

        Returns (content_bytes, error_str) — exactly one will be None.
        path should be the page path, e.g. '/index.mu' or '/page/index.mu'.

        No longer takes an identity/field-data pair — those only ever
        existed to reach executable pages (a "who's viewing" fingerprint,
        local form-submit round-tripping), which this module no longer
        supports at all (see this file's own module doc comment)."""
        # Normalise to bare filename (strip /page/ prefix if present)
        p = path.strip("/")
        if p.startswith("page/"):
            p = p[len("page/"):]
        if not p:
            p = "index.mu"

        file_path = os.path.realpath(os.path.join(self._pages_dir, p))
        pages_root = os.path.realpath(self._pages_dir)
        if not file_path.startswith(pages_root + os.sep) and file_path != pages_root:
            return None, "Invalid path"
        if not os.path.isfile(file_path):
            return None, f"Page not found: {p}"

        try:
            with open(file_path, "rb") as fh:
                return fh.read(), None
        except Exception as exc:
            log.error("Error serving local page %s: %s", file_path, exc)
            return None, str(exc)

    def announce(self) -> None:
        if self._dest is None:
            return
        try:
            self._dest.announce(app_data=self._node_name.encode("utf-8"))
            self._last_announce = time.time()
            log.info("Site node announced (%s)", self._node_hash[:16] if self._node_hash else "?")
        except Exception as exc:
            log.warning("Site announce failed: %s", exc)

    def set_node_name(self, name: str) -> None:
        """Renames this hosted node — takes effect on the *next*
        announce (this device's own name is broadcast as announce
        app_data, not pushed proactively), same "persisted, applied
        live where there's live state to update" shape as
        MessagingService.set_display_name for the LXMF identity."""
        self._node_name = name or self._node_name

    def set_auto_announce(self, enabled: bool) -> None:
        self._auto_announce = enabled

    def set_announce_interval(self, seconds: int) -> None:
        self._announce_interval = max(MIN_ANNOUNCE_INTERVAL, min(MAX_ANNOUNCE_INTERVAL, int(seconds)))

    # ------------------------------------------------------------------
    # Page / file registration  (mirrors NomadNet's Node.register_pages)
    # ------------------------------------------------------------------

    def _register_pages(self) -> None:
        if self._dest is None:
            return

        pages: list[str] = []
        self._scan_dir(self._pages_dir, pages)

        # Register a default index if none exists
        has_index = any(p.endswith("/index.mu") or p.endswith(os.sep + "index.mu") for p in pages)
        root_index = os.path.join(self._pages_dir, "index.mu")
        if not has_index and not os.path.isfile(root_index):
            self._dest.register_request_handler(
                "/page/index.mu",
                response_generator=self._serve_default_index,
                allow=self._dest.ALLOW_ALL,
            )
            self._registered_paths.add("/page/index.mu")

        for full_path in pages:
            rel = full_path[len(self._pages_dir):]
            request_path = "/page" + rel.replace(os.sep, "/")
            try:
                self._dest.register_request_handler(
                    request_path,
                    response_generator=self._serve_page,
                    allow=self._dest.ALLOW_ALL,
                )
                self._registered_paths.add(request_path)
            except Exception:
                # CodeQL persistently flags any log line that includes a
                # filesystem-derived ``request_path`` as
                # clear-text-logging-sensitive-data. Both v0.9.21
                # (variable drop) and v0.9.22 (.replace barrier) failed
                # to clear it. Just log the exception server-side
                # without the path — operators can find the failing
                # page by inspecting the pages directory and reproducing
                # the registration call.
                log.debug("Page registration failed (see exception log)")
                log.exception("Page registration failure")

        self._last_rescan = time.time()
        log.debug("Registered %d page(s)", len(pages))

    def _register_files(self) -> None:
        if self._dest is None:
            return

        files: list[str] = []
        self._scan_dir(self._files_dir, files)

        for full_path in files:
            rel = full_path[len(self._files_dir):]
            request_path = "/file" + rel.replace(os.sep, "/")
            try:
                self._dest.register_request_handler(
                    request_path,
                    response_generator=self._serve_file,
                    allow=self._dest.ALLOW_ALL,
                    auto_compress=32_000_000,
                )
                self._registered_paths.add(request_path)
            except Exception:
                # Same as the pages-register loop above — CodeQL flags
                # filesystem-derived path vars in log lines persistently.
                # Log the exception without echoing the path.
                log.debug("File registration failed (see exception log)")
                log.exception("File registration failure")

        log.debug("Registered %d file(s)", len(files))

    def _scan_dir(self, base: str, result: list) -> None:
        if not os.path.isdir(base):
            return
        for entry in os.listdir(base):
            if entry.startswith("."):
                continue
            full = os.path.join(base, entry)
            if os.path.isfile(full) and not entry.endswith(".allowed"):
                result.append(full)
            elif os.path.isdir(full):
                self._scan_dir(full, result)

    # ------------------------------------------------------------------
    # Request handlers
    # ------------------------------------------------------------------

    def _peer_connected(self, link) -> None:
        log.debug("Peer connected to site node")

    def _serve_page(self, path, data, request_id, link_id, remote_identity, requested_at):
        file_path = path.replace("/page", self._pages_dir, 1)
        log.debug("Page request: %s → %s", path, file_path)
        try:
            if not os.path.isfile(file_path):
                return b">Page Not Found\n\nThe requested page does not exist."

            # Plain markup only — never executed, regardless of the
            # file's own permission bits. See this module's own doc
            # comment for why (a real capability removed, not merely a
            # code path this app happens not to exercise).
            with open(file_path, "rb") as fh:
                content = fh.read()
            # Counted only here, past the not-found check above — a
            # missing page is a 404, not a real view of anything.
            self._record_view(path, remote_identity)
            return content

        except Exception as exc:
            log.error("Error serving page %s: %s", path, exc)
            return None

    def _serve_file(self, path, data, request_id, link_id, remote_identity, requested_at):
        file_path = path.replace("/file", self._files_dir, 1)
        file_name = path.replace("/file/", "", 1)
        log.debug("File request: %s → %s", path, file_path)
        try:
            handle = open(file_path, "rb")
            self._record_view(path, remote_identity)
            return [handle, {"name": file_name.encode("utf-8")}]
        except Exception as exc:
            log.error("Error serving file %s: %s", path, exc)
            return None

    def _serve_default_index(self, path, data, request_id, link_id, remote_identity, requested_at):
        self._record_view(path, remote_identity)
        return _DEFAULT_INDEX.encode("utf-8")

    # ------------------------------------------------------------------
    # Background jobs
    # ------------------------------------------------------------------

    def _background_jobs(self) -> None:
        time.sleep(START_ANNOUNCE_DELAY)
        if self._auto_announce:
            self.announce()
        else:
            log.info(
                "Site node silent (auto-announce off) — hash %s reachable "
                "only by direct request. Flip Admin → Settings → "
                "Auto-announce to On (or set SITE_ANNOUNCE=true) to publish; "
                "the dashboard \"Announce now\" button is disabled while "
                "silent.",
                self._node_hash[:16] if self._node_hash else "?",
            )

        while self._running:
            time.sleep(60)
            # Wrap each iteration so a raise in announce() /
            # _register_pages() / _register_files() doesn't silently
            # kill the whole thread — that's the failure mode where
            # /healthz keeps reporting green (RNS is fine, interfaces
            # are up) but the site stops announcing without any
            # user-visible signal. Log and continue.
            try:
                now = time.time()
                if self._auto_announce and now - self._last_announce > self._announce_interval:
                    self.announce()
                if now - self._last_rescan > RESCAN_INTERVAL:
                    self._register_pages()
                    self._register_files()
            except Exception:
                log.exception(
                    "site_server background loop raised — continuing"
                )

    def last_announce_at(self) -> float:
        """Unix timestamp of the last successful announce, or 0 if we
        haven't announced yet. Used by /healthz to detect a silently-
        dead announce loop (green interfaces, running container, but
        no announces going out).
        """
        return self._last_announce

    def announce_interval(self) -> int:
        """The currently-configured announce interval (seconds).
        Live-updated by the admin route when it changes, so /healthz's
        "we should have announced by now" check sees the current value.
        """
        return self._announce_interval

    def auto_announce_enabled(self) -> bool:
        return self._auto_announce
