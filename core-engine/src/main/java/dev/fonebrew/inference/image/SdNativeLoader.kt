package dev.fonebrew.inference.image

import dev.fonebrew.inference.CpuFeatureCheck
import dev.fonebrew.inference.CpuSupport
import dev.fonebrew.inference.UnsupportedCpuException

/**
 * Loads libaarso_sd behind the same CPU guard as the llama library.
 *
 * :sdengine is built with the same forced `-march=armv8.2-a+dotprod+i8mm+fp16` as llama, with no
 * runtime dispatch, so a CPU missing one of those extensions would die with SIGILL (uncatchable)
 * the first time sd code runs. The guard therefore runs BEFORE `System.loadLibrary`. The flags are
 * not changed here; a baseline variant is a benchmark decision.
 *
 * Returns the failure instead of throwing: this runs in a static initialiser, where a throw would
 * become an ExceptionInInitializerError and poison every later use of the class. The caller keeps
 * it and rethrows from its first real use, where load errors are already surfaced.
 */
internal object SdNativeLoader {

    fun attempt(
        cpu: () -> CpuSupport = { CpuFeatureCheck.ofThisDevice() },
        loadLibrary: () -> Unit = { System.loadLibrary("aarso_sd") },
    ): Throwable? {
        (cpu() as? CpuSupport.Missing)?.let { return UnsupportedCpuException(unsupportedMessage(it.features)) }
        return runCatching(loadLibrary).exceptionOrNull()
    }

    /** Image-specific wording; the llama message talks about "this chat". */
    fun unsupportedMessage(features: List<String>): String =
        "on-device image engine needs CPU features this phone lacks (${features.joinToString(", ")}); " +
            "use a cloud image provider instead (cloud is a watched object)"
}
