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

// dev.aarso:crash-recovery is single-sourced from mbaliga/Shared-Libraries-asoc (Personal-
// Tracker D-V, superseding D-O), pinned as a git submodule at ./shared-libraries and
// composited in via includeBuild. hyle-design-system keeps a compile-time tombstone at the
// old :crash-recovery coordinate (see MOVED.md there) so an un-migrated consumer gets an
// actionable DeprecationLevel.ERROR at the call site instead of a silent wrong artifact —
// which is exactly why the explicit substitution below is load-bearing, not decorative: with
// the tombstone still declaring `group = "dev.aarso"` / artifactId "crash-recovery", TWO
// composites now offer that one coordinate, and Gradle fails configuration with
// "Module version 'dev.aarso:crash-recovery' is not unique in composite: can be provided by
// [project :hyle-design-system:crash-recovery, project :shared-libraries:crash-recovery]"
// (see Hyle-Design-System PR #15 / this repo's PR #15 for the failure reproduced in full).
// Declaring ANY explicit dependencySubstitution for an included build disables *automatic*
// group:name substitution for that whole build — so naming :crash-recovery here removes
// shared-libraries as an automatic-match candidate for anything else it might one day expose,
// and naming :hyle on the hyle-design-system block below removes hyle-design-system as an
// automatic-match candidate for its own (tombstoned) :crash-recovery. Two explicit rules,
// each keeping its build to exactly what it's meant to still provide, so the two composites
// never contend for the same coordinate again. Update the pin with:
//   git -C shared-libraries fetch && git -C shared-libraries checkout <sha> && git add shared-libraries
includeBuild("shared-libraries") {
    dependencySubstitution {
        substitute(module("dev.aarso:crash-recovery")).using(project(":crash-recovery"))
        // :interaction-mode — the shared Regular/asoc choice (2026-09-15 ruling). Same
        // composited-module idiom as :crash-recovery above: a plain SharedPreferences-backed
        // store + the pure ModeDefaults policy, no UI (the picker is HyleModePicker, in the
        // hyle-design-system includeBuild below). Update the pin with:
        //   git -C shared-libraries fetch && git -C shared-libraries checkout <sha> && git add shared-libraries
        substitute(module("dev.aarso:interaction-mode")).using(project(":interaction-mode"))
    }
}

// Hyle is single-sourced from its own repo (mbaliga/Hyle-Design-System), pinned as a git
// submodule at ./hyle-design-system and composited in via includeBuild. Gradle substitutes
// any `dev.aarso:hyle` dependency with that build's :hyle project (matched on group:name),
// so there is no vendored :hyle module here anymore. Update the pin with:
//   git -C hyle-design-system fetch && git -C hyle-design-system checkout <sha> && git add hyle-design-system
//
// Explicit substitution, scoped to exactly what this includeBuild still legitimately
// provides (:hyle) — see the shared-libraries block above for why this is required now that
// hyle-design-system also carries a :crash-recovery tombstone at the same `dev.aarso`
// coordinate shared-libraries now owns for real.
includeBuild("hyle-design-system") {
    dependencySubstitution {
        substitute(module("dev.aarso:hyle")).using(project(":hyle"))
    }
}

// Fonebrew; design thesis: legibility + cognitive sovereignty. ("Aarso"/"mirror" now
// names only the within-axis self-reflection lens, domain/mirror/ — see CLAUDE.md.)
rootProject.name = "Fonebrew"
include(":app")
include(":core-engine")
include(":sdengine")
include(":hyle-probe")
