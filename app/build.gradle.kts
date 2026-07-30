import java.util.Properties

// :app — the thin, shipping application shell (§ de-fork). All the reusable substrate now
// lives in :core-engine (a com.android.library, so a future Studio :app can depend on it too);
// this module carries only what's genuinely application-scoped: applicationId, versionCode/
// versionName, signing, and the dist-flavor declarations needed to assemble real full/play
// variants. No installStudio* calls happen anywhere in this open-core tree, so a bare build of
// this module shows core's own locked placeholders — exactly the open-core shape.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
    // Aarso ("mirror"; handoff §10.1 resolved). Package: dev.aarso. Matches the actual
    // package the moved :core-engine Kotlin sources still declare (dev.aarso.*, unchanged by
    // the extraction) — a relative android:name in this module's manifest (e.g. ".AarsoApp")
    // resolves correctly against it.
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

        // arm64 is the only ABI the target device (and most modern phones) needs;
        // restricting it keeps the packaged native libs (from :core-engine's AAR) small.
        ndk { abiFilters += "arm64-v8a" }
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
            // Deliberately NOT minified: llama_jni.cpp (inside :core-engine's AAR) resolves
            // the streaming sink by name (GetMethodID("onToken")) — R8 renaming would kill
            // token streaming silently; sdengine has the same shape. This flag lives here
            // (not on the library) because only the final application module actually runs
            // R8 — a library's own isMinifyEnabled is a no-op; :core-engine instead ships a
            // consumerProguardFiles rule carrying the same intent forward.
            isMinifyEnabled = false
            if (uploadKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Distribution split (owner decision 2026-06-12) — must match :core-engine's own
    // productFlavors exactly (same dimension name, same flavor names). This is a real,
    // direct consumer of both variants (not a missingDimensionStrategy bystander), so it
    // declares the dimension itself rather than deferring to one.
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

    // Packaging is final-APK-assembly-time behaviour, so it belongs on the application module
    // even though the actual jniLibs/META-INF content originates from :core-engine's AAR (and
    // sdengine's) — a library's own `packaging{}` block only governs that library's own AAR
    // artifact, not how the consuming app later merges everything together. Moved here
    // verbatim from the old monolithic :app; dropping this (discovered via a real
    // :app:assembleFullDebug run, not assumed) reproduces a mergeFullDebugJavaResource
    // failure on duplicate META-INF/versions/9/OSGI-INF/MANIFEST.MF across the sshj→
    // BouncyCastle jars.
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    implementation(project(":core-engine"))
}
