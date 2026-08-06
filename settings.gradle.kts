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
includeBuild("../micron2compose") {
    dependencySubstitution {
        substitute(module("com.github.JamesM92:micron2compose"))
            .using(project(":micron2compose"))
    }
}
