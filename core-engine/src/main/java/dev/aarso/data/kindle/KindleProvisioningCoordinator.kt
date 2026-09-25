package dev.aarso.data.kindle

import android.net.Uri
import dev.aarso.domain.kindle.KindleDeviceProfile
import dev.aarso.domain.kindle.KindleFirmwareVersion
import dev.aarso.domain.kindle.KindleProvisioningMachine
import dev.aarso.domain.kindle.KindleProvisioningRecipe
import dev.aarso.domain.kindle.KindleProvisioningRun
import dev.aarso.domain.kindle.ProvisioningStage
import dev.aarso.domain.kindle.ProvisioningStageState
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

class KindleProvisioningCoordinator(
    private val fylz: FylzOperationClient,
    private val downloads: ApprovedPackageDownloader,
    private val store: KindleProvisioningStore,
    private val now: () -> Instant = Instant::now,
) {
    fun start(
        profile: KindleDeviceProfile,
        firmware: KindleFirmwareVersion,
        recipe: KindleProvisioningRecipe,
    ): KindleProvisioningRun = KindleProvisioningMachine.start(
        UUID.randomUUID().toString(), profile, firmware, recipe,
    ).let { advance(it, ProvisioningStage.IDENTIFY, "${profile.model} · $firmware", null) }

    fun resume(): KindleProvisioningRun? = store.load()

    suspend fun backup(
        run: KindleProvisioningRun,
        kindleTree: Uri,
        backupDestinationTree: Uri,
        onProgress: (FylzOperationReceipt) -> Unit = {},
    ): KindleProvisioningRun {
        require(run.nextStage == ProvisioningStage.BACKUP)
        val id = fylz.execute(FylzOperationKind.RECURSIVE_BACKUP, kindleTree, backupDestinationTree)
        val receipt = fylz.awaitTerminal(id, onProgress = onProgress).requireSuccess()
        return advance(run, ProvisioningStage.BACKUP, receipt.outputUri, requireNotNull(receipt.sha256))
    }

    suspend fun downloadAndVerify(
        run: KindleProvisioningRun,
        recipe: KindleProvisioningRecipe,
        onProgress: (FylzOperationReceipt) -> Unit = {},
    ): Pair<KindleProvisioningRun, Map<String, Uri>> {
        require(run.nextStage == ProvisioningStage.VERIFY_PACKAGE)
        val handles = linkedMapOf<String, Uri>()
        val digests = mutableListOf<String>()
        recipe.packages.forEach { item ->
            val handle = downloads.await(downloads.enqueue(item))
            val receipt = fylz.awaitTerminal(
                fylz.execute(FylzOperationKind.SHA256, handle), onProgress = onProgress,
            ).requireSuccess()
            check(receipt.sha256.equals(item.sha256, ignoreCase = true)) {
                "Downloaded package ${item.id} did not match its manifest SHA-256."
            }
            handles[item.id] = handle
            digests += item.sha256.lowercase()
        }
        store.savePackageHandles(run.id, handles)
        return advance(
            run, ProvisioningStage.VERIFY_PACKAGE,
            "Verified ${handles.size} manifest package(s)", aggregate(digests),
        ) to handles
    }

    suspend fun stage(
        run: KindleProvisioningRun,
        recipe: KindleProvisioningRecipe,
        packages: Map<String, Uri> = store.packageHandles(run.id),
        kindleTree: Uri,
        onProgress: (FylzOperationReceipt) -> Unit = {},
    ): KindleProvisioningRun {
        require(run.nextStage == ProvisioningStage.STAGE_FILES)
        val receipts = recipe.instructions.mapNotNull { instruction ->
            val kind = when (instruction.kind) {
                dev.aarso.domain.kindle.ProvisioningInstructionKind.COPY -> FylzOperationKind.STAGE_PACKAGE
                dev.aarso.domain.kindle.ProvisioningInstructionKind.EXTRACT_PACKAGE -> FylzOperationKind.EXTRACT_PACKAGE
                else -> return@mapNotNull null
            }
            val item = recipe.packages.single { it.id == instruction.sourcePackageId }
            val handle = requireNotNull(packages[item.id]) { "Package handle was not retained." }
            fylz.awaitTerminal(
                fylz.execute(
                    kind = kind,
                    sourceUri = handle,
                    destinationTreeUri = kindleTree,
                    destinationRelativePath = instruction.destinationRelativePath,
                    expectedSha256 = item.sha256,
                ),
                onProgress = onProgress,
            ).requireSuccess()
        }
        require(receipts.isNotEmpty()) { "Recipe has no Fylz staging instruction." }
        return advance(
            run, ProvisioningStage.STAGE_FILES,
            "Staged through Fylz to the selected Kindle capability", aggregate(receipts.map { requireNotNull(it.sha256) }),
        )
    }

    fun confirm(run: KindleProvisioningRun): KindleProvisioningRun {
        require(run.nextStage == ProvisioningStage.USER_CONFIRMATION)
        val updated = KindleProvisioningMachine.update(
            run, ProvisioningStage.USER_CONFIRMATION, ProvisioningStageState.SUCCEEDED,
            now(), detail = "User explicitly confirmed device-side steps", userConfirmed = true,
        )
        store.save(updated)
        return updated
    }

    fun markPerformed(run: KindleProvisioningRun, detail: String): KindleProvisioningRun =
        advance(run, ProvisioningStage.PERFORM_STEP, detail, null)

    fun verifyState(run: KindleProvisioningRun, observedStates: Set<String>): KindleProvisioningRun {
        require(observedStates.isNotEmpty())
        return advance(
            run, ProvisioningStage.VERIFY_STATE,
            observedStates.sorted().joinToString(), aggregate(observedStates.sorted()),
        )
    }

    fun finish(run: KindleProvisioningRun): KindleProvisioningRun =
        advance(run, ProvisioningStage.RECEIPT, "Provisioning receipt complete", digest(run))

    private fun advance(
        run: KindleProvisioningRun,
        stage: ProvisioningStage,
        detail: String?,
        receiptDigest: String?,
    ): KindleProvisioningRun = KindleProvisioningMachine.update(
        run, stage, ProvisioningStageState.SUCCEEDED, now(), detail, receiptDigest,
    ).also(store::save)

    private fun FylzOperationReceipt.requireSuccess(): FylzOperationReceipt {
        check(state == FylzOperationState.SUCCEEDED) { "Fylz operation $requestId ended $state (${errorCode ?: "no detail"})." }
        return this
    }

    private fun digest(run: KindleProvisioningRun): String = aggregate(run.checkpoints.map {
        "${it.stage}:${it.state}:${it.receiptDigest.orEmpty()}"
    })

    private fun aggregate(values: List<String>): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("\n").toByteArray()).joinToString("") { "%02x".format(it) }
}
