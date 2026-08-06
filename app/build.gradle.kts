plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy)
}

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
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Chaquopy 17 / Python 3.12 only ships prebuilt interpreters for
            // these two ABIs (armeabi-v7a was dropped) — confirmed against a
            // real build failure, not from docs.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
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
        // set `buildPython("C:/path/to/python.exe")` locally (don't commit
        // a machine-specific path here). See
        // https://chaquo.com/chaquopy/doc/current/android.html#buildpython.
        pip {
            // rns/lxmf: verified installing cleanly for both target ABIs
            // (arm64-v8a, x86_64) as of this comment — Chaquopy's own PyPI
            // mirror has prebuilt Android wheels for cryptography 42.0.8
            // (rns's only native dependency), no source build needed. This
            // was nomadportal_android_handoff.md sequencing step 2's open
            // question ("does RNS/LXMF actually run correctly on Android
            // via Chaquopy") — installation is now verified; on-device
            // import/runtime behavior is checked by the "Test Python
            // bridge" button (nomadportal_core.ping()), but full RNS
            // initialization (Reticulum(), Identity, Transport) isn't
            // exercised until the real core extraction (still step 1,
            // still not started).
            install("rns")
            install("lxmf")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
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

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
