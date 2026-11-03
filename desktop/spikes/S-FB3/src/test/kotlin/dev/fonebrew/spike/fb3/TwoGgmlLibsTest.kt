package dev.fonebrew.spike.fb3

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S-FB3 part B. libaarso_llama.so and libaarso_sd.so each carry THEIR OWN statically linked ggml
 * (llama.cpp's and stable-diffusion.cpp's forks respectively). Both export default-visibility
 * `ggml_*` symbols, so the question the plan marks "unknown" is whether the dynamic linker keeps
 * each library bound to its own copy when both are loaded into one JVM. The JVM dlopen()s with
 * RTLD_LOCAL, so in theory yes -- this test checks it with the linker's own binding log
 * (LD_DEBUG=bindings) instead of trusting the theory.
 */
class TwoGgmlLibsTest {

    private val llamaDir = File(System.getProperty("spike.llamaDir"))
    private val sdDir = File(System.getProperty("spike.sdDir"))
    private val llamaSo = File(llamaDir, "libaarso_llama.so")
    private val sdSo = File(sdDir, "libaarso_sd.so")

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "timed out: ${cmd.toList()}")
        return out
    }

    private fun definedGlobals(so: File): Set<String> =
        run("nm", "-D", "--defined-only", so.path).lines()
            .mapNotNull { Regex("^\\S+ [TDBRW] (\\S+)$").find(it)?.groupValues?.get(1) }.toSet()

    @Test fun `both shims were built and each defines its own copy of the same ggml symbols`() {
        assertTrue(llamaSo.isFile, "missing $llamaSo")
        assertTrue(sdSo.isFile, "missing $sdSo")
        val llama = definedGlobals(llamaSo)
        val sd = definedGlobals(sdSo)
        val clash = llama.filter { it.startsWith("ggml_") }.toSet().intersect(sd.filter { it.startsWith("ggml_") }.toSet())
        println("S-FB3-B ggml_* symbols defined (default visibility) by BOTH libraries: ${clash.size}, e.g. ${clash.sorted().take(6)}")
        assertTrue(clash.size > 50, "expected the two static ggml copies to export overlapping symbols; got ${clash.size}")
        assertTrue("Java_dev_fonebrew_inference_image_SdImageEngine_nativeSdLoad" in sd)
    }

    @Test fun `both libraries load into one JVM and answer through JNI, interleaved and multi-threaded`() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val out = run(
            java, "-Djava.library.path=${llamaDir.path}${File.pathSeparator}${sdDir.path}",
            "-cp", System.getProperty("spike.runtimeClasspath"), "dev.fonebrew.spike.fb3.TwoLibsMainKt",
        )
        println(out.lines().filter { it.startsWith("TWOLIBS") }.joinToString("\n"))
        assertTrue("TWOLIBS failures=0" in out, out)
    }

    @Test fun `LD_DEBUG bindings - every ggml reference inside each library binds to that same library`() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val dbg = File.createTempFile("ld-debug", "").also { it.delete() }
        val pb = ProcessBuilder(
            java, "-Djava.library.path=${llamaDir.path}${File.pathSeparator}${sdDir.path}",
            "-cp", System.getProperty("spike.runtimeClasspath"), "dev.fonebrew.spike.fb3.TwoLibsMainKt",
        ).redirectErrorStream(true)
        pb.environment()["LD_DEBUG"] = "bindings"
        pb.environment()["LD_DEBUG_OUTPUT"] = dbg.path // glibc appends ".<pid>"
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(180, TimeUnit.SECONDS))
        assertTrue("TWOLIBS failures=0" in out, out)

        val logs = dbg.parentFile.listFiles { f -> f.name.startsWith(dbg.name + ".") }.orEmpty()
        assertTrue(logs.isNotEmpty(), "no LD_DEBUG output found (glibc built without it?)")
        val re = Regex("""binding file (\S+) \[\d+] to (\S+) \[\d+]: normal symbol `([^']+)'""")
        var llamaRefs = 0; var sdRefs = 0
        val cross = mutableListOf<String>()
        for (f in logs) {
            f.forEachLine { line ->
                val m = re.find(line) ?: return@forEachLine
                val (from, to, sym) = m.destructured
                val fromName = File(from).name; val toName = File(to).name
                val relevant = sym.startsWith("ggml_") || sym.startsWith("llama_") || sym.startsWith("sd_") ||
                    sym.startsWith("gguf_") || sym.contains("generate_image") || sym.endsWith("_sd_ctx")
                if (!relevant) return@forEachLine
                if (fromName == "libaarso_llama.so") { llamaRefs++; if (toName != "libaarso_llama.so") cross += "$fromName -> $toName: $sym" }
                if (fromName == "libaarso_sd.so") { sdRefs++; if (toName != "libaarso_sd.so") cross += "$fromName -> $toName: $sym" }
            }
            f.delete()
        }
        println("S-FB3-B LD_DEBUG bindings: llama-shim ggml/llama refs=$llamaRefs, sd-shim ggml/sd refs=$sdRefs, cross-library bindings=${cross.size}")
        assertTrue(llamaRefs > 0 && sdRefs > 0, "binding log had no relevant references (llama=$llamaRefs sd=$sdRefs) -- check would be vacuous")
        assertEquals(emptyList(), cross, "a ggml/llama/sd reference bound to the OTHER library")
    }
}
