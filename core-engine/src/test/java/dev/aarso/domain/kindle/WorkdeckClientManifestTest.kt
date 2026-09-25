package dev.aarso.domain.kindle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkdeckClientManifestTest {
    private val valid = """
        {
          "schemaVersion":1,"manifestVersion":2,"clientVersion":"0.1.0","protocolVersion":1,
          "architecture":"arm-linux-gnueabihf",
          "sha256":"${"a".repeat(64)}","sizeBytes":1234,
          "supportedProfileIds":["kindle-oasis-3-koa3"],
          "supportedFirmware":[{"minimumInclusive":"5.17.1.0.3","maximumInclusive":"5.17.1.0.3"}]
        }
    """.trimIndent()

    @Test fun parsesAndAcceptsExactlyBoundedClient() {
        val manifest = WorkdeckClientManifestCodec.decode(valid)
        val profile = KindleDeviceProfile(
            "kindle-oasis-3-koa3", "Kindle", "Oasis 3", "10", emptySet(), setOf("G000WP04"),
            emptySet(), emptyList(), 1264, 1680, true,
        )
        manifest.requireCompatible(profile, KindleFirmwareVersion.parse("5.17.1.0.3"))
        assertEquals(1, manifest.protocolVersion)
    }

    @Test fun rejectsUnknownFieldsAndUnlistedFirmware() {
        assertTrue(runCatching { WorkdeckClientManifestCodec.decode(valid.replaceFirst("{", "{\"surprise\":true,")) }.isFailure)
        val manifest = WorkdeckClientManifestCodec.decode(valid)
        val profile = KindleDeviceProfile(
            "kindle-oasis-3-koa3", "Kindle", "Oasis 3", "10", emptySet(), setOf("G000WP04"),
            emptySet(), emptyList(), 1264, 1680, true,
        )
        assertTrue(runCatching { manifest.requireCompatible(profile, KindleFirmwareVersion.parse("5.18.1")) }.isFailure)
    }
}
