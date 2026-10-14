// S-FB4 -- isolated build. The Kotlin / Compose Multiplatform versions are PROPERTIES
// (gradle.properties, overridable with -PkotlinVersion=... -PcomposeVersion=...) because the
// whole point of the spike is to find which pairings work against the repo's Kotlin 2.1.0 pin.
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
    val kotlinVersion: String by settings
    val composeVersion: String by settings
    plugins {
        kotlin("jvm") version kotlinVersion
        id("org.jetbrains.kotlin.plugin.compose") version kotlinVersion
        id("org.jetbrains.compose") version composeVersion
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "S-FB4"
