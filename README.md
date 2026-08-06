# NomadPortal-Android

A native Android app for browsing, hosting, and editing
[NomadNet](https://github.com/markqvist/NomadNet) (Reticulum) pages — an
independent, open-source alternative to
[Sideband](https://github.com/markqvist/Sideband) with a broader feature
set. A from-scratch rewrite inspired by
[`jamesm92/nomadportal`](https://github.com/JamesM92/nomadportal) (the
author's existing Flask-based web portal for the same protocol stack), not a
port of its Flask/Jinja layer.

**Status: early scaffold.** This is a bare-bones Android shell with a
working Chaquopy-embedded Python interpreter and a placeholder screen — no
browsing, hosting, or editor functionality yet. See
[`nomadportal_android_handoff.md`](nomadportal_android_handoff.md) for the
full architecture and build sequencing, and
[`porting-notes.md`](porting-notes.md) for the protocol-level lessons this
project inherits from the web version.

## Architecture

Chaquopy-embedded Python backend (RNS/LXMF core logic) + native
Kotlin/Jetpack Compose UI. Compose was chosen specifically because its
declarative model handles mixed text-and-interactive-widget layouts
natively — this matters once Micron page-hosting with form fields is
involved. Full rationale in the handoff doc.

## Build

Requires JDK 17 and the Android SDK (API 36; `minSdk` 24).

```sh
./gradlew assembleDebug
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

## Toolchain versions

Pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml):

| Component | Version | Notes |
|---|---|---|
| Android Gradle Plugin | 9.3.1 | |
| Gradle | 9.5.0 | matches Chaquopy's own tested combo |
| Kotlin | 2.4.10 | |
| Chaquopy | 17.0.0 | Python 3.10–3.14, min API 24 |
| Compose BOM | 2026.06.01 | |

These were cross-checked against Chaquopy's own live demo project
(`chaquo/chaquopy@master`, `demo/`) at scaffold time, since Chaquopy's
published compatibility table lags its actual latest-tested combo.

## License

[PolyForm Noncommercial 1.0.0](LICENSE) — free to use, modify, and
redistribute for any noncommercial purpose. Commercial use (including
repackaging into a paid product) requires a separate agreement with the
copyright holder. This is a deliberate departure from Sideband's CC
BY-NC-SA: same noncommercial spirit, but a license actually written for
software rather than creative works, with an explicit patent grant. See
[`nomadportal_android_handoff.md`](nomadportal_android_handoff.md#chaquopy--packaging-specifics)
for why this project isn't a fork of (and isn't bound by the license of)
Sideband itself.

## Security

See [`SECURITY.md`](SECURITY.md) for the threat model, vulnerability
reporting process, and what CI enforces on every PR (CodeQL, dependency
review, Gradle wrapper validation).
