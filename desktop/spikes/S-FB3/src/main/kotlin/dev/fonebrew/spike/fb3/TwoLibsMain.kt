package dev.fonebrew.spike.fb3

import dev.fonebrew.inference.LlamaCppEngine
import dev.fonebrew.inference.image.SdImageEngine
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Loads BOTH ggml-bearing shims into one JVM and drives each through its JNI surface, interleaved and
 * from several threads. Run standalone by TwoGgmlLibsTest under LD_DEBUG=bindings so the dynamic
 * linker's own log can show which library each ggml, llama and sd symbol reference bound to.
 * Exit code 0 only if every call returned the expected "no model" result.
 */
fun main() {
    val llama = LlamaCppEngine()
    val sd = SdImageEngine()
    var failures = 0
    fun check(what: String, ok: Boolean) { println("TWOLIBS ${if (ok) "ok  " else "FAIL"} $what"); if (!ok) failures++ }

    check("llama load of missing file == 0", llama.loadModel("/no/such/model.gguf", 256) == 0L)
    check("sd load of missing file == 0", sd.load("/no/such/model.safetensors", 2) == 0L)
    check("sd txt2img on null handle == false", !sd.txt2img(0L, "x", "/tmp/never.png"))

    val pool = Executors.newFixedThreadPool(4)
    val results = (0 until 8).map { i ->
        pool.submit<Boolean> {
            if (i % 2 == 0) llama.loadModel("/no/such/$i.gguf", 128) == 0L else sd.load("/no/such/$i.gguf", 1) == 0L
        }
    }
    pool.shutdown()
    check("interleaved threads finished", pool.awaitTermination(120, TimeUnit.SECONDS))
    check("interleaved results all expected", results.all { it.get() })
    println("TWOLIBS failures=$failures")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
