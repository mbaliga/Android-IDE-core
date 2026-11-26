package dev.fonebrew.domain.benchmark

import dev.fonebrew.domain.model.ModelSpec
import dev.fonebrew.domain.model.Runtime

/** Whether a benchmark may start, with a stated reason when it may not. */
sealed class Preflight {
    data class Ready(val target: BenchmarkTarget) : Preflight()
    data class Refused(val reason: String) : Preflight()
}

/**
 * The gate in front of the run. The benchmark measures a GGUF model that is *already on this
 * device*: it never downloads, never touches the network and never offers a cloud model, so
 * anything else is refused with a reason the screen shows verbatim.
 */
object BenchmarkPreflight {

    fun check(
        spec: ModelSpec,
        fileExists: (String) -> Boolean,
        fileSizeBytes: (String) -> Long?,
        busy: Boolean = false,
    ): Preflight {
        if (spec.runtime == Runtime.CLOUD || !spec.isOnDevice) {
            return Preflight.Refused("${spec.displayName} runs in the cloud; the benchmark only measures models on this phone.")
        }
        if (spec.runtime != Runtime.LOCAL_GGUF) {
            return Preflight.Refused("${spec.displayName} is not a downloaded GGUF model, so there is no file to benchmark.")
        }
        val path = spec.modelPath
        if (path.isNullOrBlank() || !fileExists(path)) {
            return Preflight.Refused("The model file for ${spec.displayName} is not on this device. The benchmark never downloads anything.")
        }
        if (busy) {
            return Preflight.Refused("A generation is already running. Wait for it to finish or stop it first.")
        }
        val name = path.substringAfterLast('/')
        return Preflight.Ready(BenchmarkTarget(spec.id, name, path, fileSizeBytes(path)))
    }
}
