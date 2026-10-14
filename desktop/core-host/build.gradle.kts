// :core-host -- the JVM "host lane" over the Android core's pure Kotlin (porting plan LX-1).
//
// WHAT THIS IS. A plain Kotlin/JVM module that compiles, UNCHANGED and in place, three things the
// Android build already owns, and runs the matching JUnit tests on the host JDK:
//   * core-engine/src/main/java/dev/fonebrew/domain   (the pure-Kotlin domain/ tree)
//   * contracts/kotlin                                (the neutral contract corpus -- the same root
//                                                      :core-engine adds as an extra srcDir)
//   * core-engine/src/test/java/dev/fonebrew/domain   (the domain/ unit tests)
// Nothing is copied or moved; the files stay where Android compiles them (plan: "map by srcDir").
//
// WHAT THIS IS NOT. No Android Gradle plugin, no Android SDK (this build never reads ANDROID_HOME),
// no submodules, no Compose, no Room, no UI, no native code. It is not a KMP module (that is
// LX-4, owner-gated) and it changes no existing file. It does NOT make any desktop app exist.
//
// THE MAPPING IS DATA, NOT CODE. The roots and the exclusions live in mapping.txt, which this file
// and desktop/scripts/*.py both read, so the test-count reconciliation and PORTABILITY.md are
// derived from exactly the file set Gradle compiles. See mapping.txt for each exclusion's witness
// string and reason. In summary:
//
//   EXCLUDED main files (srcDir filter below, each with a witness check in :verifyMapping):
//     1. domain/authority/AuditedAuthorityEngine.kt        -> imports data/ReceiptStore   (plan-named)
//     2. domain/execution/CiActionsExecutionProvider.kt    -> imports data/GitTransport   (plan-named)
//     3. domain/mode/GestureModeDefaults.kt                -> imports shared-libraries'
//        dev.aarso.interactionmode.InteractionMode (NOT plan-named: appeared after the plan was
//        written; reported as a finding, not silently absorbed)
//   EXCLUDED test files: the tests of the three above, plus LoopTemplateCatalogTest (imports the
//   Context-bound data/LoopTemplateAssets). LX-3 (Android-side, gated) removes the first two
//   back-edges via small interfaces in domain/; when it lands, `:verifyMapping` fails on the stale
//   witness and tells whoever is there to delete the exclusion -- it cannot rot quietly.
//
// ONE LANE-OWNED STAND-IN (src/hostShim/kotlin/android/icu/text/BreakIterator.kt). The plan's "no
// android.* import" grep is true of import lines and false of the code: domain/search/Segmenter.kt
// names android.icu.text.BreakIterator fully-qualified on five code lines, so it cannot compile on a
// plain JVM unaided. The stand-in is what android.jar is to the Android unit-test lane -- a stub
// whose factory THROWS -- and Segmenter's own probe catches that and falls back to java.text, the
// identical branch the Android JVM gate takes. It has no implementation by design. See the file.
//
// org.json: the Android runtime provides org.json as a platform class; the JVM does not, so the
// host lane declares the real artifact (same 20231013 the Android unit tests already use). It is a
// RUNTIME dependency here (api, since domain/contracts signatures expose JSONObject). The root
// build's :core-engine:checkLicense has never scanned it (test-only there); the desktop build has
// no license gate yet -- see desktop/README.md ("Licence note") rather than assuming it passes.
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// rootDir is desktop/ (this build's own root); the repo root is its parent.
val repoRoot: File = rootDir.parentFile

// ---- mapping.txt ------------------------------------------------------------------------------
class MapExclusion(val file: File, val witness: String, val reason: String)

val mappingFile = layout.projectDirectory.file("mapping.txt").asFile
val mainRoots = mutableListOf<File>()
val testRoots = mutableListOf<File>()
val mainExclusions = mutableListOf<MapExclusion>()
val testExclusions = mutableListOf<MapExclusion>()

mappingFile.readLines(Charsets.UTF_8).forEachIndexed { index, raw ->
    val line = raw.trim()
    if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
    val parts = line.split(" | ").map { it.trim() }
    val where = "mapping.txt:${index + 1}"
    fun repoFile(p: String) = File(repoRoot, p)
    when (parts[0]) {
        "main-root" -> mainRoots += repoFile(parts.getOrNull(1) ?: error("$where: main-root needs a directory"))
        "test-root" -> testRoots += repoFile(parts.getOrNull(1) ?: error("$where: test-root needs a directory"))
        "main-exclude", "test-exclude" -> {
            require(parts.size == 4) { "$where: ${parts[0]} needs 'kind | file | witness | reason'" }
            val entry = MapExclusion(repoFile(parts[1]), parts[2], parts[3])
            if (parts[0] == "main-exclude") mainExclusions += entry else testExclusions += entry
        }
        else -> error("$where: unknown mapping kind '${parts[0]}'")
    }
}

kotlin {
    jvmToolchain(17)
    sourceSets {
        named("main") {
            kotlin {
                mainRoots.forEach { srcDir(it) }
                // Host-lane-owned compile-time stand-in(s) for Android-only APIs the mapped domain
                // sources reference by fully-qualified name (see the file's own header). NOT part of
                // mapping.txt: it is this lane's code, not mapped Android code, so the scripts that
                // derive counts from mapping.txt rightly never see it.
                srcDir("src/hostShim/kotlin")
                // The srcDir filter: exact-file exclusion. Each excluded path is re-checked by
                // :verifyMapping (exists, and its witness back-edge is still there).
                val excluded = mainExclusions.map { it.file.canonicalFile }.toSet()
                exclude { element -> element.file.canonicalFile in excluded }
            }
        }
        named("test") {
            kotlin {
                testRoots.forEach { srcDir(it) }
                val excluded = testExclusions.map { it.file.canonicalFile }.toSet()
                exclude { element -> element.file.canonicalFile in excluded }
            }
        }
    }
}

dependencies {
    // contracts/kotlin exposes kotlinx.coroutines.flow.Flow in public signatures.
    api(libs.kotlinx.coroutines.core)
    // See the org.json paragraph in the header. api: JSONObject appears in domain/contracts APIs.
    api(libs.org.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// ---- :verifyMapping ---------------------------------------------------------------------------
// Fails the build if the mapping has gone stale: a root that does not exist, an excluded file that
// does not exist, or an excluded file whose witness back-edge is gone (=> exclusion no longer
// needed). Runs before any compile so a stale exclusion is the FIRST thing you see.
val verifyMapping by tasks.registering {
    group = "verification"
    description = "Checks mapping.txt: roots exist, every exclusion exists and still carries its witness back-edge."
    inputs.file(mappingFile)
    inputs.files(mainExclusions.map { it.file }.filter { it.exists() })
    inputs.files(testExclusions.map { it.file }.filter { it.exists() })
    val stamp = layout.buildDirectory.file("verifyMapping.ok")
    outputs.file(stamp)
    doLast {
        val problems = mutableListOf<String>()
        (mainRoots + testRoots).forEach { if (!it.isDirectory) problems += "mapped root is not a directory: $it" }
        (mainExclusions + testExclusions).forEach { ex ->
            val rel = ex.file.relativeTo(repoRoot)
            when {
                !ex.file.isFile ->
                    problems += "excluded file no longer exists: $rel -- delete its mapping.txt line"
                !ex.file.readText(Charsets.UTF_8).contains(ex.witness) ->
                    problems += "excluded file no longer contains its witness back-edge '${ex.witness}': $rel " +
                        "-- the exclusion is stale; delete its mapping.txt line and let the file compile"
            }
        }
        // An exclusion must actually lie under a mapped root, or it filters nothing.
        mainExclusions.forEach { ex ->
            if (mainRoots.none { ex.file.canonicalPath.startsWith(it.canonicalPath + File.separator) })
                problems += "main-exclude is outside every main-root (filters nothing): ${ex.file.relativeTo(repoRoot)}"
        }
        testExclusions.forEach { ex ->
            if (testRoots.none { ex.file.canonicalPath.startsWith(it.canonicalPath + File.separator) })
                problems += "test-exclude is outside every test-root (filters nothing): ${ex.file.relativeTo(repoRoot)}"
        }
        if (problems.isNotEmpty()) throw GradleException("mapping.txt is stale:\n  - " + problems.joinToString("\n  - "))
        stamp.get().asFile.apply { parentFile.mkdirs(); writeText("ok\n") }
    }
}
tasks.named("compileKotlin") { dependsOn(verifyMapping) }
tasks.named("compileTestKotlin") { dependsOn(verifyMapping) }

// ---- tests ------------------------------------------------------------------------------------
tasks.test {
    useJUnit()
    maxHeapSize = "1g"
    // The domain tests were authored against a UTF-8 default (test names contain an em-dash).
    jvmArgs("-Dfile.encoding=UTF-8")
    // Default working directory (desktop/core-host) is intentional: the two tests that read the
    // bundled loop asset hunt upward for core-engine/src/main/assets/..., and nothing here writes
    // relative to the cwd -- so the lane cannot dirty core-engine/.
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
}
