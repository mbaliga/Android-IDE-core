// desktop/ is its OWN Gradle build (porting plan docs/PORTING_PLAN.md section 6.1, step LX-1).
//
// It is deliberately NOT included from the repo-root settings.gradle.kts and the root build never
// sees it: running ./gradlew from the repo root is unchanged, and running ./desktop/gradlew from
// here needs only a JDK -- no Android Gradle plugin, no Android SDK, no ANDROID_HOME, no git
// submodules (hyle-design-system / shared-libraries / the native engines are never touched).
//
// Pins mirror the root build's do-not-regress pins (see gradle/libs.versions.toml in this
// directory); scripts/check_pins.py proves they have not drifted.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// Same toolchain-resolver the root build uses: auto-provisions JDK 17 on a machine that lacks one.
// (CI already has Temurin 17 from setup-java, so this is a no-op there.)
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "fonebrew-desktop"
include(":core-host")
