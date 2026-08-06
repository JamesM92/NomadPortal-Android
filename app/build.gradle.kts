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
    // the actual dependency graph needs. minSdk 24 is Chaquopy 17.0's
    // documented floor.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jamesm92.nomadportal"
        minSdk = 24
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
        // RNS + LXMF placeholder — real dependency pinning happens once the
        // NomadPortal core extraction (separate task) lands in
        // app/src/main/python/. Left empty for now so the scaffold builds.
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
