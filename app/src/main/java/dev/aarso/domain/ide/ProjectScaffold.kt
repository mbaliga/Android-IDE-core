package dev.aarso.domain.ide

/**
 * Agentic-IDE pillar: turn an idea into a **minimal, CI-buildable Android project**
 * that the user can commit to their own Git host, where *their* CI builds the APK —
 * so the phone never needs the NDK/SDK to ship a user app (docs/design/coding-assistant.md,
 * app-distribution.md). The scaffold is the seed of the "conceive → build → test →
 * launch from the phone" loop: its files flow through `GitContentsApi.putFile` to the
 * host, a generated GitHub/Gitea Actions workflow produces an APK, and `BuildsApi`
 * lists it back for in-app install.
 *
 * Pure domain (no Android, no network, no IO): [generate] returns the file set as
 * data, so it is fully JVM-tested. It is a *template* — the user's CI is the ground
 * truth that it builds; this engine only guarantees a coherent, conventional layout.
 */
object ProjectScaffold {

    /** A single generated file: repo-relative path + its text content. */
    data class File(val path: String, val content: String)

    /** What the user (or a Loop) specifies to seed a project. */
    data class AppSpec(
        val appName: String,
        val packageId: String,
        val minSdk: Int = 26,
        val compileSdk: Int = 36,
        /** One-line description of the first screen; shown on the generated Compose home. */
        val idea: String = "",
    )

    /** Package-id rule: dotted, lowercase-friendly segments, each a valid identifier. */
    fun isValidPackageId(id: String): Boolean {
        val segs = id.split('.')
        if (segs.size < 2) return false
        return segs.all { it.isNotEmpty() && it.first().isJavaIdentifierStart() && it.all(Char::isJavaIdentifierPart) }
    }

    /** Reasons [spec] cannot be scaffolded (empty = good to go). */
    fun validate(spec: AppSpec): List<String> = buildList {
        if (spec.appName.isBlank()) add("App name is required.")
        if (!isValidPackageId(spec.packageId)) add("Package id must be dotted identifiers, e.g. com.example.app.")
        if (spec.minSdk < 21) add("minSdk must be 21 or higher.")
        if (spec.compileSdk < spec.minSdk) add("compileSdk cannot be lower than minSdk.")
    }

    /**
     * Generate the project file set for [spec]. Throws [IllegalArgumentException] if the
     * spec is invalid (call [validate] first to surface reasons in the UI).
     */
    fun generate(spec: AppSpec): List<File> {
        val problems = validate(spec)
        require(problems.isEmpty()) { "invalid AppSpec: ${problems.joinToString("; ")}" }

        val pkgPath = spec.packageId.replace('.', '/')
        val idea = spec.idea.ifBlank { "A new app, conceived on Aarso." }

        return listOf(
            File("settings.gradle.kts", settingsGradle(spec.appName)),
            File("build.gradle.kts", rootBuildGradle()),
            File("gradle.properties", GRADLE_PROPERTIES),
            File(".gitignore", GITIGNORE),
            File("README.md", readme(spec, idea)),
            File(".github/workflows/build.yml", ciWorkflow()),
            File("app/build.gradle.kts", appBuildGradle(spec)),
            File("app/src/main/AndroidManifest.xml", manifest()),
            File("app/src/main/java/$pkgPath/MainActivity.kt", mainActivity(spec.packageId, spec.appName, idea)),
            File("app/src/main/res/values/strings.xml", strings(spec.appName)),
        )
    }

    // ---- templates (kept conventional + version-pinned so a fresh checkout builds) ----

    private fun settingsGradle(appName: String) = """
        pluginManagement {
            repositories { google(); mavenCentral(); gradlePluginPortal() }
        }
        dependencyResolutionManagement {
            repositories { google(); mavenCentral() }
        }
        rootProject.name = "$appName"
        include(":app")
    """.trimIndent() + "\n"

    private fun rootBuildGradle() = """
        plugins {
            id("com.android.application") version "8.7.3" apply false
            id("org.jetbrains.kotlin.android") version "2.1.0" apply false
            id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
        }
    """.trimIndent() + "\n"

    private fun appBuildGradle(spec: AppSpec) = """
        plugins {
            id("com.android.application")
            id("org.jetbrains.kotlin.android")
            id("org.jetbrains.kotlin.plugin.compose")
        }

        android {
            namespace = "${spec.packageId}"
            compileSdk = ${spec.compileSdk}

            defaultConfig {
                applicationId = "${spec.packageId}"
                minSdk = ${spec.minSdk}
                targetSdk = ${spec.compileSdk}
                versionCode = 1
                versionName = "0.1.0"
            }

            buildTypes {
                release { isMinifyEnabled = false }
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            kotlin { jvmToolchain(17) }
            buildFeatures { compose = true }
        }

        dependencies {
            val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
            implementation(composeBom)
            implementation("androidx.activity:activity-compose:1.9.3")
            implementation("androidx.compose.material3:material3")
            implementation("androidx.compose.ui:ui")
        }
    """.trimIndent() + "\n"

    private fun manifest() = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <application
                android:label="@string/app_name"
                android:theme="@android:style/Theme.Material.Light.NoActionBar">
                <activity android:name=".MainActivity" android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity>
            </application>
        </manifest>
    """.trimIndent() + "\n"

    private fun mainActivity(pkg: String, appName: String, idea: String) = """
        package $pkg

        import android.os.Bundle
        import androidx.activity.ComponentActivity
        import androidx.activity.compose.setContent
        import androidx.compose.foundation.layout.*
        import androidx.compose.material3.MaterialTheme
        import androidx.compose.material3.Surface
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Alignment
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.unit.dp

        class MainActivity : ComponentActivity() {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                setContent {
                    MaterialTheme {
                        Surface(modifier = Modifier.fillMaxSize()) { Home() }
                    }
                }
            }
        }

        @Composable
        private fun Home() {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("$appName", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                Text("$idea", style = MaterialTheme.typography.bodyLarge)
            }
        }
    """.trimIndent() + "\n"

    private fun strings(appName: String) = """
        <resources>
            <string name="app_name">$appName</string>
        </resources>
    """.trimIndent() + "\n"

    private fun readme(spec: AppSpec, idea: String) = """
        # ${spec.appName}

        $idea

        _Conceived on **Aarso**. Your CI builds the APK; Aarso installs it._

        ## Build
        ```bash
        ./gradlew :app:assembleDebug
        ```
        The `.github/workflows/build.yml` workflow builds a debug APK on every push and
        uploads it as an artifact — which Aarso lists and installs in-app.
    """.trimIndent() + "\n"

    /** A CI workflow that produces an APK artifact — the other half of the IDE loop. */
    private fun ciWorkflow() = """
        name: build
        on: [push, workflow_dispatch]
        jobs:
          apk:
            runs-on: ubuntu-latest
            steps:
              - uses: actions/checkout@v4
              - uses: actions/setup-java@v4
                with: { distribution: temurin, java-version: "17" }
              - uses: android-actions/setup-android@v3
              - run: ./gradlew :app:assembleDebug
              - uses: actions/upload-artifact@v4
                with:
                  name: app-debug
                  path: app/build/outputs/apk/debug/*.apk
    """.trimIndent() + "\n"

    private val GRADLE_PROPERTIES = """
        org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
        android.useAndroidX=true
        kotlin.code.style=official
    """.trimIndent() + "\n"

    private val GITIGNORE = """
        *.iml
        .gradle/
        /local.properties
        .idea/
        /build/
        **/build/
        .DS_Store
    """.trimIndent() + "\n"
}
