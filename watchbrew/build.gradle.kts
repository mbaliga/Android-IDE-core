import com.github.jk1.license.filter.LicenseBundleNormalizer
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import java.util.Properties

// :watchbrew — the Wear OS app (docs/design/watchbrew.md sections 2 and 3). A thin REMOTE SURFACE for
// Fonebrew on the wrist: it asks the phone, shows what the phone says, and never runs a model,
// holds a key, or talks to a network.
//
// DEPENDENCY LAW (machine-enforced by WatchbrewModuleGuardTest, not just stated here):
//   - depends ONLY on :watch-bridge (the wire contract) + androidx.wear / Wear Compose +
//     play-services-wearable (the device-local Data Layer link) + activity-compose.
//   - NEVER on :core-engine (~130 MB of native engine libs + the whole substrate) and NEVER on the
//     phone Hyle (it is phone Compose). Hyle's COLOUR TOKENS are copied as constants instead — see
//     core/WatchPalette.kt for the single-source follow-up.
//   - NO network: no INTERNET permission, no HTTP client, no cloud call. The Data Layer is the
//     pair-local transport to the phone; it is not telemetry.
//
// Honest scope: everything in this module compiles and its pure logic is JVM-tested; nothing here
// has run on a watch, an emulator, or against a paired phone (the build container has none).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.license.report)
}

// Release signing — the SAME gitignored keystore.properties / FONEBREW_KEYSTORE_* environment
// contract as :app. This is load-bearing, not copied by habit: the Wearable Data Layer only
// delivers between a phone app and a watch app that share applicationId AND signing key, so the
// phone and the watch build you pair MUST both come from the same keystore (see README.md).
// Debug builds on one machine share the machine's default debug keystore, which satisfies the same
// rule for dev sideloads. Builds with neither stay unsigned so CI/agent environments still assemble.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(prop: String, env: String): String? =
    keystoreProps.getProperty(prop) ?: System.getenv(env)

android {
    // The SOURCE package is dev.fonebrew.watch (post-rename register); the R/BuildConfig namespace
    // stays dev.aarso.watch for continuity with :app's dev.aarso namespace (the Sprint-R rename is
    // deferred; see the root build-rules doc). The manifest therefore references classes by fully-qualified name.
    namespace = "dev.aarso.watch"
    compileSdk = 36

    defaultConfig {
        // MUST equal the phone app's applicationId per flavor (full -> dev.aarso.full via the
        // ".full" suffix below, play -> dev.aarso): the Data Layer routes by package name, so a
        // mismatch silently breaks every message in both directions. WatchbrewModuleGuardTest
        // compares this block against app/build.gradle.kts.
        applicationId = "dev.aarso"
        minSdk = 30          // Wear OS 3 floor
        targetSdk = 36
        // The watch version spine is its own fresh, independent monotonic line (design section 2).
        versionCode = 1
        versionName = "0.1.0"
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
            // Not minified in v1: R8 on a Wear Compose + Data Layer app is exactly the kind of
            // change that can only be proven on a device, and none is available here. The APK is
            // already small (no native libs, no large assets — README.md "APK budget"); shrinking is
            // a named follow-up once the owner has verified an unshrunk build.
            isMinifyEnabled = false
            if (uploadKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // The same `dist` pair as the phone (design section 2, "Consequence of matching appIds").
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

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
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
    // The wire contract — the ONLY project dependency this module may have.
    implementation(project(":watch-bridge"))

    // The Wearable Data Layer: the device-local pair transport (Bluetooth/Wi-Fi between the two
    // paired devices). Not telemetry — nothing leaves the pair. One catalog pin shared with the
    // phone-side relay (:core-engine), so both ends of the pair run the same Data Layer client
    // release. License: the GMS scoped rule in config/allowed-licenses.json.
    implementation(libs.play.services.wearable)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)

    // Compose runtime/UI at the same generation as the phone (the BoM pin is untouched). Wear Compose
    // itself is not BoM-governed and is pinned exactly in the catalog.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.navigation)
    implementation(libs.wear.input)

    // Tile + complication.
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.wear.watchface.complications.data.source.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // The watch-bridge codec uses org.json, which is a PLATFORM class on a watch (so the bridge
    // declares it compileOnly). A JVM unit test has only AGP's non-functional android.jar stub, so a
    // real org.json goes on the TEST classpath — the same arrangement :core-engine uses.
    testImplementation("org.json:json:20231013")
}

// License gate for the watch APK's own dependency graph (same allowlist file as :core-engine; the
// Wear/GMS artifacts are admitted there already — nothing here edits it). Same scan-configuration
// idiom as :core-engine's block (see its long comment for why BuildTypeAttr is pinned): the scan
// configs extend `implementation` so they see the real runtime graph, and the :watch-bridge project
// edge is a same-build subproject (no synthesized module coordinates, so it never needs an entry).
val licenseScanAttrs: (org.gradle.api.attributes.AttributeContainer) -> Unit = { attrs ->
    attrs.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
    attrs.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
    attrs.attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
    attrs.attribute(Attribute.of("artifactType", String::class.java), "jar")
    attrs.attribute(
        com.android.build.api.attributes.BuildTypeAttr.ATTRIBUTE,
        objects.named(com.android.build.api.attributes.BuildTypeAttr::class.java, "debug"),
    )
}
listOf("full", "play").forEach { flavor ->
    configurations.create("${flavor}LicenseScan") {
        isCanBeResolved = true
        isCanBeConsumed = false
        extendsFrom(configurations.getByName("implementation"), configurations.getByName("${flavor}Implementation"))
        attributes { licenseScanAttrs(this) }
    }
}
licenseReport {
    configurations = arrayOf("fullLicenseScan", "playLicenseScan")
    filters = arrayOf(LicenseBundleNormalizer())
    allowedLicensesFile = rootProject.file("config/allowed-licenses.json")
}
