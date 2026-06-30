package dev.aarso.domain.device

/**
 * What the app knows about the hardware before any model is loaded. Used to gate
 * downloads to models that can plausibly run (handoff §1 — RAM is the gating
 * factor). This is a *pre-flight* check; the real signal is on-device tok/s
 * benchmarking (§10.6), which only exists once the native engine runs.
 */
data class DeviceSpec(
    val totalRamBytes: Long,
    val availRamBytes: Long,
    val abis: List<String>,
) {
    val arm64: Boolean get() = abis.any { it == "arm64-v8a" }
}
