package dev.fonebrew.spike.fb3

import dev.fonebrew.inference.LlamaCppEngine
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S-FB3 evidence. The shim under test is core-engine's UNMODIFIED llama_jni.cpp compiled for
 * Linux x86_64 (CPU) and linked with the pinned llama.cpp; no model file is involved.
 */
class LlamaShimLoadTest {

    private val variant = System.getProperty("spike.variant")
    private val nativeDir = File(System.getProperty("spike.nativeDir"))
    private val logDir = File(System.getProperty("spike.logDir"))
    private val shim = File(nativeDir, "libaarso_llama.so")

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "timed out: ${cmd.toList()}")
        return out
    }

    private fun mappedPaths(): List<String> =
        File("/proc/self/maps").readLines().mapNotNull { Regex("""\s(/\S.*?)(?:\s+\(deleted\))?$""").find(it)?.groupValues?.get(1) }

    @Test fun `the shim was built and is a Linux x86_64 ELF shared object`() {
        assertTrue(shim.isFile, "missing $shim")
        val header = run("readelf", "-h", shim.path)
        println("S-FB3 [$variant] test JVM: ${System.getProperty("java.vendor")} ${System.getProperty("java.version")}")
        println("S-FB3 [$variant] readelf -h:\n$header")
        assertTrue("ELF64" in header && "X86-64" in header && "DYN" in header, header)
    }

    @Test fun `System_loadLibrary aarso_llama loads the shim and its ggml code into this JVM`() {
        LlamaCppEngine() // companion init => System.loadLibrary("aarso_llama"), exactly as core does
        val mapped = mappedPaths().toSet()
        println("S-FB3 [$variant] mapped native libs: ${mapped.filter { Regex("\\.so(\\.\\d+)*$").containsMatchIn(it) && ("aarso" in it || "ggml" in it || "llama" in it) }.sorted()}")
        assertTrue(mapped.any { it.endsWith("/libaarso_llama.so") }, "shim not mapped into the JVM")
        if (variant == "shared") {
            // ggml's CMake installs versioned sonames (libllama.so.0 ...) into <build>/bin
            for (lib in listOf("libllama", "libggml", "libggml-base", "libggml-cpu"))
                assertTrue(mapped.any { Regex("/" + Regex.escape(lib) + "\\.so(\\.\\d+)*$").containsMatchIn(it) }, "$lib not mapped (shared variant): $mapped")
            val rpath = run("readelf", "-d", shim.path).lines().filter { "RPATH" in it || "RUNPATH" in it }
            println("S-FB3 [shared] shim rpath/runpath: $rpath  (contains a build-tree path => not relocatable; see README)")
        }
    }

    @Test fun `JNI entry point runs real llama_cpp code - missing model file returns 0`() {
        val e = LlamaCppEngine()
        // llama_backend_init() + llama_model_load_from_file() on a nonexistent path: fails cleanly, handle 0.
        assertEquals(0L, e.loadModel("/definitely/not/a/model.gguf", 512))
    }

    @Test fun `JNI callback path works - generate without a model reports through TokenSink onError`() {
        val errors = mutableListOf<String>()
        LlamaCppEngine().generate(0L, object : LlamaCppEngine.TokenSink {
            override fun onToken(utf8: ByteArray, logprob: Float, entropy: Float) = error("no tokens expected")
            override fun onDone() = error("onDone not expected without a model")
            override fun onError(message: String) { errors += message }
        })
        assertEquals(listOf("no model loaded"), errors)
    }

    @Test fun `null-handle natives are inert`() {
        val e = LlamaCppEngine()
        e.free(0L)
        e.requestStop(0L)
        assertEquals(0, e.countTokens(0L, "hello"))
    }

    @Test fun `the shim exports all five JNI symbols and no unresolved symbol blocks dlopen`() {
        val syms = run("nm", "-D", "--defined-only", shim.path)
        val jni = Regex("Java_dev_fonebrew_inference_LlamaCppEngine_\\w+").findAll(syms).map { it.value }.toSortedSet()
        println("S-FB3 [$variant] JNI exports: $jni")
        assertEquals(
            setOf("nativeCountTokens", "nativeFree", "nativeGenerate", "nativeLoadModel", "nativeRequestStop")
                .map { "Java_dev_fonebrew_inference_LlamaCppEngine_$it" }.toSortedSet(),
            jni,
        )
        if (variant == "static") {
            // ggml/llama are linked INTO the shim (as in the Android build)
            for (s in listOf("llama_backend_init", "llama_model_load_from_file", "ggml_init", "ggml_backend_cpu_init"))
                assertTrue(Regex("\\bT $s\\b").containsMatchIn(syms), "$s not defined in the static shim")
        }
        val needed = run("readelf", "-d", shim.path).lines().filter { "(NEEDED)" in it }.map { it.substringAfter("[").substringBefore("]") }
        println("S-FB3 [$variant] DT_NEEDED: $needed")
        assertFalse(needed.any { "liblog" in it || "libandroid" in it }, "Android-only library required: $needed")
        val ldd = run("ldd", shim.path)
        println("S-FB3 [$variant] ldd:\n$ldd")
        assertFalse("not found" in ldd, "unresolved dependency:\n$ldd")
        val undefinedAndroid = run("nm", "-D", "--undefined-only", shim.path).lines().filter { "android_log" in it }
        assertTrue(undefinedAndroid.isEmpty(), "shim still references liblog: $undefinedAndroid")
    }

    @Test fun `FINDING - unmodified llama_jni_cpp does NOT compile on Linux without the android-log compat header`() {
        val exit = File(logDir, "$variant-negative_control.exit").readText().trim().toInt()
        val log = File(logDir, "$variant-negative_control.log").readText()
        println("S-FB3 [$variant] negative control exit=$exit")
        println(log.lines().filter { "error" in it }.take(3).joinToString("\n"))
        assertTrue(exit != 0, "the unmodified source unexpectedly compiled without the compat header")
        assertTrue("android/log.h" in log, "failed for a different reason than the missing NDK header:\n$log")
    }

    @Test fun `the proposed __ANDROID__ guard patch applied to a COPY compiles without the compat header`() {
        val exit = File(logDir, "$variant-patched_copy.exit").readText().trim().toInt()
        assertEquals(0, exit, File(logDir, "$variant-patched_copy.log").readText())
        val obj = nativeDir.walkTopDown().firstOrNull { it.isFile && it.name.startsWith("llama_jni.cpp") && it.extension == "o" && "patched" in it.path }
        assertTrue(obj != null, "no object file produced from the patched copy")
    }
}
