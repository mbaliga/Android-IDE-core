plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Userland probe: zero runtime library dependencies (only the Kotlin toolchain stdlib that the
// Kotlin plugin adds; no AndroidX, no network library). junit is testImplementation only and is
// never packaged. Same SDK/NDK pins as core (CLAUDE.md "Building").
android {
    namespace = "dev.fonebrew.probe"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.fonebrew.probe"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            // Same explicit platform pin as core: left implicit AGP configures android-22.
            cmake { arguments += listOf("-DANDROID_PLATFORM=android-31") }
        }
        buildConfigField("boolean", "STEP_TWO_ENABLED", "false")
    }
    buildFeatures { buildConfig = true }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    packaging {
        // Check (ii) needs the test executable to exist as a real file in nativeLibraryDir.
        jniLibs { useLegacyPackaging = true }
    }
    buildTypes {
        // The DEFAULT build never fetches anything. Step two (docs/design/userland-proot.md
        // section 9) exists only in this separate build type, which the owner must build by name:
        //   ./gradlew :app:assembleStepTwo
        // It flips the flag, adds the INTERNET permission and enables the step-two activity
        // (src/stepTwo/AndroidManifest.xml). It is gated on the owner's legal ruling.
        create("stepTwo") {
            initWith(getByName("debug"))
            buildConfigField("boolean", "STEP_TWO_ENABLED", "true")
            applicationIdSuffix = ".steptwo"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation(libs.junit)
}
