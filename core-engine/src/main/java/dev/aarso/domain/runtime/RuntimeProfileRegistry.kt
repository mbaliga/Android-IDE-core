package dev.aarso.domain.runtime

import dev.aarso.data.runtime.TermuxRunCommandBridge
import dev.aarso.data.runtime.TermuxWindowsCompatBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runtime profiles whose capabilities are discovered at runtime rather than compiled in.
 *
 * Probes are user/device actions; they are never treated as proof of future availability.
 */
class RuntimeProfileRegistry(
    private val termux: TermuxRunCommandBridge,
    private val windowsCompat: TermuxWindowsCompatBridge,
) {
    private val _termuxProfile = MutableStateFlow(termux.setupProfile())
    val termuxProfile: StateFlow<RuntimeProviderProfile> = _termuxProfile.asStateFlow()

    private val _windowsProfile = MutableStateFlow(
        RuntimeProviderProfile(
            providerId = TermuxWindowsCompatBridge.PROVIDER_ID,
            executionTargetType = dev.aarso.contracts.execution.ExecutionTargetType.LOCAL_ANDROID,
            kinds = setOf(RuntimeKind.WINDOWS_COMPAT),
            capabilities = emptySet(),
            locality = RuntimeLocality.ON_DEVICE,
            availability = RuntimeAvailability.NEEDS_SETUP,
            setupHint = "Probe the local Wine/translation compatibility runtime.",
        )
    )
    val windowsProfile: StateFlow<RuntimeProviderProfile> = _windowsProfile.asStateFlow()

    suspend fun refreshTermux(): RuntimeProviderProfile {
        val profile = termux.probeProfile()
        _termuxProfile.value = profile
        return profile
    }

    suspend fun refreshWindowsCompat(): RuntimeProviderProfile {
        val (_, profile) = windowsCompat.probe()
        _windowsProfile.value = profile
        return profile
    }

    fun currentProfiles(): List<RuntimeProviderProfile> =
        listOf(_termuxProfile.value, _windowsProfile.value)
}
