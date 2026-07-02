import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing: gitignored keystore.properties, overridable via environment
// (AARSO_KEYSTORE_FILE/_PASSWORD/_ALIAS/_KEY_PASSWORD). The upload key never
// enters the repo; Play App Signing holds the app key. Builds without either
// stay unsigned so CI/agent environments still assemble.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(prop: String, env: String): String? =
    keystoreProps.getProperty(prop) ?: System.getenv(env)

android {
    // Aarso ("mirror"; handoff §10.1 resolved). Package: dev.aarso.
    namespace = "dev.aarso"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.aarso"
        minSdk = 31          // pragmatic floor; adjustable as system-integration lands
        targetSdk = 36       // recent Android (the target device)
        // +1 per Play upload (docs/play/release-process.md); also bumped for sideload
        // refreshes so a new APK always installs over the previous one.
        versionCode = 17
        versionName = "0.13.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // arm64 is the only ABI the target device (and most modern phones) needs;
        // restricting it keeps the llama.cpp build and APK small.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Native llama.cpp engine (CPU-only first cut). The submodule lives at
    // src/main/cpp/llama.cpp; the JNI shim + CMake build it into libaarso_llama.so.
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    val uploadKeystore = signingValue("storeFile", "AARSO_KEYSTORE_FILE")
    if (uploadKeystore != null) {
        signingConfigs {
            create("release") {
                storeFile = file(uploadKeystore)
                storePassword = signingValue("storePassword", "AARSO_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "AARSO_KEYSTORE_ALIAS")
                keyPassword = signingValue("keyPassword", "AARSO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Deliberately NOT minified: llama_jni.cpp resolves the streaming
            // sink by name (GetMethodID("onToken")) — R8 renaming would kill
            // token streaming silently; sdengine has the same shape. Turning R8
            // on later needs -keep rules for the JNI surfaces plus an on-device
            // regression pass. Size is dominated by the native libs anyway.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (uploadKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Distribution split (owner decision 2026-06-12):
    //  - play: Google Play build. Policy-safe catalog (official instruct GGUFs),
    //    no overlay bubble / screen-capture OCR (the heaviest review surface),
    //    in-app output flagging (Play GenAI policy).
    //  - full: the sideload build (apk-dist) — current catalog and all §7 tiers.
    //    Suffixed appId so both can live on one phone side by side.
    flavorDimensions += "dist"
    productFlavors {
        create("full") {
            dimension = "dist"
            isDefault = true
            applicationIdSuffix = ".full"
        }
        create("play") {
            dimension = "dist"
        }
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
        // BuildConfig.DEBUG gates the echo dev stand-ins (no fake engine in release).
        buildConfig = true
    }

    // Compress the native library inside the APK (extracted at install). Cuts the
    // download size substantially with no effect on runtime behaviour.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            // sshj pulls BouncyCastle, which ships duplicate/irrelevant metadata that
            // R8 packaging otherwise rejects. None affect runtime.
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/versions/**",
                "META-INF/BC*.SF",
                "META-INF/BC*.DSA",
                "META-INF/BC*.RSA",
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "module-info.class",
            )
        }
    }
}

dependencies {
    // Hyle single-sourced via the includeBuild'd submodule (see settings.gradle.kts);
    // Gradle substitutes this coordinate with hyle-design-system's :hyle project.
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
    // Markdown rendering for assistant turns (legibility); pure rendering, no IO.
    implementation(libs.markdown.renderer.m3)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    // SSH/SFTP transport for the remote-exec spine (data/remote). Runtime owner-verified.
    implementation(libs.sshj)
    // On-device OCR (offline, bundled) for the screen-capture content tier (§7) —
    // full flavor only; the play build ships without screen capture.
    "fullImplementation"(libs.mlkit.text)

    // On-device image generation native library (libaarso_sd.so).
    implementation(project(":sdengine"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real org.json for JVM tests (the app uses Android's bundled org.json; the
    // stub in unit tests isn't functional). Lets us round-trip the tree archive.
    testImplementation("org.json:json:20231013")
}
