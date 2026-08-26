pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // usb-serial-for-android (RNode-over-USB support) isn't on Maven
        // Central — confirmed via a direct search.maven.org query, not
        // assumed — only distributed via JitPack, same as any other
        // GitHub-hosted `com.github.<owner>:<repo>` coordinate.
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroup("com.github.mik3y")
                // micron2compose — published at v0.2.0; see
                // gradle/libs.versions.toml's own comment.
                includeGroup("com.github.JamesM92")
            }
        }
    }
}

rootProject.name = "NomadPortal-Android"
include(":app")

// micron2compose is published (v0.2.0, tagged on JitPack — see
// gradle/libs.versions.toml's own comment) — app/build.gradle.kts's
// ordinary `implementation(libs.micron2compose)` declaration resolves
// straight to that artifact now, no composite build needed. This block
// used to substitute a sibling checkout in its place while the library
// was still unreleased; removed once the real tag existed to depend on.

// RNS_BLE_Wrapper isn't published yet, so it still needs a composite build
// the way micron2compose used to (see above). Its own rnsble-core module
// declares a real group (com.jamesm92.rnsble, added specifically to make
// this substitution possible) since it previously had none. Location
// overridable via the RNS_BLE_WRAPPER_DIR env var — local dev defaults to a
// true sibling checkout (../RNS_BLE_Wrapper); CI can't use that
// (actions/checkout@v7 refuses to place a checkout outside
// $GITHUB_WORKSPACE — a real failure hit here), so ci.yml checks it out to
// a subdirectory of this repo's own workspace instead and points this at
// that via the env var.
val rnsBleWrapperDir = System.getenv("RNS_BLE_WRAPPER_DIR") ?: "../RNS_BLE_Wrapper"
includeBuild(rnsBleWrapperDir) {
    dependencySubstitution {
        substitute(module("com.jamesm92.rnsble:rnsble-core"))
            .using(project(":rnsble-core"))
    }
}
