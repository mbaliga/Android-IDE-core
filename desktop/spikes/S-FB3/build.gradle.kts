// S-FB3: build core-engine's llama JNI shim (part A) and sdengine's JNI shim (part B) for Linux
// x86_64 (CPU only) with CMake, out of tree under build/native/, then load them in JVM tests.
// Pure JVM + CMake; no AGP, no NDK.
//
//   ./gradlew -p desktop/spikes/S-FB3 test                    # part A, static variant (= the Android layout)
//   ./gradlew -p desktop/spikes/S-FB3 test -Pfb3.shared=true  # part A, shared libllama.so/libggml*.so variant
//   ./gradlew -p desktop/spikes/S-FB3 testTwoLibs             # part B: llama + sd shims in ONE JVM
//
// Pins: Kotlin 2.1.0 (= core). Nothing else from the core catalog is involved.

plugins {
    kotlin("jvm") version "2.1.0"
}

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
}

val jobs = providers.gradleProperty("fb3.jobs").map(String::toInt)
    .getOrElse(minOf(Runtime.getRuntime().availableProcessors(), 4))
val variant = if (providers.gradleProperty("fb3.shared").orNull == "true") "shared" else "static"

/** A CMake build tree under build/native/<name>: one configure task, plus `cmake --build --target` tasks that log to build/native-logs. */
class NativeTree(val name: String, val dir: Provider<Directory>, val configure: TaskProvider<Exec>)

fun nativeTree(name: String, sourceDir: String, extraArgs: List<String>, watched: List<String>): NativeTree {
    val dir = layout.buildDirectory.dir("native/$name")
    val configure = tasks.register<Exec>("cmakeConfigure_$name") {
        group = "native"
        watched.forEach { inputs.files(it) }
        outputs.file(dir.map { it.file("CMakeCache.txt") })
        doFirst { dir.get().asFile.mkdirs() }
        commandLine(listOf("cmake", "-S", file(sourceDir).path, "-B", dir.get().asFile.path, "-DCMAKE_BUILD_TYPE=Release") + extraArgs)
    }
    return NativeTree(name, dir, configure)
}

fun nativeBuild(tree: NativeTree, taskName: String, target: String, ignoreFailure: Boolean = false, parallel: Int = jobs) =
    tasks.register<Exec>(taskName) {
        group = "native"
        dependsOn(tree.configure)
        outputs.upToDateWhen { false } // cmake/make track their own incrementality
        isIgnoreExitValue = ignoreFailure
        val log = layout.buildDirectory.file("native-logs/${tree.name}-$target.log")
        val exitFile = layout.buildDirectory.file("native-logs/${tree.name}-$target.exit")
        doFirst { log.get().asFile.parentFile.mkdirs() }
        val sink = lazy { log.get().asFile.outputStream() }
        standardOutput = object : java.io.OutputStream() {
            override fun write(b: Int) { sink.value.write(b); System.out.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { sink.value.write(b, off, len); System.out.write(b, off, len) }
            override fun flush() { sink.value.flush(); System.out.flush() }
        }
        errorOutput = standardOutput
        commandLine("cmake", "--build", tree.dir.get().asFile.path, "--target", target, "--parallel", parallel.toString())
        doLast {
            if (sink.isInitialized()) sink.value.close()
            exitFile.get().asFile.writeText(executionResult.get().exitValue.toString())
        }
    }

val llamaInputs = listOf("CMakeLists.txt", "compat/android/log.h", "proposed/llama_jni-android-guard.patch")

// ---- part A: the llama shim, in the selected variant -------------------------------------------
val llamaA = nativeTree(variant, ".", listOf("-DSFB3_SHARED_GGML=${variant == "shared"}"), llamaInputs)
val buildShim = nativeBuild(llamaA, "buildShim", "aarso_llama")
// Expected to FAIL (exit != 0): the unmodified source without the compat header. The failure is the evidence.
val buildNegativeControl = nativeBuild(llamaA, "buildNegativeControl", "negative_control", ignoreFailure = true)
    .also { it.configure { mustRunAfter(buildShim) } }
val buildPatchedCopy = nativeBuild(llamaA, "buildPatchedCopy", "patched_copy")
    .also { it.configure { mustRunAfter(buildNegativeControl) } }

// ---- part B: llama (always static here) + sd shim, in their own CMake projects -------------------
val llamaB = if (variant == "static") llamaA else nativeTree("static", ".", listOf("-DSFB3_SHARED_GGML=false"), llamaInputs)
val buildShimStatic = if (variant == "static") buildShim else nativeBuild(llamaB, "buildShimStatic", "aarso_llama")
val sdTree = nativeTree("sd-static", "sd", emptyList(), listOf("sd/CMakeLists.txt", "compat/android/log.h"))
// stable-diffusion.cpp's src/tokenizers/vocab/vocab.cpp #includes ~240 MB of vocabulary headers and its single
// compile is memory-hungry (an unconstrained -j4 build in the container had cc1plus killed: README finding),
// so this target defaults to 2 jobs. Override with -Pfb3.sdJobs=N.
val sdJobs = providers.gradleProperty("fb3.sdJobs").map(String::toInt).getOrElse(2)
val buildSd = nativeBuild(sdTree, "buildSd", "aarso_sd", parallel = sdJobs)

fun Test.commonProps() {
    systemProperty("spike.logDir", layout.buildDirectory.dir("native-logs").get().asFile.path)
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.test {
    dependsOn(buildShim, buildNegativeControl, buildPatchedCopy)
    filter { excludeTestsMatching("*TwoGgmlLibsTest") }
    systemProperty("spike.variant", variant)
    systemProperty("spike.nativeDir", llamaA.dir.get().asFile.path)
    systemProperty("java.library.path", llamaA.dir.get().asFile.path) // System.loadLibrary("aarso_llama") finds it, as on Android
    commonProps()
}

val testTwoLibs = tasks.register<Test>("testTwoLibs") {
    group = "verification"
    description = "Part B: libaarso_llama.so and libaarso_sd.so (two independent ggml copies) in one JVM"
    dependsOn(buildShimStatic, buildSd)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("*TwoGgmlLibsTest") }
    systemProperty("spike.llamaDir", llamaB.dir.get().asFile.path)
    systemProperty("spike.sdDir", sdTree.dir.get().asFile.path)
    systemProperty("spike.runtimeClasspath", sourceSets.main.get().runtimeClasspath.asPath)
    commonProps()
}
tasks.check { dependsOn(testTwoLibs) }
