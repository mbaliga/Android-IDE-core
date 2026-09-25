package dev.aarso.domain.kindle

/** A numeric Kindle firmware version. Unknown/non-numeric suffixes are rejected fail-closed. */
data class KindleFirmwareVersion private constructor(val parts: List<Int>) : Comparable<KindleFirmwareVersion> {
    init {
        require(parts.isNotEmpty()) { "A firmware version must contain at least one numeric part." }
        require(parts.all { it >= 0 }) { "Firmware parts must be non-negative." }
    }

    override fun compareTo(other: KindleFirmwareVersion): Int {
        val width = maxOf(parts.size, other.parts.size)
        for (index in 0 until width) {
            val comparison = (parts.getOrNull(index) ?: 0).compareTo(other.parts.getOrNull(index) ?: 0)
            if (comparison != 0) return comparison
        }
        return 0
    }

    override fun toString(): String = parts.joinToString(".")

    companion object {
        private val SHAPE = Regex("^[0-9]+(?:\\.[0-9]+){1,7}$")

        fun parse(value: String): KindleFirmwareVersion {
            val trimmed = value.trim()
            require(SHAPE.matches(trimmed)) { "Unsupported Kindle firmware version format: $value" }
            return KindleFirmwareVersion(trimmed.split('.').map(String::toInt))
        }
    }
}

data class KindleFirmwareRange(
    val minimumInclusive: KindleFirmwareVersion,
    val maximumInclusive: KindleFirmwareVersion,
) {
    init {
        require(minimumInclusive <= maximumInclusive) { "Firmware range is reversed." }
    }

    operator fun contains(version: KindleFirmwareVersion): Boolean =
        version >= minimumInclusive && version <= maximumInclusive
}

data class KindleDeviceProfile(
    val id: String,
    val family: String,
    val model: String,
    val generation: String,
    val aliases: Set<String>,
    val serialPrefixes: Set<String>,
    val certifiedFirmware: Set<KindleFirmwareVersion>,
    val provisioningRanges: List<KindleFirmwareRange>,
    val displayWidth: Int,
    val displayHeight: Int,
    val pageButtons: Boolean,
) {
    init {
        require(id.isNotBlank() && family.isNotBlank() && model.isNotBlank()) { "Device profile identity is incomplete." }
        require(serialPrefixes.isNotEmpty()) { "A Kindle profile must declare at least one serial prefix." }
        require(serialPrefixes.all { normalizeSerial(it).length >= 6 }) { "Serial prefixes must be specific." }
        require(displayWidth > 0 && displayHeight > 0) { "Display dimensions must be positive." }
    }

    fun supportsProvisioning(firmware: KindleFirmwareVersion): Boolean =
        provisioningRanges.any { firmware in it }

    fun isCertified(firmware: KindleFirmwareVersion): Boolean = firmware in certifiedFirmware

    companion object {
        fun normalizeSerial(value: String): String =
            value.uppercase().filter(Char::isLetterOrDigit)
    }
}

data class KindleIdentity(
    val serial: String? = null,
    val modelLabel: String? = null,
    val firmware: KindleFirmwareVersion? = null,
    val usbVendorId: Int? = null,
    val usbProductId: Int? = null,
)

sealed interface KindleProfileMatch {
    data class Matched(val profile: KindleDeviceProfile, val confidence: Int) : KindleProfileMatch
    data class Ambiguous(val profileIds: List<String>) : KindleProfileMatch
    data object Unknown : KindleProfileMatch
}

class KindleProfileRegistry(private val profiles: List<KindleDeviceProfile>) {
    init {
        require(profiles.map { it.id }.distinct().size == profiles.size) { "Duplicate Kindle profile id." }
    }

    fun all(): List<KindleDeviceProfile> = profiles.toList()

    /**
     * Model/serial evidence is required. Amazon's USB vendor id by itself is deliberately not
     * enough: it identifies many unrelated Kindle/Fire devices and would make provisioning unsafe.
     */
    fun match(identity: KindleIdentity): KindleProfileMatch {
        val serial = identity.serial?.let(KindleDeviceProfile::normalizeSerial).orEmpty()
        val label = identity.modelLabel?.trim()?.lowercase().orEmpty()
        val scored = profiles.mapNotNull { profile ->
            var score = 0
            if (serial.isNotEmpty() && profile.serialPrefixes.any { serial.startsWith(KindleDeviceProfile.normalizeSerial(it)) }) {
                score += 100
            }
            val labels = profile.aliases + profile.model + profile.id
            if (label.isNotEmpty() && labels.any { it.lowercase() == label || label.contains(it.lowercase()) }) {
                score += 50
            }
            if (score == 0) null else profile to score
        }
        if (scored.isEmpty()) return KindleProfileMatch.Unknown
        val best = scored.maxOf { it.second }
        val winners = scored.filter { it.second == best }.map { it.first }
        return if (winners.size == 1) KindleProfileMatch.Matched(winners.single(), best)
        else KindleProfileMatch.Ambiguous(winners.map { it.id }.sorted())
    }
}

enum class KindleUsbState { DISCONNECTED, DETECTED, PERMISSION_REQUIRED, STORAGE_VISIBLE, RAW_HOST_ONLY }
enum class KindleNetworkState { OFFLINE, WIFI_VISIBLE, HOTSPOT_PAIRED }
enum class KindleFeatureState { UNKNOWN, ABSENT, PRESENT, NEEDS_ATTENTION }

data class KindleDeviceState(
    val profileId: String?,
    val model: String?,
    val serialPrefix: String?,
    val firmware: KindleFirmwareVersion?,
    val usb: KindleUsbState,
    val network: KindleNetworkState,
    val jailbreak: KindleFeatureState,
    val kpmHomebrew: KindleFeatureState,
    val ssh: KindleFeatureState,
    val otaProtection: KindleFeatureState,
    val workdeckClient: KindleFeatureState,
)

