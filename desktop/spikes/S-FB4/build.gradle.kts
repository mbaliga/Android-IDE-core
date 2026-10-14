// S-FB4: Compose for Desktop draws one Fonebrew room (a simplified copy -- there is NO dependency
// on :core-engine or Hyle) and renders it headlessly. Evidence = (1) the room compiles, (2) an
// offscreen Skia render of it is non-blank / deterministic / carries the palette (JVM test, no X
// server), (3) createDistributable produces a launchable app image.
//
// Versions come from gradle.properties / -P so the SAME build can be re-run on other pairings.

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.foundation)
    implementation(compose.material3)
    // The renderer :core-engine pins (libs.versions.toml `markdownRenderer`). It needs Compose
    // Foundation 1.8's BasicText/TextAutoSize (CLAUDE.md "Compose <-> markdown pin"), which is
    // exactly what a Compose Multiplatform pairing must also satisfy on desktop.
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.35.0")
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
}

compose.desktop {
    application {
        mainClass = "dev.fonebrew.spike.fb4.MainKt"
        nativeDistributions {
            // R11: no application identifier (bundleID / reverse-DNS appId) may exist before a
            // NAMES.md row does, so none is set here; packageName is only the spike's image name.
            packageName = "spike-fb4"
            packageVersion = "1.0.0"
        }
    }
}

tasks.test {
    systemProperty("spike.kotlinVersion", providers.gradleProperty("kotlinVersion").get())
    systemProperty("spike.composeVersion", providers.gradleProperty("composeVersion").get())
    systemProperty("spike.outDir", layout.buildDirectory.dir("renders").get().asFile.path)
    systemProperty("spike.runtimeClasspath", sourceSets.main.get().runtimeClasspath.asPath)
    // CI passes -Pfb4.requireXvfb=true so a missing xvfb-run FAILS the window smoke instead of skipping it.
    systemProperty("spike.requireXvfb", providers.gradleProperty("fb4.requireXvfb").getOrElse("false"))
    jvmArgs("-Djava.awt.headless=true") // the evidence render needs no X server
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
