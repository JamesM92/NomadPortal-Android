"""
nomadportal_core — Android-side orchestration/glue layer.

The actual RNS/LXMF core logic (link handling, page/file requests, node
discovery, messaging) is NOT a subpackage here — it landed as its own
top-level package, `nomadnet_web`, extracted separately into
`python-core/src/nomadnet_web/` at the repo root and wired into Chaquopy
as an additional source set (see `app/build.gradle.kts`'s
`chaquopy.sourceSets` block) rather than copied in here. That's
sequencing step 1, done — see the `nomadportal-android-core-extraction`
memory for the full story.

`orchestrator.py` (this package) is the Android-specific wiring that
connects `nomadnet_web`'s classes together — the equivalent of what the
original Flask app's `create_app()` did — see that module's own
docstring for the design (interfaces added via the running `Reticulum`
instance's own `_add_interface()`, `Reticulum()` constructed exactly
once). Kotlin's `RealInterfaceController` calls into it for connectivity
toggles (TCP and Wi-Fi discovery are wired to real behavior; RNode/
Bluetooth-mesh/hosting remain persisted-intent-only). As of Aug 2026,
`orchestrator.py` also exposes a browsing bridge (`get_nodes_json`,
`fetch_page_text`, `set_node_favorite`) and a messaging bridge
(`get_conversations_json`, `get_messages_json`, `send_message`,
`mark_conversation_read`, `get_contact_json`), backing Kotlin's
`RealBrowserRepository`/`RealMessagingRepository` — both poll these on an
interval rather than reacting to a push callback, since neither
`NodeBrowser` nor `MessagingService` expose one.
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
