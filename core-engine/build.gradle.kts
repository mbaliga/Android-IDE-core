import com.github.jk1.license.filter.LicenseBundleNormalizer
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage

// :core-engine — the reusable substrate extracted out of :app (§ de-fork). A
// com.android.application module can't be depended on by anything else, which is what forced
// the closed Studio build to fork :app line-for-line instead of consuming it. This library
// carries everything that used to live directly in :app: domain/data/inference/service/ui,
// the native llama.cpp JNI (libaarso_llama.so), and the full/play dist flavors. :app (this
// repo's own open-core shell) and, eventually, Studio's own thin :app both depend on it.
//
// No applicationId/versionCode/versionName/signing here — those are consumer-module concerns
// (AGP forbids them on a library anyway); each consuming :app declares its own.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.license.report)
    alias(libs.plugins.sqldelight)
}

android {
    // Deliberately NOT "dev.fonebrew" — that namespace (== applicationId) belongs to the thin
    // :app that actually ships. Sharing it here would generate a second dev.fonebrew.R /
    // dev.fonebrew.BuildConfig and collide with the consuming app's own R/BuildConfig classes at
    // merge time. Follows :sdengine's precedent (dev.fonebrew.sdengine — "dev.fonebrew." + module
    // name) with the hyphen in "core-engine" collapsed to an underscore, since Java/Kotlin
    // package segments can't contain hyphens.
    namespace = "dev.fonebrew.core_engine"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        targetSdk = 36

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // arm64 is the only ABI the target device (and most modern phones) needs;
        // restricting it keeps the llama.cpp build and APK small.
        ndk { abiFilters += "arm64-v8a" }

        // Pin the NATIVE platform to this module's own minSdk. Left implicit, AGP
        // configured CMake at `android-22` (-DANDROID_PLATFORM=android-22,
        // --target=aarch64-none-linux-android22) even though minSdk is 31 — and bionic
        // guards the POSIX_MADV_* macros behind `__ANDROID_API__ >= 23`, so llama.cpp's
        // llama-mmap.cpp failed to compile ("use of undeclared identifier
        // 'POSIX_MADV_WILLNEED'"). Stating the level explicitly keeps the native headers
        // on the same contract the Kotlin side already declares.
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_PLATFORM=android-31") }
        }
    }

    // Native llama.cpp engine (CPU-only first cut). The submodule lives at
    // src/main/cpp/llama.cpp; the JNI shim + CMake build it into libaarso_llama.so, which
    // ships inside this module's AAR (jni/<abi>/) for the consuming app to package.
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    // A library never runs R8 itself (only the final application module does), but it can
    // still contribute -keep rules to whichever app consumes it. llama_jni.cpp resolves the
    // streaming sink by name (GetMethodID("onToken")) — those rules travel with the AAR so any
    // consumer that turns minification on later doesn't silently break token streaming.
    buildTypes {
        release {
            consumerProguardFiles("proguard-rules.pro")
        }
    }

    // Distribution split (owner decision 2026-06-12) — unchanged from the old :app. Both the
    // thin :app here and a future Studio :app resolve real full/play variants against this
    // dimension (not missingDimensionStrategy — each is a direct consumer, not a transitive
    // bystander).
    flavorDimensions += "dist"
    productFlavors {
        create("full") {
            dimension = "dist"
            isDefault = true
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

    // Fonebrew handoff-pack contract corpus (WP-1/WP-1L): ../contracts/kotlin/*.kt are the
    // neutral, kotlinc-target-only contract type declarations (ContractEnvelope, ErrorEnvelope,
    // the loop object model, etc. — see docs/ratified/ + schemas/ alongside them at the repo
    // root). They live outside any Gradle module by design (04_ARCHITECTURE_CONTRACTS.md §0's
    // target layout puts them at the repo root, reusable across the constellation, not owned by
    // one app module) but need a real compiler to verify against, which only a Gradle module
    // provides in this repo (no standalone kotlinc in the build environment). Adding the
    // directory as an extra main source root — rather than copying the files into
    // core-engine's own package tree — keeps the repo-root location as the single source of
    // truth; WP-2+ implementation code in domain/contracts/ imports these types directly.
    sourceSets {
        getByName("main") {
            kotlin.srcDir("../contracts/kotlin")
        }
    }

    buildFeatures {
        compose = true
        // BuildConfig.DEBUG gates the echo dev stand-ins (no fake engine in release). This is
        // now dev.fonebrew.core_engine.BuildConfig, generated by THIS module — every reference
        // was re-pointed at it (see AppContainer.kt / SettingsRoom.kt).
        buildConfig = true
    }

    // NOTE (verified by a real :app:assembleFullDebug run): a library's own `packaging{}`
    // block only governs its own AAR artifact, not how a consuming application module later
    // merges every dependency's resources — that enforcement has to be declared on the final
    // application module (see app/build.gradle.kts, which carries the real, effective copy of
    // this same block). Left here too so the AAR's own packaging is still sane in isolation,
    // but do not rely on this alone.
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

// Search index substrate (FONEBREW_SEARCH_SPEC.md D1) — SQLDelight over bundled SQLite, for
// FTS5 (Room has no @Fts5). Coexists with Room (the message-tree store); separate database.
sqldelight {
    databases {
        create("SearchDatabase") {
            packageName.set("dev.fonebrew.data.search")
        }
    }
}

dependencies {
    // Hyle single-sourced via the includeBuild'd submodule (see settings.gradle.kts);
    // Gradle substitutes this coordinate with hyle-design-system's :hyle project.
    implementation("dev.aarso:hyle:0.2.0")
    // Shared crash-recovery utility — single-sourced from mbaliga/Shared-Libraries-asoc (its
    // own submodule, ./shared-libraries; see settings.gradle.kts for why this coordinate needs
    // an explicit dependencySubstitution now that hyle-design-system also carries a tombstone
    // at the same `dev.aarso:crash-recovery` coordinate). 1.5.0 folds forward the fix that
    // makes "Continue" actually relaunch the app, plus the non-destructive quarantine/salvage
    // reset (see that repo's crash-recovery/build.gradle.kts changelog comment).
    implementation("dev.aarso:crash-recovery:1.5.0")
    // Shared Regular/asoc interaction-mode choice (2026-09-15 ruling) — composited exactly
    // like :crash-recovery above, same shared-libraries submodule, own coordinate/project.
    // InteractionMode enum + InteractionModeStore/PrefsInteractionModeStore + the pure
    // ModeDefaults policy; deliberately no UI (the picker is Hyle's HyleModePicker).
    implementation("dev.aarso:interaction-mode:0.1.0")
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

    // Search FTS5 index (separate database from Room's message-tree store — see D1).
    implementation(libs.sqldelight.runtime)
    implementation(libs.sqldelight.coroutines.extensions)
    implementation(libs.sqldelight.androidx.driver)
    implementation(libs.androidx.sqlite.bundled)

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
    // JVM unit tests run on the host JVM, but this is a plain Android module (not Kotlin
    // Multiplatform), so dependency resolution for `implementation(libs.androidx.sqlite.bundled)`
    // uses Android's own consumer attributes and pulls the Android-ABI native binary — useless
    // for the host JVM process the test actually runs in (UnsatisfiedLinkError). Force the real
    // JVM-targeted artifact onto the test classpath explicitly. See SearchDatabaseFactoryTest.
    testImplementation("androidx.sqlite:sqlite-bundled-jvm:2.7.0")
}

// §1.8 license gate — moved here verbatim from :app, since this module now carries the actual
// runtime dependency graph. See the (unchanged) reasoning comment this was copied from.
//
// AUDIT FIX (2026-09-15, lane LC-license): Studio's own hygiene wave found that its :app license
// scan was silently dropping every first-party project() dependency because lenient resolution
// swallows an ambiguous-variant failure instead of erroring (see, read-only reference — NOT this
// repo — android-ide-studio/app/build.gradle.kts's own long comment on the same block). Checked
// empirically here rather than assumed inherited: before this fix,
// `:core-engine:generateLicenseReport` produced a 162-entry report with ZERO `dev.aarso:*` /
// `dev.fonebrew:*` entries, and `:core-engine:dependencyInsight --dependency
// <sdengine|hyle|crash-recovery|interaction-mode> --configuration fullLicenseScan` showed the
// identical shape for all four of this module's project()/includeBuild edges: "FAILED — cannot
// choose between … debugRuntimeElements / releaseRuntimeElements … distinguishing attribute
// BuildTypeAttr" — i.e. the bug Studio found is real here too, just with a narrower trigger: this
// scan config only ever lacked `BuildTypeAttr`, not the flavor attribute Studio also had to pin.
// (Unlike Studio's :app, which pulls in project(":core-engine") — a module that itself declares
// the "dist" flavor dimension — none of core-engine's own edges (:sdengine, and the
// hyle-design-system / shared-libraries includeBuild substitutions for :hyle / :crash-recovery /
// :interaction-mode) declare a flavor dimension of their own, so their runtime variants only ever
// differ by build type; there is no `ProductFlavorAttr` ambiguity to pin here, and adding one that
// no producer variant declares would be scope creep past what the empirical failure showed.) Fix:
// pin BuildTypeAttr to "debug", matching the variant these gates actually compile/test
// (testFullDebugUnitTest/testPlayDebugUnitTest); this module never minifies, so the dependency
// SET doesn't differ by build type — only which one a report attaches to.
//
// Deliberately NOT pinning Kotlin's `org.jetbrains.kotlin.platform.type` attribute either, for the
// same reason Studio's comment gives: several androidx.compose.* artifacts resolve to a cosmetic
// extra "-jvmstubs" coordinate (own Apache-2.0 POM, no bundled LICENSE.txt) alongside their real
// Android one when that attribute isn't requested — pinning it to "androidJvm" would dedupe those,
// but it also makes plain JVM-only Kotlin libraries (kotlin-stdlib itself,
// kotlinx-coroutines-core/-android, okio, …) incompatible and silently drops them from the scan
// instead, since this ad hoc configuration has no access to the Kotlin Gradle Plugin's own
// jvm<->androidJvm compatibility rule. A coverage loss on real, shipped dependencies is strictly
// worse than a same-license cosmetic duplicate, so the duplicate stays (pre-existing, unchanged by
// this fix).
//
// Verified by regenerating core-engine/build/reports/dependency-license/
// project-licenses-for-check-license-task.json (via `:core-engine:checkLicense --rerun`, which is
// what actually drives that file — plain `generateLicenseReport` alone does not): raw dependency
// count rose 162 -> 165, surfacing three real, previously-silently-dropped first-party deps —
// dev.aarso:hyle:0.2.1, dev.aarso:crash-recovery:1.5.0, dev.aarso:interaction-mode:0.1.0 — which
// then correctly FAILED checkLicense (empty moduleLicense: composite-substituted project modules
// carry no POM for the plugin to read a license off of), so they're allow-listed by name below,
// same as this file already does for other first-party/no-POM entries.
//
// project(":sdengine") is NOT among the three and does not newly appear in the report even after
// this fix, but that is a *different*, pre-existing, non-bug property, checked separately:
// `dependencyInsight` above confirms sdengine's own variant selection resolves cleanly post-fix
// (no FAILED, no ambiguity) — so this is not the silent-drop bug recurring. Rather, :sdengine is a
// plain `include(":sdengine")` subproject of *this same build* (not a composite/includeBuild
// substitution like the three above), so Gradle resolves it as a bare ProjectComponentIdentifier
// with no synthesized module/GAV coordinates for the license-report plugin to key a report entry
// on — there is no separate license to vet because it isn't a separate published artifact, it's
// this very repo. (Contrast Studio's own :app scan, where :sdengine is reached *transitively*,
// crossing the "core" includeBuild boundary from Studio's side — that boundary is what gives it
// synthesized `Fonebrew:sdengine` coordinates in Studio's report; no such boundary exists between
// core-engine and its own sibling :sdengine.) Named here, not silently assumed, per the honest-
// scope rule: if :sdengine ever gains a real external dependency of its own, that dependency would
// need scanning directly (:sdengine has no licenseScan of its own today) — tracked as a follow-up,
// not fixed here, since it's a scope expansion (a new scan target) rather than a repair of this
// block's existing one.
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
