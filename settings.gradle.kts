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

// Auto-provisions the JDK toolchain (Java 17) when it isn't installed locally,
// so the project builds on a clean machine without manual JDK setup.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Hyle is single-sourced from its own repo (mbaliga/Hyle-Design-System), pinned as a git
// submodule at ./hyle-design-system and composited in via includeBuild. Gradle substitutes
// any `dev.aarso:hyle` dependency with that build's :hyle project (matched on group:name),
// so there is no vendored :hyle module here anymore. Update the pin with:
//   git -C hyle-design-system fetch && git -C hyle-design-system checkout <sha> && git add hyle-design-system
includeBuild("hyle-design-system")

// Fonebrew; design thesis: legibility + cognitive sovereignty. ("Aarso"/"mirror" now
// names only the within-axis self-reflection lens, domain/mirror/ — see CLAUDE.md.)
rootProject.name = "Fonebrew"
include(":app")
include(":core-engine")
include(":sdengine")
include(":hyle-probe")
