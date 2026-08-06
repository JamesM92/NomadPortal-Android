"""
nomadportal_core — Android-side orchestration/glue layer.

This module intentionally does nothing real yet beyond the Chaquopy
smoke test below. The actual RNS/LXMF core logic (link handling,
page/file requests, node discovery, messaging) is NOT a subpackage here —
it landed as its own top-level package, `nomadnet_web`, extracted
separately into `python-core/src/nomadnet_web/` at the repo root and
wired into Chaquopy as an additional source set (see
`app/build.gradle.kts`'s `chaquopy.sourceSets` block) rather than copied
in here. That's sequencing step 1, done — see the
`nomadportal-android-core-extraction` memory for the full story.

What belongs in *this* package: the Android-specific wiring that connects
`nomadnet_web`'s classes together for this app specifically — the
equivalent of what the original Flask app's `create_app()` did (instantiate
`IdentityStore`, `NodeBrowser`, `MessagingService`, etc. and connect them),
adapted for Chaquopy/scoped storage instead of a Flask app factory. That
orchestration layer doesn't exist yet either — it's what
`NoopInterfaceController`, `StubMessagingRepository`, and
`StubBrowserRepository` (Kotlin side) are waiting on to become real.
"""


def ping() -> str:
    """Smoke-test hook called from Kotlin to confirm the embedded
    interpreter is alive, this package is importable, and RNS/LXMF
    actually import at runtime on-device — not just at build time (pip
    install succeeding under Chaquopy doesn't guarantee the native
    `cryptography` extension actually loads correctly on a real device's
    ABI/libc, which is why this is a runtime check, not just a build one).
    Does not initialize Reticulum itself (no Transport, no Identity, no
    on-disk config) — that's real core-extraction work, not a smoke test.
    """
    import sys

    try:
        import RNS
        import LXMF
        rns_status = f"RNS {RNS.__version__}, LXMF {LXMF.__version__}"
    except Exception as e:
        rns_status = f"RNS/LXMF import FAILED: {e}"

    return f"nomadportal_core alive, python {sys.version.split()[0]}, {rns_status}"
