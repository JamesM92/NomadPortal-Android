# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in build.gradle.kts (getDefaultProguardFile).

# isMinifyEnabled is currently false (see app/build.gradle.kts) — no rules
# needed yet. When minification is turned on, Chaquopy's runtime and any
# reflection-based RNS/LXMF Java-interop code will need explicit -keep
# rules; test release builds against a real device before shipping, since
# ProGuard/R8 stripping bugs surface at runtime, not at build time.
