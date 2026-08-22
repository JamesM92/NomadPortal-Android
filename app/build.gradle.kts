plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy)
}

// Real gap found via a direct user question ("does the debug log include the build #
// and etc?") -- it didn't. With build/commit tracking now spanning two composite-built
// repos (see the apk-delivery-location memory's build-number scheme), the in-app debug
// log needs to self-identify which commit of *both* NomadPortal-Android and the
// composite-built RNS_BLE_Wrapper sibling it was actually built from, not rely on
// whoever's testing to remember which upload filename they installed.
fun gitShortSha(dir: File): String = try {
    val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(dir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    output.ifBlank { "unknown" }
} catch (e: Exception) {
    "unknown"
}

val nomadPortalGitSha = gitShortSha(rootDir)
// Matches settings.gradle.kts's own RNS_BLE_WRAPPER_DIR override/default resolution.
val rnsBleWrapperDir = File(rootDir, System.getenv("RNS_BLE_WRAPPER_DIR") ?: "../RNS_BLE_Wrapper")
val rnsBleWrapperGitSha = gitShortSha(rnsBleWrapperDir)

android {
    namespace = "com.jamesm92.nomadportal"
    // compileSdk 37: Chaquopy 17.0's own demo pins compileSdk 36, but that
    // failed a real build here — androidx.core 1.19.0 / lifecycle-runtime-
    // compose-android 2.11.0 (pulled in transitively) both require
    // compiling against API 37+. Bumped past the demo's pin to match what
    // the actual dependency graph needs.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jamesm92.nomadportal"
        // minSdk 31 (not Chaquopy 17.0's documented floor of 24): the
        // Bluetooth-mesh interface needs BLE scanning, and Android only
        // supports scanning without location permission via
        // BLUETOOTH_SCAN's `neverForLocation` flag on API 31+ — on API
        // 24-30 the OS itself ties BLE scan results to location permission
        // with no bypass. This app's product requirement is "never request
        // location, full stop" (see nomadportal_android_handoff.md's "Main
        // menu / connectivity & privacy controls" section), so minSdk was
        // raised to make that requirement satisfiable everywhere the app
        // runs, rather than gating BLE mesh behind a runtime SDK check on
        // an app that otherwise supports API 24+.
        minSdk = 31
        targetSdk = 37
        versionCode = 2
        // v0.0.2 — second real beta release tag. v0.0.1 (versionCode 1)
        // was cut before a substantial batch of real fixes/features
        // landed on dev this same session (identity-import crash fix,
        // 3-hex Micron colors, Save As, own-hosted-node browsing fix,
        // icon categories, real incoming-call notifications, and more)
        // — this bump exists so that work actually reaches a real,
        // installable beta build instead of sitting merged-but-
        // unreleased indefinitely.
        versionName = "0.0.2"

        buildConfigField("String", "GIT_SHA", "\"$nomadPortalGitSha\"")
        buildConfigField("String", "RNSBLE_GIT_SHA", "\"$rnsBleWrapperGitSha\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Chaquopy 17 / Python 3.12 only ships prebuilt interpreters for
            // these two ABIs (armeabi-v7a was dropped) — confirmed against a
            // real build failure, not from docs.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // Real bug found+fixed via an actual failed on-device update, not
    // guessed: without this, every build's debug APK gets signed with
    // whichever machine happened to build it's own auto-generated
    // ~/.android/debug.keystore — a real, different, randomly-generated
    // certificate per machine (confirmed directly: `apksigner verify
    // --print-certs` on the real v0.0.1 GitHub-Actions-built release
    // APK vs. a real locally-built v0.0.2 APK showed two completely
    // different SHA-256 cert fingerprints). Android hard-requires an
    // update to be signed with the *exact same* certificate as
    // whatever's already installed — a mismatch fails
    // (INSTALL_FAILED_UPDATE_INCOMPATIBLE) on every device, every time,
    // exactly what two real phones both hit trying to update from
    // v0.0.1. A single, real, checked-into-the-repo keystore (debug
    // signing keys are never sensitive the way a real release key is —
    // this is the standard, accepted practice, unlike
    // proguard-rules.pro's own real release-signing concerns) makes
    // every build from here forward — CI or local, any machine — share
    // one identical certificate, so updates actually work. One-time
    // real cost: this fix's own first build still won't "update" over
    // any earlier build signed with a machine-specific cert (v0.0.1,
    // and the v0.0.2 already handed out before this fix landed) — those
    // need one clean uninstall-then-reinstall to get onto this new
    // shared-keystore lineage; every build after that updates normally.
    signingConfigs {
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // Real, measured trigger, not a default-flip-it-on: Material
            // Icons Extended alone accounted for ~35.8MB of the
            // unminified debug APK (confirmed by actually disassembling
            // the built APK's dex files) purely because nothing was ever
            // tree-shaken. See proguard-rules.pro's own doc comment for
            // the real -keep rules this needed (Chaquopy's own runtime
            // bridge, and RNS_BLE_Wrapper's real Python<->Kotlin interop
            // boundary — both reached only via runtime reflection, which
            // R8 can't see on its own).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // No signingConfig here on purpose — there's still no real
            // release-signing key (a separate, bigger decision the user
            // hasn't made yet; see debug.keystore's own doc comment for
            // why that one is fine to commit and this one wouldn't be).
            // A debug-keystore signingConfig was applied here temporarily,
            // build-then-revert, purely to get a real installable APK for
            // this change's own on-device verification pass (confirmed
            // clean: real emulator install, every major screen navigated
            // with no crash, and the one genuinely at-risk path —
            // RnsBleBridge's Chaquopy reflection boundary — exercised for
            // real via the Bluetooth mesh toggle, confirmed via a real
            // "Interface 'bluetooth_mesh' enabled" log line, not assumed).
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        // Building this module requires Python 3.12 on the build machine
        // (Chaquopy resolves/installs pip dependencies at build time, not
        // just on-device). Chaquopy auto-detects it via `py -3.12` (Windows)
        // / `python3.12` (Linux/Mac) — if that doesn't find your install,
        // set the CHAQUOPY_BUILD_PYTHON environment variable to a real
        // interpreter path (see the chaquopy-build-cycle convention).
        // Deliberately an env var, not a hardcoded buildPython() call
        // edited in and back out of this file for every single local
        // build (this repo's own history has that mistake committed and
        // caught more than once, and it's the exact class of footgun
        // sanity-checks' own "No committed buildPython() override" CI
        // step exists for) — an env var can never end up in a commit at
        // all, so there's nothing to remember to revert. See
        // https://chaquo.com/chaquopy/doc/current/android.html#buildpython.
        System.getenv("CHAQUOPY_BUILD_PYTHON")?.let { buildPython(it) }
        pip {
            // Pinned to exactly what python-core/requirements.txt (the
            // extracted nomadnet_web core) was validated against — NOT
            // latest. nomadportal's own requirements.txt documents a real
            // regression history behind this specific combo (an earlier
            // rns/lxmf bump broke inbound link establishment to hosted
            // sites). This app briefly ran unpinned-latest (1.4.2/1.1.1,
            // verified installing cleanly — same cryptography 42.0.8
            // Android wheel covers both, since rns's own dependency spec
            // is a broad `cryptography>=3.4.7` either way) before the core
            // extraction landed; reconciled down to match once python-core
            // existed to have an actual opinion to match. Bumping past
            // this pin is a decision to make deliberately, with its own
            // validation pass — not something to drift into.
            install("rns==1.3.9")
            install("lxmf==1.0.1")
            // identity_store.py (nomadnet_web) imports yaml directly — this
            // was missing here even though python-core/requirements.txt
            // pins it, because the Android pip block predates the core
            // extraction and was never reconciled against the extracted
            // package's actual import list. Confirmed missing via a real
            // on-device crash (ModuleNotFoundError: No module named
            // 'yaml'), not caught until first real orchestrator.start()
            // run on the emulator — pip installing cleanly at build time
            // doesn't catch a package that was simply never listed.
            install("pyyaml==6.0.3")
        }
    }

    // Points Chaquopy at python-core/src/ (the nomadnet_web package only —
    // python-core's src/ layout keeps tests/README/requirements.txt
    // outside this dir specifically so they don't get bundled into the
    // app too) rather than copying nomadnet_web into app/src/main/python/.
    // One source of truth, no drift between what python-core's own test
    // suite validates and what actually ships. python-core stays
    // independently testable as plain Python
    // (nomadportal_android_handoff.md sequencing step 1's own
    // requirement) precisely because this is an additive source root, not
    // a copy.
    //
    // Also picks up rnsble-core's own rns_ble_interface.py the same way —
    // Chaquopy only embeds a Python runtime into a com.android.application
    // module, not a library module (RNS_BLE_Wrapper's rnsble-core is one),
    // so that repo's own README documents this exact extra-source-directory
    // step as what an out-of-repo consumer needs.
    //
    // Two `../` hops, not one: unlike python-core (which lives *inside*
    // this repo, one level up from app/, at NomadPortal-Android/python-core),
    // RNS_BLE_Wrapper is a *sibling checkout* of this whole repo, matching
    // RNS_BLE_WRAPPER_DIR's own default in settings.gradle.kts
    // (../RNS_BLE_Wrapper *relative to the repo root*, i.e. one more hop up
    // than app/ itself sits at). Confirmed via a real build failure this
    // wasn't obvious from the settings.gradle.kts path alone: Chaquopy's
    // own mergeDebugPythonSources error named the exact wrong resolved
    // path it went looking for
    // (NomadPortal-Android/RNS_BLE_Wrapper/... instead of the real
    // .../JamesM92/RNS_BLE_Wrapper/...).
    sourceSets {
        getByName("main") {
            srcDir("../python-core/src")
            srcDir("../../RNS_BLE_Wrapper/rnsble-core/src/main/python")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // -extended supersedes -core (superset of its icons) — used instead of
    // -core once Message/Wifi/etc. icons were needed beyond core's set.
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.micron2compose)
    implementation(libs.rnsble.core)
    // QR-code identity sharing — see libs.versions.toml's own comment for
    // why CameraX + ZXing (not ML Kit) was picked.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    // rnsh's real device-credential gate — see MainActivity's own
    // FragmentActivity comment for why the base Activity class had to
    // change for this.
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.work.runtime.ktx)
    // Forces the whole dependency graph's androidx.fragment resolution up
    // to a modern version — see libs.versions.toml's own androidxFragment
    // comment for the real, on-device-traced crash this fixes (every
    // activity-result launcher throwing "Can only use lower 16 bits for
    // requestCode" on this FragmentActivity-based app, deterministically,
    // because of a real incompatibility between fragment 1.2.5's own
    // FragmentActivity override and ComponentActivity's ActivityResultRegistry
    // — not a version bump for its own sake).
    implementation(libs.androidx.fragment.ktx)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
