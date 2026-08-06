# python-core

`nomadportal_android_handoff.md` sequencing step 1: NomadPortal's
UI-agnostic RNS/LXMF core, extracted from the original Flask web app
(`jamesm92/nomadportal`, MIT, same author/owner) with no Chaquopy/Android
dependency — validated as plain Python before anything Android-specific
touched it.

See `src/nomadnet_web/__init__.py`'s docstring for exactly what's here,
what was deliberately left behind (the Flask routing/auth/session layer),
and why the package name wasn't changed during extraction.

`src/` layout (package under `src/nomadnet_web/`, not at the project root)
is deliberate, not just convention — it's what lets `app/build.gradle.kts`
point Chaquopy's `sourceSets` at `src/` alone and bundle only
`nomadnet_web` into the app, without `tests/`/`README.md`/
`requirements.txt` coming along for the ride.

## Status

- **Extraction**: done, validated against the original test suite
  unmodified — **44/44 pass**, zero import-path edits needed (confirms the
  source was already cleanly separated from Flask; this wasn't a
  strip-Flask-out job so much as a leave-the-Flask-files-behind one). One
  file (`user_store.py`, an OIDC multi-user web-login registry) was
  excluded entirely rather than force-fixed — it depends on `werkzeug` and
  exists for NomadPortal's shared/multi-user web deployment mode, which
  doesn't apply to this app's single-user, local-only architecture.
- **Wired into Chaquopy**: done. `app/build.gradle.kts`'s
  `chaquopy.sourceSets` points at `src/` directly (one source of truth, no
  copy), and `chaquopy.pip` installs `rns==1.3.9`/`lxmf==1.0.1` — pinned to
  match this package's own `requirements.txt` exactly (see that file's
  comment for the regression history behind that specific pin; the Android
  app briefly ran unpinned-latest before this package existed to have an
  opinion to match).

**Not yet done**: no Android-side orchestration layer exists yet — the
equivalent of the original `create_app()`'s wiring (instantiating
`IdentityStore`, `NodeBrowser`, `MessagingService`, etc. and connecting
them together). That's real design work, not a mechanical port, since
`create_app()` itself was pure Flask app-factory shape. The stub
implementations already built on the Kotlin side
(`NoopInterfaceController`, `StubMessagingRepository`,
`StubBrowserRepository`) are what actually become real once this exists.

## Running the tests

```sh
python -m venv .venv
.venv/Scripts/activate  # or .venv/bin/activate on Linux/Mac
pip install -r requirements.txt
pytest
```
