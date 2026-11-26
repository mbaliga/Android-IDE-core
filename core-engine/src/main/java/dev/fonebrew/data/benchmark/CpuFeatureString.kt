package dev.fonebrew.data.benchmark

import dev.fonebrew.inference.CpuFeatureCheck
import dev.fonebrew.inference.CpuSupport

/**
 * The CPU line stored with each result: the existing [CpuFeatureCheck] verdict on the extensions
 * the native build requires, plus the raw `Features` flags of the first core that lists them.
 * Pure over the /proc/cpuinfo text; null input (unreadable) yields "unknown", not a guess.
 */
object CpuFeatureString {

    fun describe(cpuinfo: String?): String {
        val verdict = when (val s = CpuFeatureCheck.evaluate(cpuinfo)) {
            CpuSupport.Supported -> "required extensions present (${CpuFeatureCheck.REQUIRED.keys.joinToString(", ")})"
            is CpuSupport.Missing -> "required extensions MISSING: ${s.features.joinToString(", ")}"
            is CpuSupport.Unknown -> "required extensions unknown (${s.why})"
        }
        val flags = cpuinfo?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.substringBefore(':').trim().equals("Features", ignoreCase = true) && ':' in it }
            ?.substringAfter(':')?.trim()
        return if (flags.isNullOrBlank()) verdict else "$verdict; Features: $flags"
    }
}
