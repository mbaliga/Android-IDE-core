import java.util.Properties

// Thin application shell (§5 de-fork). All Kotlin/UI/resources/manifest-components live
// in :core-engine (a com.android.library); this module only carries what an application
// must: the applicationId + signing, the `dist` flavors, the native llama.cpp build, and
// the dependency on :core-engine. A future Studio :app is the same shell over the same
// (submodule'd) :core-engine — that's what collapses the fork.
//
// namespace is dev.aarso.app (must differ from :core-engine's dev.aarso); applicationId
// stays dev.aarso so installed identity is unchanged.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing: gitignored keystore.properties, overridable via environment. The upload
// key never enters the repo; builds without either stay unsigned so CI/agents still assemble.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(prop: String, env: String): String? =
    keystoreProps.getProperty(prop) ?: System.getenv(env)

android {
    namespace = "dev.aarso.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.aarso"
        minSdk = 31
        targetSdk = 36
        versionCode = 17
        versionName = "0.13.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // arm64 is the only ABI the target device needs; keeps the llama.cpp build + APK small.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Native llama.cpp engine. The submodule lives at src/main/cpp/llama.cpp; the JNI shim +
    // CMake build it into libaarso_llama.so. The Kotlin `external` bindings are in :core-engine
    // (package dev.aarso.inference), resolved by FQN at runtime — package unchanged by the move.
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
            // Deliberately NOT minified: llama_jni.cpp resolves the streaming sink by name
            // (GetMethodID("onToken")) — R8 renaming would kill token streaming silently.
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

    // Distribution split — must match :core-engine's `dist` dimension so AGP flavor-matches
    // app→library. appId suffix + isDefault are application-only, so they live here.
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

    // Compress the native lib inside the APK (extracted at install); exclude the duplicate
    // BouncyCastle/sshj metadata (sshj comes in transitively via :core-engine) that R8
    // packaging otherwise rejects. None affect runtime.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
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
    // Everything (code, resources, manifest components, flavors) lives in :core-engine;
    // its transitive deps become this APK's runtime classpath.
    implementation(project(":core-engine"))
}
