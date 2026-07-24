import com.github.jk1.license.filter.LicenseBundleNormalizer
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.license.report)
}

// Release signing: gitignored keystore.properties, overridable via environment
// (FONEBREW_KEYSTORE_FILE/_PASSWORD/_ALIAS/_KEY_PASSWORD). The upload key never
// enters the repo; Play App Signing holds the app key. Builds without either
// stay unsigned so CI/agent environments still assemble.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(prop: String, env: String): String? =
    keystoreProps.getProperty(prop) ?: System.getenv(env)

android {
    // Fonebrew (handoff §10.1 resolved). Package stays dev.aarso — deferred rename;
    // "Aarso"/"mirror" now names only the self-reflection lens (domain/mirror/).
    namespace = "dev.aarso"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.aarso"
        minSdk = 31          // pragmatic floor; adjustable as system-integration lands
        targetSdk = 36       // recent Android (the target device)
        // Fonebrew launch build (2026-07-16): resets the version spine for this brand's first
        // ship. +1 per Play upload (docs/play/release-process.md); also bumped for sideload
        // refreshes so a new APK always installs over the previous one.
        versionCode = 1
        versionName = "0.1.0"

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

    val uploadKeystore = signingValue("storeFile", "FONEBREW_KEYSTORE_FILE")
    if (uploadKeystore != null) {
        signingConfigs {
            create("release") {
                storeFile = file(uploadKeystore)
                storePassword = signingValue("storePassword", "FONEBREW_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "FONEBREW_KEYSTORE_ALIAS")
                keyPassword = signingValue("keyPassword", "FONEBREW_KEY_PASSWORD")
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
    // Shared crash-recovery utility (same submodule, separate coordinate — deliberately
    // independent of :hyle so non-Hyle apps can also depend on it; see that repo's README).
    implementation("dev.aarso:crash-recovery:1.0.0")
    // On-device Gemini Nano via Android's AICore system service — an experimental preview SDK
    // (0.0.1-exp01) that only runs on a narrow device set (Pixel 8+/9 class, a Galaxy S24
    // subset); AiCoreEngine/AiCoreAvailability gate and fail closed everywhere else.
    implementation("com.google.ai.edge.aicore:aicore:0.0.1-exp01")
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

// §1.8 license gate: scans what actually ships and fails `checkLicense` on anything off
// `config/allowed-licenses.json`. Test-only deps like JUnit are a different configuration
// and never scanned. Every borrow is still verify-at-build (§1.8) — this only catches
// licenses the repo hasn't already had a human look at.
//
// Two purpose-built resolvable configurations, NOT the real fullDebug/playDebug/fullRelease/
// playReleaseRuntimeClasspath: asking the license-report plugin to resolve those directly
// (it uses the legacy `Configuration.resolvedConfiguration` API, bypassing AGP's own task
// graph) hits a composite-build variant-selection ambiguity — :hyle-design-system:hyle
// (consumed via `includeBuild("hyle-design-system")`, substituted for `dev.aarso:hyle`)
// publishes many secondary artifactType-tagged variants of its runtime configuration
// (android-classes-jar, android-jni, android-res, plain jar, ...), and AGP's own
// disambiguation rule for picking among them apparently doesn't cross the included-build
// boundary — Gradle refuses to guess. AGP's real task graph resolves the exact same
// dependency fine (proved by :app:testFullDebugUnitTest/:testPlayDebugUnitTest passing),
// it just requests a specific artifactType the plugin's bare API call doesn't. Working
// around it: mirror the real classpath's declared dependencies (extendsFrom the same
// implementation buckets AGP's own RuntimeClasspath configurations extend) on a fresh
// configuration that requests plain Usage=java-runtime/Category=library/artifactType=jar —
// enough to disambiguate without needing AGP's flavor/build-type attributes at all, since
// this is only for reading licenses off the resolved graph, not for compiling/packaging.
val licenseScanAttrs: (org.gradle.api.attributes.AttributeContainer) -> Unit = { attrs ->
    attrs.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
    attrs.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
    attrs.attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
    attrs.attribute(Attribute.of("artifactType", String::class.java), "jar")
}
listOf("full", "play").forEach { flavor ->
    configurations.create("${flavor}LicenseScan") {
        isCanBeResolved = true
        isCanBeConsumed = false
        extendsFrom(configurations.getByName("implementation"), configurations.getByName("${flavor}Implementation"))
        attributes { licenseScanAttrs(this) }
    }
}
//
// config/allowed-licenses.json carries two narrow name-scoped overrides for
// com.google.android.gms / com.google.mlkit / com.google.android.odml (the on-device
// OCR chain behind `libs.mlkit.text`, `full` flavor only — see the dependency below).
// Those report as "Android Software Development Kit License" / "ML Kit Terms of
// Service", Google's own SDK-distribution terms rather than an OSS license string —
// outside the §1.8 OSS allowlist by nature, not because they're a copyleft/attribution
// risk (what §1.8 actually guards against). Pre-existing dependency, verified
// 2026-07-11, scoped by name regex so the override can't silently cover anything else.
licenseReport {
    configurations = arrayOf("fullLicenseScan", "playLicenseScan")
    filters = arrayOf(LicenseBundleNormalizer())
    allowedLicensesFile = rootProject.file("config/allowed-licenses.json")
}
