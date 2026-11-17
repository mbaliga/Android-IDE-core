// apple/ is its OWN Gradle build (porting plan docs/PORTING_PLAN.md section 6.3, IOS-1), mirroring
// desktop/: the repo-root settings.gradle.kts never includes it, so the Android gate is untouched.
// It needs only a JDK to build the JVM target; the iOS targets need macOS + Xcode (CI lane
// .github/workflows/ios.yml) and are skipped automatically on Linux.
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

rootProject.name = "fonebrew-apple"
include(":shared")
