package dev.aarso.domain.kindle

import org.json.JSONObject

data class WorkdeckClientManifest(
    val schemaVersion: Int,
    val manifestVersion: Int,
    val clientVersion: String,
    val protocolVersion: Int,
    val architecture: String,
    val sha256: String,
    val sizeBytes: Long,
    val supportedProfileIds: Set<String>,
    val supportedFirmware: List<KindleFirmwareRange>,
) {
    init {
        require(schemaVersion == 1 && manifestVersion > 0) { "Unsupported Workdeck client manifest." }
        require(clientVersion.isNotBlank() && protocolVersion == 1) { "Unsupported Workdeck client version/protocol." }
        require(architecture == "arm-linux-gnueabihf") { "Unsupported Workdeck client architecture." }
        require(ProvisioningPackage.SHA256.matches(sha256)) { "Invalid Workdeck client SHA-256." }
        require(sizeBytes in 1..MAX_CLIENT_BYTES) { "Invalid Workdeck client size." }
        require(supportedProfileIds.isNotEmpty() && supportedFirmware.isNotEmpty()) { "Workdeck compatibility bounds are required." }
    }

    fun requireCompatible(profile: KindleDeviceProfile, firmware: KindleFirmwareVersion) {
        require(profile.id in supportedProfileIds && supportedFirmware.any { firmware in it }) {
            "This Workdeck client manifest does not support ${profile.model} firmware $firmware."
        }
    }

    companion object { const val MAX_CLIENT_BYTES = 16L * 1024L * 1024L }
}

object WorkdeckClientManifestCodec {
    private val ROOT_KEYS = setOf(
        "schemaVersion", "manifestVersion", "clientVersion", "protocolVersion", "architecture",
        "sha256", "sizeBytes", "supportedProfileIds", "supportedFirmware",
    )
    private val RANGE_KEYS = setOf("minimumInclusive", "maximumInclusive")

    fun decode(raw: String): WorkdeckClientManifest {
        require(raw.toByteArray().size <= 64 * 1024) { "Workdeck client manifest exceeds size limit." }
        val value = JSONObject(raw)
        require(value.keys().asSequence().toSet() == ROOT_KEYS) { "Workdeck client manifest fields are invalid." }
        return WorkdeckClientManifest(
            schemaVersion = value.getInt("schemaVersion"),
            manifestVersion = value.getInt("manifestVersion"),
            clientVersion = value.getString("clientVersion"),
            protocolVersion = value.getInt("protocolVersion"),
            architecture = value.getString("architecture"),
            sha256 = value.getString("sha256").lowercase(),
            sizeBytes = value.getLong("sizeBytes"),
            supportedProfileIds = value.getJSONArray("supportedProfileIds").let { array ->
                List(array.length()) { array.getString(it) }.toSet()
            },
            supportedFirmware = value.getJSONArray("supportedFirmware").let { array ->
                List(array.length()) { index ->
                    val range = array.getJSONObject(index)
                    require(range.keys().asSequence().toSet() == RANGE_KEYS) { "Workdeck firmware range fields are invalid." }
                    KindleFirmwareRange(
                        KindleFirmwareVersion.parse(range.getString("minimumInclusive")),
                        KindleFirmwareVersion.parse(range.getString("maximumInclusive")),
                    )
                }
            },
        )
    }
}
