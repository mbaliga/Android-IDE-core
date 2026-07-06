// :core-engine — the extractable, reusable engine library (§5 de-fork, stage 1).
//
// Why this module exists: Aarso/Workbench and the closed "Studio" build were a
// single application module forked line-for-line. An application module can't be
// depended on, so Studio had to duplicate ~everything to add its paid surfaces.
// The fix is to sink the shared engine into a `com.android.library` that BOTH the
// thin `:app` here AND a future thin Studio `:app` (via git submodule + includeBuild)
// depend on — so the fork collapses to a handful of Studio-only files.
//
// This is being grown one CI-verifiable slice at a time (the container has no
// Android toolchain, so each slice is proven by the JVM unit-test gate, not a local
// build). SLICE 1 = the pure-Kotlin `domain/` layer (tree, council, bpmn, loop, diff,
// device, git, ide, remote, disclosure, instruments, mirror) + its 89 test files.
// Later slices move data/inference/service/ui/native + the `dist` flavors here and
// leave `:app` a thin application shell. Until then `:app` still holds everything else.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.aarso.engine"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    // domain/ uses only `suspend` (kotlin stdlib) today, org.json (from android.jar),
    // and org.w3c.dom / javax.xml.parsers (from the JDK/android.jar). coroutines-core is
    // declared as `api` so consumers (`:app`, later Studio) see the same coroutine types
    // on their compile classpath, matching how `:app` already depends on it.
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real org.json for JVM unit tests — the android.jar stub isn't functional, so the
    // BPMN/tree round-trip tests need the genuine implementation on the test classpath.
    testImplementation("org.json:json:20231013")
}
