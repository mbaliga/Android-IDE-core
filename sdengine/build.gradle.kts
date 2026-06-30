// On-device image generation engine: stable-diffusion.cpp (with its OWN ggml)
// built as a separate native library, libaarso_sd.so. Kept in its own module so
// its ggml fork never collides with llama.cpp's ggml in the :app module.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.aarso.sdengine"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        ndk { abiFilters += "arm64-v8a" }
    }

    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
