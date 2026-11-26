package dev.fonebrew.inference

import java.io.File

/**
 * Decides whether this CPU can run libaarso_llama.so.
 *
 * core-engine/src/main/cpp/CMakeLists.txt compiles with `-march=armv8.2-a+dotprod+i8mm+fp16` and
 * `GGML_NATIVE=OFF`: no runtime dispatch, so an arm64 CPU missing any of those extensions would
 * die with SIGILL (an uncatchable native crash) as soon as the library's code runs. This reads the
 * `Features` lines of /proc/cpuinfo and says so up front instead. It never changes the build
 * flags: whether to ship a baseline variant is a performance decision for an on-device benchmark.
 *
 * Linux names the extensions differently from the compiler: dotprod = `asimddp`, i8mm = `i8mm`,
 * fp16 = `asimdhp` (vector) + `fphp` (scalar).
 *
 * Unknown is not "missing": if /proc/cpuinfo is unreadable or has no `Features` line (a non-arm
 * host, or a locked-down kernel) the result is [CpuSupport.Unknown], which does not block — we do
 * not invent a verdict, and a library that cannot run on the ABI fails its normal load path.
 */
internal object CpuFeatureCheck {

    /** compiler-facing name -> /proc/cpuinfo flags that must ALL be present. */
    val REQUIRED: Map<String, List<String>> = linkedMapOf(
        "dotprod" to listOf("asimddp"),
        "i8mm" to listOf("i8mm"),
        "fp16" to listOf("asimdhp", "fphp"),
    )

    /**
     * Pure: every `Features` line (one per core, and big.LITTLE cores can differ) must carry every
     * required flag, because the native code may be scheduled on any core.
     */
    fun evaluate(cpuinfo: String?): CpuSupport {
        if (cpuinfo == null) return CpuSupport.Unknown("could not read /proc/cpuinfo")
        val perCore = cpuinfo.lineSequence()
            .map { it.trim() }
            .filter { it.substringBefore(':').trim().equals("Features", ignoreCase = true) && ':' in it }
            .map { line -> line.substringAfter(':').trim().split(Regex("\\s+")).toSet() }
            .toList()
        if (perCore.isEmpty()) return CpuSupport.Unknown("no Features line in /proc/cpuinfo")
        val missing = REQUIRED.filter { (_, flags) -> perCore.any { core -> !core.containsAll(flags) } }.keys.toList()
        return if (missing.isEmpty()) CpuSupport.Supported else CpuSupport.Missing(missing)
    }

    fun ofThisDevice(readCpuInfo: () -> String? = ::readProcCpuInfo): CpuSupport =
        evaluate(runCatching(readCpuInfo).getOrNull())

    private fun readProcCpuInfo(): String? = File("/proc/cpuinfo").takeIf { it.canRead() }?.readText()
}

internal sealed class CpuSupport {
    object Supported : CpuSupport()

    /** Could not tell; does not block the engine. */
    data class Unknown(val why: String) : CpuSupport()

    data class Missing(val features: List<String>) : CpuSupport()

    /** Legible one-liner for the UI, or null when the on-device engine may run. */
    val blockReason: String?
        get() = (this as? Missing)?.let {
            "on-device engine needs CPU features this phone lacks (${it.features.joinToString(", ")}); " +
                "use a cloud model for this chat instead (cloud is a watched object)"
        }

    /** Throws [UnsupportedCpuException] when [Missing]; a no-op otherwise. */
    fun requireSupported() {
        blockReason?.let { throw UnsupportedCpuException(it) }
    }
}

internal class UnsupportedCpuException(message: String) : IllegalStateException(message)
