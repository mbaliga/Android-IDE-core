package dev.aarso.domain.kindle

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ProvisioningManifestTest {
    private val raw = """
        {
          "schemaVersion": 1,
          "manifestVersion": 4,
          "generatedAtUtc": "2026-09-25T00:00:00Z",
          "recipes": [{
            "id": "koa3-safe-test",
            "version": 2,
            "supportedProfileIds": ["kindle-oasis-3-koa3"],
            "supportedFirmware": [{"minimumInclusive":"5.17.1.0.3","maximumInclusive":"5.17.1.0.3"}],
            "packages": [{
              "id":"payload", "version":"1.0.0", "sourceUrl":"https://example.invalid/releases/v1/payload.zip",
              "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "sizeBytes":12
            }],
            "instructions": [
              {"id":"copy","kind":"COPY","title":"Stage","detail":"Copy package","sourcePackageId":"payload","destinationRelativePath":"documents/payload.zip"},
              {"id":"verify","kind":"VERIFY","title":"Verify","detail":"Verify state","expectedState":"HOME_BREW_PRESENT"}
            ],
            "expectedResultingStates": ["HOME_BREW_PRESENT"]
          }]
        }
    """.trimIndent()

    @Test fun acceptsBoundedRecipeAndSelectsExactFirmware() {
        val manifest = KindleProvisioningManifestCodec.decode(raw)
        val firmware = KindleFirmwareVersion.parse("5.17.1.0.3")
        val profile = profile(firmware)
        assertTrue(KindleProvisioningPolicy.select(manifest, profile, firmware) is ProvisioningSelection.Approved)
    }

    @Test fun rejectsUnlistedFirmwareFailClosed() {
        val result = KindleProvisioningPolicy.select(
            KindleProvisioningManifestCodec.decode(raw),
            profile(KindleFirmwareVersion.parse("5.17.1.0.3")),
            KindleFirmwareVersion.parse("5.17.1.0.4"),
        )
        assertTrue(result is ProvisioningSelection.Blocked)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPackageWithoutSha256() {
        KindleProvisioningManifestCodec.decode(raw.replace("a".repeat(64), "not-a-hash"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun confirmationCannotBeSilentlyCompleted() {
        val manifest = KindleProvisioningManifestCodec.decode(raw)
        val firmware = KindleFirmwareVersion.parse("5.17.1.0.3")
        var run = KindleProvisioningMachine.start("run-1", profile(firmware), firmware, manifest.recipes.single())
        run = KindleProvisioningMachine.update(run, ProvisioningStage.IDENTIFY, ProvisioningStageState.SUCCEEDED, Instant.EPOCH)
        run = KindleProvisioningMachine.update(run, ProvisioningStage.BACKUP, ProvisioningStageState.SUCCEEDED, Instant.EPOCH, receiptDigest = "b".repeat(64))
        run = KindleProvisioningMachine.update(run, ProvisioningStage.VERIFY_PACKAGE, ProvisioningStageState.SUCCEEDED, Instant.EPOCH, receiptDigest = "c".repeat(64))
        run = KindleProvisioningMachine.update(run, ProvisioningStage.STAGE_FILES, ProvisioningStageState.SUCCEEDED, Instant.EPOCH, receiptDigest = "d".repeat(64))
        KindleProvisioningMachine.update(run, ProvisioningStage.USER_CONFIRMATION, ProvisioningStageState.SUCCEEDED, Instant.EPOCH)
    }

    private fun profile(firmware: KindleFirmwareVersion) = KindleDeviceProfile(
        id = "kindle-oasis-3-koa3", family = "Kindle", model = "Kindle Oasis 3", generation = "10th gen",
        aliases = setOf("KOA3"), serialPrefixes = setOf("G000WP04"), certifiedFirmware = setOf(firmware),
        provisioningRanges = listOf(KindleFirmwareRange(firmware, firmware)), displayWidth = 1264, displayHeight = 1680,
        pageButtons = true,
    )
}

