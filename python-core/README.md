# python-core

`nomadportal_android_handoff.md` sequencing step 1: NomadPortal's
UI-agnostic RNS/LXMF core, extracted from the original Flask web app
(`jamesm92/nomadportal`, MIT, same author/owner) with no Chaquopy/Android
dependency — validated as plain Python before anything Android-specific
touches it.

See `nomadnet_web/__init__.py`'s docstring for exactly what's here, what
was deliberately left behind (the Flask routing/auth/session layer), and
why the package name wasn't changed during extraction.

## Status

Extraction done, validated against the original test suite unmodified —
**44/44 pass**, zero import-path edits needed (confirms the source was
already cleanly separated from Flask; this wasn't a strip-Flask-out job so
much as a leave-the-Flask-files-behind one). One file (`user_store.py`,
an OIDC multi-user web-login registry) was excluded entirely rather than
force-fixed — it depends on `werkzeug` and exists for NomadPortal's
shared/multi-user web deployment mode, which doesn't apply to this app's
single-user, local-only architecture.

**Not yet done** — this package isn't wired into the Android app yet:

- Not embedded in Chaquopy (`app/build.gradle.kts`'s `chaquopy.pip` block
  still just installs bare `rns`/`lxmf`, unpinned-latest, with none of this
  package's code).
- `rns`/`lxmf` version mismatch to reconcile first: this package's
  `requirements.txt` pins `rns==1.3.9`/`lxmf==1.0.1` (exactly what
  NomadPortal's own `requirements.txt` validated against, with a
  documented regression history behind that pin), while
  `app/build.gradle.kts` currently installs latest (1.4.2/1.1.1). Don't
  wire this in without resolving that first — running this code against
  an untested newer rns/lxmf combo risks exactly the kind of regression
  that pin's comments describe.
- No Android-side orchestration layer yet (the equivalent of the original
  `create_app()`'s wiring — instantiating `IdentityStore`, `NodeBrowser`,
  `MessagingService`, etc. and connecting them together) — that's real
  work, not a mechanical port, since `create_app()` itself was pure Flask
  app-factory shape.
- The four stub implementations already built (`NoopInterfaceController`,
  `StubMessagingRepository`, `StubBrowserRepository`) are what actually
  become real once the above is done.

## Running the tests

```sh
python -m venv .venv
.venv/Scripts/activate  # or .venv/bin/activate on Linux/Mac
pip install -r requirements.txt
pytest
```
