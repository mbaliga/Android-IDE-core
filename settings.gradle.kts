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

// com.asystemofcells:crash-recovery and :interaction-mode are single-sourced from
// mbaliga/Shared-Libraries-asoc, pinned as a git submodule at ./shared-libraries and composited
// in via includeBuild. The shared libraries live under the `com.asystemofcells` group, NOT under
// `dev.aarso`: Aarso is a distinct module of the constellation and does not lend its name to
// shared infrastructure (see that repo's README, "Coordinate change"). Hyle's old
// `dev.aarso:crash-recovery` tombstone is on a different group now, so it can no longer contend
// with shared-libraries for the same coordinate; the explicit substitutions below stay anyway
// because they make each composite offer exactly what it is meant to. Update the pin with:
//   git -C shared-libraries fetch && git -C shared-libraries checkout <sha> && git add shared-libraries
includeBuild("shared-libraries") {
    dependencySubstitution {
        substitute(module("com.asystemofcells:crash-recovery")).using(project(":crash-recovery"))
        // :interaction-mode — the shared Regular/asoc choice (2026-09-15 ruling). Same
        // composited-module idiom as :crash-recovery above: a plain SharedPreferences-backed
        // store + the pure ModeDefaults policy, no UI (the picker is HyleModePicker, in the
        // hyle-design-system includeBuild below). Update the pin with:
        //   git -C shared-libraries fetch && git -C shared-libraries checkout <sha> && git add shared-libraries
        substitute(module("com.asystemofcells:interaction-mode")).using(project(":interaction-mode"))
    }
}

// Hyle is single-sourced from its own repo (mbaliga/Hyle-Design-System), pinned as a git
// submodule at ./hyle-design-system and composited in via includeBuild. Gradle substitutes
// any `com.asystemofcells:hyle` dependency with that build's :hyle project (matched on group:name),
// so there is no vendored :hyle module here anymore. Update the pin with:
//   git -C hyle-design-system fetch && git -C hyle-design-system checkout <sha> && git add hyle-design-system
//
// Explicit substitution, scoped to exactly what this includeBuild provides (:hyle). Its
// :crash-recovery module is only a tombstone for the retired `dev.aarso:crash-recovery`
// coordinate and must never be picked up as a real dependency.
includeBuild("hyle-design-system") {
    dependencySubstitution {
        substitute(module("com.asystemofcells:hyle")).using(project(":hyle"))
    }
}

// Fonebrew; design thesis: legibility + cognitive sovereignty. ("Aarso"/"mirror" now
// names only the within-axis self-reflection lens, domain/mirror/ — see CLAUDE.md.)
rootProject.name = "Fonebrew"
include(":app")
include(":core-engine")
include(":sdengine")
include(":hyle-probe")
// WatchBrew wire contract (docs/design/watchbrew.md §2): pure Kotlin/JVM, no Android deps.
include(":watch-bridge")
// WatchBrew, the Wear OS app (docs/design/watchbrew.md section 2): depends ONLY on :watch-bridge,
// never on :core-engine (no native engine libs on the wrist). applicationIds mirror the phone's dist
// flavors so the Wearable Data Layer routes between the pair.
include(":watchbrew")
