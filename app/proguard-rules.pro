# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in build.gradle.kts (getDefaultProguardFile).

# isMinifyEnabled is now true for release (see app/build.gradle.kts) —
# per a real, measured need: Material Icons Extended alone accounted for
# ~35.8MB of the unminified debug APK's ~118MB (8,573 classes, confirmed by
# actually disassembling the built APK's dex files, not estimated), because
# nothing was ever tree-shaken. R8 needs explicit help wherever this app's
# code is reached only via runtime reflection, which static analysis can't
# see — the two real cases here, both confirmed by reading the actual
# call sites, not guessed:

# 1. Chaquopy's own runtime bridge (com.chaquo.python.Python/PyObject/
#    PyException/etc., used throughout this app's orchestrator.callAttr()
#    call sites) resolves Python<->Java calls reflectively at runtime.
-keep class com.chaquo.python.** { *; }

# 2. RNS_BLE_Wrapper's real Python<->Kotlin interop boundary
#    (com.jamesm92.rnsble.interop) — nomadnet_web.rns_ble_interface.py
#    calls bridge.send()/getCurrentMtu()/stop()/setReceiveCallback() by
#    name on a live RnsBleBridge object handed to Python via
#    orchestrator.py's set_bluetooth_mesh_bridge (confirmed directly
#    against that Python source, not assumed); setReceiveCallback's own
#    PacketReceiver interface is then *implemented* by Python the other
#    direction, via Chaquopy's java.dynamic_proxy, which needs the real
#    interface shape preserved to work at all. Keeping this whole (small)
#    package rather than cherry-picking individual members — both
#    directions of this boundary need to survive intact, and it's cheap
#    enough that there's no real minification cost to keeping all of it.
-keep class com.jamesm92.rnsble.interop.** { *; }

# Preserves real stack-trace line numbers in a release build's crash logs
# — matches Chaquopy's own demo app's identical rule (chaquo/chaquopy
# demo/app/proguard-rules.pro), not invented here.
-keepattributes SourceFile,LineNumberTable
