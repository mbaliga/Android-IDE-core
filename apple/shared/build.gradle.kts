// :shared -- Kotlin Multiplatform seam for the iOS port (porting plan IOS-1, first slice).
//
// commonMain = the pure-Kotlin domain files listed in mapping.txt, compiled IN PLACE from core-engine
// (nothing copied; the Android build keeps owning them). jvmTest = the matching existing JUnit tests
// from core-engine (runs on Linux, BUILT-HERE). commonTest/iosSimulatorArm64Test = new kotlin.test
// smoke tests that run on the iOS simulator in CI only (macos-latest).
//
// iOS targets: the static framework "FonebrewShared" is what the SwiftUI shell under apple/ios links.
// Kotlin/Native iOS targets are NOT compilable on Linux; kotlin.native.ignoreDisabledTargets=true makes
// Gradle skip them there.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

val repoRoot: File = rootDir.parentFile
val mainFiles = mutableListOf<File>()
val testFiles = mutableListOf<File>()
file("mapping.txt").readLines(Charsets.UTF_8).forEachIndexed { i, raw ->
    val line = raw.trim()
    if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
    val p = line.split(" | ").map { it.trim() }
    require(p.size == 2) { "mapping.txt:${i + 1}: expected 'main|test | path'" }
    val f = File(repoRoot, p[1])
    require(f.isFile) { "mapping.txt:${i + 1}: ${p[1]} does not exist" }
    when (p[0]) {
        "main" -> mainFiles += f
        "test" -> testFiles += f
        else -> error("mapping.txt:${i + 1}: unknown kind ${p[0]}")
    }
}

kotlin {
    jvmToolchain(17)
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    val iosTargets = listOf(iosArm64(), iosSimulatorArm64())
    iosTargets.forEach { t ->
        t.binaries.framework {
            baseName = "FonebrewShared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(layout.buildDirectory.dir("generated/mapped-main"))
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
        jvmTest {
            kotlin.srcDir(layout.buildDirectory.dir("generated/mapped-jvmTest"))
            dependencies { implementation(libs.junit) }
        }
    }
}

// Copy-free mapping is not possible through srcDir for individual files, so the mapped files are
// mirrored into build/ (a generated, git-ignored directory) before compilation. The SOURCE OF TRUTH
// stays in core-engine; build/ is disposable. scripts/check_shared_sources.py proves the mapped set
// is common-Kotlin-clean.
val syncMapped = tasks.register("syncMappedSources") {
    val outMain = layout.buildDirectory.dir("generated/mapped-main")
    val outTest = layout.buildDirectory.dir("generated/mapped-jvmTest")
    inputs.files(mainFiles + testFiles)
    outputs.dirs(outMain, outTest)
    doLast {
        fun mirror(files: List<File>, dest: File) {
            dest.deleteRecursively(); dest.mkdirs()
            files.forEach { f -> f.copyTo(File(dest, f.relativeTo(repoRoot).path.replace('/', '_')), overwrite = true) }
        }
        mirror(mainFiles, outMain.get().asFile)
        mirror(testFiles, outTest.get().asFile)
    }
}
tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") || it.name.endsWith("SourcesJar") }
    .configureEach { dependsOn(syncMapped) }
