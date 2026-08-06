// Top-level build file where you can add configuration options common to all
// sub-projects/modules.
// AGP 9.0+ has Kotlin support built in — the separate
// org.jetbrains.kotlin.android plugin is no longer applied (or allowed).
// See https://kotl.in/gradle/agp-built-in-kotlin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.chaquopy) apply false
}
