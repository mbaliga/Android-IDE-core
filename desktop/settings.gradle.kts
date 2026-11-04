// Desktop host lane (porting plan LX-1): a separate Gradle build, JVM only, no AGP and no
// Android SDK. It compiles the pure-JVM sources that :core-engine already owns, by srcDir,
// so they stay in one place. The root build is untouched; CI checks that with
// `git diff --exit-code` over the root build files (desktop-host-core.yml).
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "aarso-desktop"
include(":core-host")
