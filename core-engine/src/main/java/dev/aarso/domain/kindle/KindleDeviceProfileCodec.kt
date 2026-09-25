package dev.aarso.domain.kindle

import org.json.JSONArray
import org.json.JSONObject

object KindleDeviceProfileCodec {
    fun decode(raw: String): List<KindleDeviceProfile> {
        val root = JSONObject(raw)
        require(root.getInt("schemaVersion") == 1) { "Unsupported Kindle profile schema." }
        return root.getJSONArray("profiles").mapObjects { profile ->
            KindleDeviceProfile(
                id = profile.getString("id"),
                family = profile.getString("family"),
                model = profile.getString("model"),
                generation = profile.getString("generation"),
                aliases = profile.getJSONArray("aliases").mapStrings().toSet(),
                serialPrefixes = profile.getJSONArray("serialPrefixes").mapStrings().toSet(),
                certifiedFirmware = profile.getJSONArray("certifiedFirmware").mapStrings()
                    .map(KindleFirmwareVersion::parse).toSet(),
                provisioningRanges = profile.getJSONArray("provisioningRanges").mapObjects { range ->
                    KindleFirmwareRange(
                        KindleFirmwareVersion.parse(range.getString("minimumInclusive")),
                        KindleFirmwareVersion.parse(range.getString("maximumInclusive")),
                    )
                },
                displayWidth = profile.getJSONObject("display").getInt("width"),
                displayHeight = profile.getJSONObject("display").getInt("height"),
                pageButtons = profile.getBoolean("pageButtons"),
            )
        }
    }

    private fun JSONArray.mapStrings(): List<String> = List(length()) { getString(it) }
    private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
        List(length()) { transform(getJSONObject(it)) }
}

