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
includeBuild("hyle-design-system") {
    // The explicit rule is load-bearing, not decoration. hyle-design-system still contains a
    // :crash-recovery TOMBSTONE declaring the same dev.aarso:crash-recovery coordinate as the real
    // module in shared-libraries (deliberately — see that module's MOVED.md; it exists so consumers
    // who have NOT migrated get an actionable compile error rather than an unresolved dependency).
    // With two composites offering one coordinate, Gradle fails with:
    //
    //   Module version 'dev.aarso:crash-recovery' is not unique in composite: can be provided by
    //   [project :hyle-design-system:crash-recovery, project :shared-libraries:crash-recovery]
    //
    // Declaring ANY explicit substitution for an included build disables AUTOMATIC substitution for
    // that build, so naming :hyle here removes hyle-design-system as a crash-recovery candidate and
    // the coordinate resolves unambiguously from shared-libraries.
    dependencySubstitution {
        substitute(module("dev.aarso:hyle")).using(project(":hyle"))
    }
}

// Shared constellation libraries (dev.aarso:crash-recovery, dev.aarso:search-core), pulled in by
// the same sanctioned mechanism as Hyle (D-A): git submodule + Gradle includeBuild. Gradle
// substitutes those coordinates with this build's projects, so no Maven registry is involved.
//
// crash-recovery MOVED here from hyle-design-system (D-V, superseding D-O): it always had zero
// :hyle dependency, so keeping it inside the design-system repo forced apps that must never
// depend on Hyle to carry the whole Hyle submodule to reach it.
//
// This build pins AGP 8.9.1, identical to hyle-design-system, so both composites agree (D-Q).
includeBuild("shared-libraries")


// Aarso ("mirror"); design thesis: legibility + cognitive sovereignty.
rootProject.name = "Aarso"
include(":app")
include(":core-engine")
include(":sdengine")
include(":hyle-probe")
