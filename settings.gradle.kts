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
            content { includeGroup("com.github.mik3y") }
        }
    }
}

rootProject.name = "NomadPortal-Android"
include(":app")

// micron2compose isn't published (no GitHub remote/tag yet — its README's
// JitPack instructions describe the intended future consumption path, not
// today's). Composite-build it from the sibling checkout instead, with a
// dependency substitution matching the exact Maven coordinate the README
// documents (com.github.JamesM92:micron2compose) — app/build.gradle.kts
// declares that coordinate normally, and this substitution transparently
// redirects it to build from source. When the library is actually
// published, delete this block; the declared dependency coordinate in
// app/build.gradle.kts doesn't need to change at all.
//
// Location is overridable via the MICRON2COMPOSE_DIR env var. Local dev
// defaults to a true sibling checkout (../micron2compose) — this repo's
// own dev-setup convention. CI can't use that: actions/checkout@v7
// refuses to place a checkout outside $GITHUB_WORKSPACE (a real failure
// hit here: "Repository path '.../micron2compose' is not under
// '.../NomadPortal-Android'"), so ci.yml checks micron2compose out to a
// subdirectory of this repo's own workspace instead and points this at
// that via the env var — see ci.yml's matching comment on its own
// checkout step.
val micron2composeDir = System.getenv("MICRON2COMPOSE_DIR") ?: "../micron2compose"
includeBuild(micron2composeDir) {
    dependencySubstitution {
        substitute(module("com.github.JamesM92:micron2compose"))
            .using(project(":micron2compose"))
    }
}

// Same composite-build shape as micron2compose above, for the same reason
// (not published anywhere yet) -- RNS_BLE_Wrapper's own rnsble-core module
// now declares a real group (com.jamesm92.rnsble, added specifically to make
// this substitution possible) since it previously had none. Location
// overridable via RNS_BLE_WRAPPER_DIR, same CI-checkout-location reasoning
// as MICRON2COMPOSE_DIR above (actions/checkout@v7 refuses to place a
// checkout outside $GITHUB_WORKSPACE).
val rnsBleWrapperDir = System.getenv("RNS_BLE_WRAPPER_DIR") ?: "../RNS_BLE_Wrapper"
includeBuild(rnsBleWrapperDir) {
    dependencySubstitution {
        substitute(module("com.jamesm92.rnsble:rnsble-core"))
            .using(project(":rnsble-core"))
    }
}
