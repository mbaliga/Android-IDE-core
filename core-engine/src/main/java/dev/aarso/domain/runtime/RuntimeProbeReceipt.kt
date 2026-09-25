package dev.aarso.domain.runtime

import java.time.Instant

data class RuntimePackageVersion(
    val packageName: String,
    val version: String,
)

enum class RuntimeThermalState { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN, UNKNOWN }

/** Point-in-time evidence emitted by a concrete runtime probe, never an install-time promise. */
data class RuntimeProbeReceipt(
    val providerId: String,
    val observedAtUtc: Instant,
    val runtimeVersion: String,
    val architecture: String,
    val packageVersions: List<RuntimePackageVersion>,
    val freeSpaceBytes: Long,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val thermalState: RuntimeThermalState,
    val fileOutputChannelVerified: Boolean,
    val androidSdkVerified: Boolean = false,
) {
    init {
        require(providerId.isNotBlank() && runtimeVersion.isNotBlank() && architecture.isNotBlank())
        require(freeSpaceBytes >= 0)
        require(batteryPercent == null || batteryPercent in 0..100)
        require(packageVersions.map { it.packageName }.distinct().size == packageVersions.size)
    }
}
