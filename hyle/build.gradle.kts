// Hyle — the material design system (docs/design/material-language.md), staged as
// its own module. The *semantic* (local vs from-elsewhere) lives in the app
// (dev.aarso.domain.material); this module owns the *render side* — tokens + the
// contract the renderer obeys. The Compose Modifiers and AGSL shaders land next and
// are owner-verified on device, so this first cut is deliberately pure data
// (JVM-tested). Kept a separate module so it can graduate to its own repo once the
// API stabilises and it first renders well on the device.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

// Coordinate for the publishable AAR. When Hyle graduates to its own repo
// (docs/handoff/hyle-extraction.md) this build file moves with it unchanged; until then
// `./gradlew :hyle:publishToMavenLocal` proves the module stands alone as `dev.aarso:hyle`.
val hyleGroup = "dev.aarso"
val hyleArtifact = "hyle"
val hyleVersion = "0.1.0"

android {
    namespace = "dev.aarso.hyle"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Required so maven-publish has a single, named variant to publish.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    jvmToolchain(17)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = hyleGroup
                artifactId = hyleArtifact
                version = hyleVersion
            }
        }
    }
}

dependencies {
    testImplementation(libs.junit)
}
