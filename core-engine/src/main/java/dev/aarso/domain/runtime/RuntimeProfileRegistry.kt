package dev.aarso.domain.runtime

import dev.aarso.data.runtime.TermuxRunCommandBridge
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
) {
    private val _termuxProfile = MutableStateFlow(termux.setupProfile())
    val termuxProfile: StateFlow<RuntimeProviderProfile> = _termuxProfile.asStateFlow()

    suspend fun refreshTermux(): RuntimeProviderProfile {
        val profile = termux.probeProfile()
        _termuxProfile.value = profile
        return profile
    }

    fun currentProfiles(): List<RuntimeProviderProfile> = listOf(_termuxProfile.value)
}
