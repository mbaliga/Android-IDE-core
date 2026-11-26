// STANDALONE project: deliberately NOT included from the repo-root settings.gradle.kts, so the
// core gate never configures, builds or tests it. Build with its own wrapper:
//   cd tools/userland-probe && ./gradlew :app:assembleDebug
// It reuses core's pinned versions (AGP, Kotlin, junit) by reading the root version catalog.
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
    versionCatalogs {
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}
rootProject.name = "userland-probe"
include(":app")
