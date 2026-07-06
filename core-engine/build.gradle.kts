// :core-engine — the extractable, reusable engine library (§5 de-fork).
//
// This now holds ~all of the app: the domain/data/inference/service/ui/di/security/
// embedding layers, the resources, the manifest components, and the full/play/debug
// source sets. Both the thin :app here AND a future thin Studio :app (via git submodule
// + includeBuild) depend on it, so the Studio fork collapses to a few Studio-only files.
//
// Native (llama.cpp JNI + CMake) stays in :app — the Kotlin here only declares the
// `external` bindings (package dev.aarso.inference); the .so is built and packaged by
// whichever application module consumes this library.
//
// namespace = dev.aarso (NOT dev.aarso.engine) on purpose: the moved code references
// dev.aarso.R and dev.aarso.BuildConfig, which are generated from the module namespace.
// Keeping it dev.aarso means those resolve with zero source changes.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.aarso"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // A library BuildConfig has DEBUG but not VERSION_NAME; SettingsRoom reads
        // dev.aarso.BuildConfig.VERSION_NAME, so surface it here. Keep in lockstep
        // with :app's versionName.
        buildConfigField("String", "VERSION_NAME", "\"0.13.0\"")
    }

    // Distribution split (mirrors :app so AGP flavor-matches app→core-engine):
    //  - full: sideload build; all §7 tiers (overlay, screen-capture OCR).
    //  - play: policy-safe; no overlay/screen-capture. Each flavor's source set
    //    (src/full, src/play) provides its own ApkInstaller/ModelCatalog/etc.
    flavorDimensions += "dist"
    productFlavors {
        create("full") { dimension = "dist" }
        create("play") { dimension = "dist" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
    buildFeatures {
        compose = true
        // BuildConfig.DEBUG gates the echo dev stand-ins (di/AppContainer).
        buildConfig = true
    }
}

dependencies {
    // Hyle single-sourced via the includeBuild'd submodule (settings.gradle.kts).
    implementation("dev.aarso:hyle:0.2.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.markdown.renderer.m3)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.sshj)
    // On-device OCR (offline, bundled) for the screen-capture content tier — full flavor only.
    "fullImplementation"(libs.mlkit.text)

    // On-device image generation native library (libaarso_sd.so).
    implementation(project(":sdengine"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real org.json for JVM tests (Android's bundled org.json stub isn't functional).
    testImplementation("org.json:json:20231013")
}
