"""
nomadportal_core — placeholder package for the UI-agnostic RNS/LXMF core
logic extracted from the original NomadPortal Flask app.

This module intentionally does nothing real yet. Its only job right now is
to prove the Chaquopy embedding works end-to-end (see
nomadportal_android_handoff.md, "Suggested sequencing", step 2) before any
actual RNS/LXMF extraction (step 1) lands here.

Do not add real link-handling/page-request logic to this file directly —
that extraction is its own task, deliberately scoped separately, and should
probably land as its own subpackage (e.g. nomadportal_core.browser,
mirroring nomadnet_web/browser.py) rather than growing this __init__.py.
"""


def ping() -> str:
    """Smoke-test hook called from Kotlin to confirm the embedded
    interpreter is alive and this package is importable."""
    import sys

    return f"nomadportal_core alive, python {sys.version.split()[0]}"
