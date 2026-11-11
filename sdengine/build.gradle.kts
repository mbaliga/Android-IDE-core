// On-device image generation engine: stable-diffusion.cpp (with its OWN ggml)
// built as a separate native library, libaarso_sd.so. Kept in its own module so
// its ggml fork never collides with llama.cpp's ggml in the :app module.
plugins {
    alias(libs.plugins.android.library)
}

// CI-only emulator-smoke ABI: see core-engine/build.gradle.kts and docs/ci/emulator-smoke.md.
val smokeAbi: String? = providers.gradleProperty("fonebrew.smokeAbi").orNull?.takeIf { it.isNotBlank() }

android {
    namespace = "dev.fonebrew.sdengine"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        ndk { abiFilters += (smokeAbi ?: "arm64-v8a") }

        // Same explicit native platform pin as :core-engine — see the note there; AGP
        // otherwise configures CMake at android-22, below several bionic API guards.
        if (smokeAbi == null) {
            externalNativeBuild {
                cmake { arguments += listOf("-DANDROID_PLATFORM=android-31") }
            }
        }
    }

    ndkVersion = "28.2.13676358"
    if (smokeAbi == null) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.31.6"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
