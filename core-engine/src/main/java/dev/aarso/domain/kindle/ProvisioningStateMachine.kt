package dev.aarso.domain.kindle

import java.time.Instant

enum class ProvisioningStage {
    IDENTIFY,
    BACKUP,
    VERIFY_PACKAGE,
    STAGE_FILES,
    USER_CONFIRMATION,
    PERFORM_STEP,
    VERIFY_STATE,
    RECEIPT,
}

enum class ProvisioningStageState { PENDING, RUNNING, SUCCEEDED, FAILED, BLOCKED }

data class ProvisioningCheckpoint(
    val stage: ProvisioningStage,
    val state: ProvisioningStageState,
    val updatedAtUtc: Instant,
    val detail: String? = null,
    val receiptDigest: String? = null,
)

data class KindleProvisioningRun(
    val id: String,
    val profileId: String,
    val firmware: KindleFirmwareVersion,
    val recipeId: String,
    val recipeVersion: Int,
    val checkpoints: List<ProvisioningCheckpoint>,
) {
    init {
        require(id.isNotBlank() && profileId.isNotBlank() && recipeId.isNotBlank()) { "Provisioning run identity is incomplete." }
        require(checkpoints.map { it.stage }.distinct().size == checkpoints.size) { "Provisioning stage is duplicated." }
    }

    val nextStage: ProvisioningStage?
        get() = ProvisioningStage.entries.firstOrNull { stage ->
            checkpoints.firstOrNull { it.stage == stage }?.state != ProvisioningStageState.SUCCEEDED
        }
}

object KindleProvisioningMachine {
    fun start(
        id: String,
        profile: KindleDeviceProfile,
        firmware: KindleFirmwareVersion,
        recipe: KindleProvisioningRecipe,
    ): KindleProvisioningRun {
        require(profile.id in recipe.supportedProfileIds && recipe.supportedFirmware.any { firmware in it }) {
            "Recipe does not support the identified Kindle/firmware."
        }
        return KindleProvisioningRun(id, profile.id, firmware, recipe.id, recipe.version, emptyList())
    }

    fun update(
        run: KindleProvisioningRun,
        stage: ProvisioningStage,
        state: ProvisioningStageState,
        at: Instant,
        detail: String? = null,
        receiptDigest: String? = null,
        userConfirmed: Boolean = false,
    ): KindleProvisioningRun {
        val expected = run.nextStage ?: error("Provisioning run is already complete.")
        require(stage == expected) { "Expected provisioning stage $expected, got $stage." }
        if (stage == ProvisioningStage.USER_CONFIRMATION && state == ProvisioningStageState.SUCCEEDED) {
            require(userConfirmed) { "User confirmation cannot be inferred or automated." }
        }
        if (state == ProvisioningStageState.SUCCEEDED && stage in setOf(
                ProvisioningStage.BACKUP,
                ProvisioningStage.VERIFY_PACKAGE,
                ProvisioningStage.STAGE_FILES,
                ProvisioningStage.VERIFY_STATE,
                ProvisioningStage.RECEIPT,
            )
        ) {
            require(ProvisioningPackage.SHA256.matches(receiptDigest.orEmpty())) {
                "$stage requires a SHA-256-bearing receipt."
            }
        }
        val checkpoint = ProvisioningCheckpoint(stage, state, at, detail, receiptDigest?.lowercase())
        return run.copy(checkpoints = run.checkpoints.filterNot { it.stage == stage } + checkpoint)
    }
}

