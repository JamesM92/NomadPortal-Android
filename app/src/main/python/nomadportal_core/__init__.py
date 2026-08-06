"""
nomadportal_core — placeholder package for the UI-agnostic RNS/LXMF core
logic extracted from the original NomadPortal Flask app.

This module intentionally does nothing real yet. Its only job right now is
to prove the Chaquopy embedding works end-to-end, including that RNS/LXMF
actually import at runtime on-device (see nomadportal_android_handoff.md,
"Suggested sequencing", step 2) before any actual RNS/LXMF extraction
(step 1) lands here.

Do not add real link-handling/page-request logic to this file directly —
that extraction is its own task, deliberately scoped separately, and should
probably land as its own subpackage (e.g. nomadportal_core.browser,
mirroring nomadnet_web/browser.py) rather than growing this __init__.py.
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
