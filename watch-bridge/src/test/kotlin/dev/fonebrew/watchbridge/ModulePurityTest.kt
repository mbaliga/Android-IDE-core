package dev.fonebrew.watchbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * `:watch-bridge` is pure Kotlin BY DESIGN (docs/design/watchbrew.md section 5: a later Kotlin
 * Multiplatform watchosArm64 conversion). Android leaking in — through a source import, a Gradle
 * plugin, or the classpath — would silently end that, so it fails the build here.
 */
class ModulePurityTest {

    private fun moduleDir(): File {
        // Gradle runs tests with the project directory as the working directory; tolerate an IDE running from the repo root.
        val candidates = listOf(File("."), File("watch-bridge"))
        val dir = candidates.firstOrNull { File(it, "src/main/kotlin").isDirectory }
        assertNotNull("cannot locate the watch-bridge module directory from ${File(".").absolutePath}", dir)
        return dir!!
    }

    private fun mainSources(): List<File> =
        File(moduleDir(), "src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun scansARealSetOfSources() {
        // Guards against the scan silently covering nothing.
        val names = mainSources().map { it.name }.toSet()
        assertTrue("expected the module's core files, found $names", names.containsAll(setOf("Messages.kt", "WatchBridgeCodec.kt", "PayloadCodecs.kt", "StrictJson.kt")))
    }

    @Test
    fun mainSources_importOnlyKotlinJavaOrgJsonOrThemselves() {
        val allowed = listOf("kotlin.", "java.", "org.json.", "dev.fonebrew.watchbridge")
        for (file in mainSources()) {
            for (line in file.readLines()) {
                val t = line.trim()
                if (!t.startsWith("import ")) continue
                val target = t.removePrefix("import ").trim()
                assertTrue("${file.name}: disallowed import '$target' (only ${allowed.joinToString()} may be imported)", allowed.any { target.startsWith(it) })
            }
        }
    }

    @Test
    fun mainSources_neverReferenceAndroidOrOtherModules_evenFullyQualified() {
        val forbidden = Regex("""\b(android|androidx|com\.google\.android|com\.google\.firebase|dev\.fonebrew\.(?!watchbridge)[a-z_]+)\.""")
        for (file in mainSources()) {
            file.readLines().forEachIndexed { i, line ->
                // KDoc/comments may NAME these things to explain the rule; code may not use them.
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || code.startsWith("/*")) return@forEachIndexed
                assertFalse("${file.name}:${i + 1} references a forbidden package: $line", forbidden.containsMatchIn(code))
            }
        }
    }

    @Test
    fun buildFile_appliesNoAndroidPlugin_andDeclaresNoAndroidDependency() {
        val build = File(moduleDir(), "build.gradle.kts").readLines()
            .map { it.substringBefore("//") } // ignore comments that explain the rule
            .joinToString("\n")
        assertTrue("must apply the plain-JVM Kotlin plugin", build.contains("libs.plugins.kotlin.jvm"))
        for (bad in listOf("com.android", "libs.plugins.android", "android {", "androidx", "play-services", "com.google.android", "compose", "coroutines", "project(\":core-engine\")", "project(\":app\")")) {
            assertFalse("build.gradle.kts must not mention '$bad'", build.contains(bad))
        }
    }

    @Test
    fun testClasspath_hasNoAndroidArtifacts() {
        val entries = System.getProperty("java.class.path").split(File.pathSeparator).filter { it.isNotBlank() }
        assertTrue(entries.isNotEmpty())
        val badNames = Regex("""^(androidx\.|android\.jar|play-services|core-ktx|appcompat|activity|fragment|lifecycle|room-|compose|wear)""")
        for (e in entries) {
            val name = File(e).name
            assertFalse("Android-ish artifact on the watch-bridge classpath: $e", badNames.containsMatchIn(name))
        }
        for (cls in listOf("android.os.Bundle", "androidx.annotation.NonNull", "com.google.android.gms.wearable.Wearable")) {
            try {
                Class.forName(cls)
                fail("$cls must not be loadable from :watch-bridge")
            } catch (expected: ClassNotFoundException) {
                // good: pure JVM
            }
        }
    }

    @Test
    fun publicSurface_isTheEightMessagesPlusCodecAndTransportVocabulary() {
        // A wire contract grows deliberately: a new public top-level type must be added here on purpose.
        val declared = mainSources().flatMap { f ->
            f.readLines().mapNotNull { Regex("""^public (?:data |sealed |enum )?(?:class|interface|object) (\w+)""").find(it)?.groupValues?.get(1) }
        }.toSet()
        assertEquals(
            setOf(
                "WatchBridge", "WatchBridgeCodec", "DecodeResult", "WatchMessage",
                "ContractHello", "AskRequest", "AskChunk", "Usage", "AskError",
                "WatchTaskState", "TaskEntry", "TaskSnapshot", "TaskToggleRequest", "TaskToggleResult",
                "LoopRunSnapshot", "Spend", "StateGlyphKeys", "VersionMismatch", "HelloCompatibility",
                // Transport vocabulary shared by both consumers (paths + stream framing) — still pure constants/bytes.
                "WatchPaths", "StreamFraming", "FrameDecoder",
            ),
            declared,
        )
    }
}
