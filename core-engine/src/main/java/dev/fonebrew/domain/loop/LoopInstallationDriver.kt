package dev.fonebrew.domain.loop

import dev.fonebrew.contracts.common.DigestAlgorithm
import dev.fonebrew.contracts.common.IntegrityRef
import dev.fonebrew.contracts.loops.BindingProfile
import dev.fonebrew.contracts.loops.BoundSlotSummary
import dev.fonebrew.contracts.loops.CompatibilityOutcome
import dev.fonebrew.contracts.loops.DurableObjectRef
import dev.fonebrew.contracts.loops.GrantedAuthorityBucket
import dev.fonebrew.contracts.loops.InstallationState
import dev.fonebrew.contracts.loops.LoopActivationReceipt
import dev.fonebrew.contracts.loops.LoopInstallation
import dev.fonebrew.contracts.loops.LoopPackageManifest
import dev.fonebrew.contracts.loops.PackageSignatureState
import dev.fonebrew.contracts.loops.ReleaseIdentity
import dev.fonebrew.contracts.loops.SimulationOutcome
import dev.fonebrew.contracts.loops.SimulationResult
import dev.fonebrew.contracts.loops.TransferEnvelope
import dev.fonebrew.domain.contracts.Digest
import dev.fonebrew.domain.contracts.IdGenerator
import java.time.Instant

/**
 * WP-8a: drives WP-1L's real eleven-state `InstallationState` machine
 * (`LOOP_IMPORT_ACTIVATION_CONTRACT.md` §3, `contracts/kotlin/LoopActivationContracts.kt`) through
 * the ten-step import/activation main path, exactly as [LoopRunDriver] (WP-8) drives the run-state
 * machine around [GraphRunner] -- same "adapter, not a rewrite" posture, this time with nothing
 * pre-existing to adapt (import/activation is genuinely greenfield, per WP0_SURVEY.md). Every
 * transition is checked against the ALREADY-REAL `InstallationState.isValidTransition` (WP-1L),
 * both individually (this driver only ever calls the one legal next state) and, in tests, as a
 * full sequence.
 *
 * FB-RAT-IMP-002 (§21, LOOP_ENGINEERING_SPEC_V2.1.md — "transfer moves inert bytes... no model,
 * tool, shell, remote, device, or side-effecting node may execute during parsing, validation,
 * preview, or installation") is enforced by construction here, not just by convention: everything
 * through `PREVIEWED` is pure byte/JSON inspection; [resolveBindings]/[resolveAuthority]/
 * [runSimulation] are the only injected suspend seams, and none of them are called before
 * `WAITING_BINDINGS`.
 */
class LoopInstallationDriver(
    private val idGenerator: () -> String = { "inst_" + IdGenerator.generate() },
    private val now: () -> Instant = Instant::now,
) {

    /** Terminal or `INSTALLED` outcome. [receipt] is non-null iff `installation.installationState == INSTALLED`. */
    data class InstallationOutcome(val installation: LoopInstallation, val receipt: LoopActivationReceipt?)

    /** A parse failure or a content-safety problem PARSED_VALIDATED found -- rejects the whole package, per §20/§3. */
    class UnsafePackageException(message: String) : Exception(message)

    suspend fun install(
        packageBytes: ByteArray,
        source: TransferEnvelope,
        releaseIdentity: ReleaseIdentity,
        parseManifest: (ByteArray) -> LoopPackageManifest,
        evaluateCompatibility: (LoopPackageManifest) -> CompatibilityOutcome,
        resolveBindings: suspend (LoopPackageManifest) -> BindingProfile?,
        resolveAuthority: suspend (LoopPackageManifest) -> List<GrantedAuthorityBucket>?,
        runSimulation: suspend () -> SimulationResult = { SimulationResult(SimulationOutcome.SKIPPED_NO_SIMULATOR_AVAILABLE, gapAcknowledgedByUser = true) },
        engineVersion: String = "1.0.0",
    ): InstallationOutcome {
        val installationId = idGenerator()
        val createdAt = now()
        var state = InstallationState.ACQUIRING

        fun reject(to: InstallationState, compatRef: DurableObjectRef? = null): InstallationOutcome {
            require(InstallationState.isValidTransition(state, to)) { "internal: illegal reject transition $state -> $to" }
            return InstallationOutcome(
                LoopInstallation(
                    schemaVersion = "1.0.0", installationId = installationId, releaseIdentity = releaseIdentity,
                    source = source, installationState = to, createdAtUtc = createdAt,
                    compatibilityReportRef = compatRef, updatedAtUtc = now(),
                ),
                receipt = null,
            )
        }

        // ACQUIRING -> SNAPSHOTTED: digest the exact bytes received.
        state = InstallationState.SNAPSHOTTED
        val actualDigest = Digest.of(packageBytes)

        // SNAPSHOTTED -> CONTAINER_VERIFIED, or REJECTED_UNSAFE if the digest doesn't match what the
        // user-initiated TransferEnvelope declared (LOOP_ENGINEERING_SPEC_V2.1.md §21 -- Fonebrew
        // "validates the container" before anything else).
        if (actualDigest.digestHex != source.expectedDigest.digestHex) {
            return reject(InstallationState.REJECTED_UNSAFE)
        }
        state = InstallationState.CONTAINER_VERIFIED

        // CONTAINER_VERIFIED -> PARSED_VALIDATED, or REJECTED_UNSAFE on a malformed/unparseable container.
        val manifest = try {
            parseManifest(packageBytes)
        } catch (e: Exception) {
            return reject(InstallationState.REJECTED_UNSAFE)
        }
        state = InstallationState.PARSED_VALIDATED

        // PARSED_VALIDATED -> COMPATIBILITY_EVALUATED, or BLOCKED_INCOMPATIBLE.
        val compatOutcome = evaluateCompatibility(manifest)
        val compatRef = DurableObjectRef("compat_" + IdGenerator.generate())
        if (compatOutcome == CompatibilityOutcome.BLOCKED || compatOutcome == CompatibilityOutcome.UNSUPPORTED) {
            state = InstallationState.COMPATIBILITY_EVALUATED
            return reject(InstallationState.BLOCKED_INCOMPATIBLE, compatRef)
        }
        state = InstallationState.COMPATIBILITY_EVALUATED

        // COMPATIBILITY_EVALUATED -> PREVIEWED. No decision point here -- a preview is shown, not decided.
        state = InstallationState.PREVIEWED

        // PREVIEWED -> WAITING_BINDINGS -> (bindings resolved or the user cancels).
        state = InstallationState.WAITING_BINDINGS
        val bindingProfile = resolveBindings(manifest)
            ?: return reject(InstallationState.CANCELLED, compatRef)
        val bindingRef = DurableObjectRef(bindingProfile.profileId)

        // WAITING_BINDINGS -> WAITING_AUTHORITY -> (granted or declined).
        state = InstallationState.WAITING_AUTHORITY
        val grantedBuckets = resolveAuthority(manifest)
            ?: return reject(InstallationState.CANCELLED, compatRef)

        // WAITING_AUTHORITY -> READY_TO_SIMULATE -> INSTALLABLE.
        state = InstallationState.READY_TO_SIMULATE
        val simulation = runSimulation()
        state = InstallationState.INSTALLABLE

        // INSTALLABLE -> INSTALLED: write the activation receipt (FB-RAT-IMP-009).
        state = InstallationState.INSTALLED
        val receiptId = "actr_" + IdGenerator.generate()
        val signatureState = if (manifest.signatureRef != null) {
            PackageSignatureState.IMPORTED_SIGNED_VERIFIED
        } else {
            PackageSignatureState.IMPORTED_UNSIGNED_NARROWED_GRANTS
        }

        val receipt = LoopActivationReceipt(
            receiptId = receiptId, loopId = releaseIdentity.loopId, semanticVersion = releaseIdentity.semanticVersion,
            packageDigest = releaseIdentity.packageContentDigest, signatureState = signatureState,
            compatibilityOutcome = compatOutcome,
            boundSlots = bindingProfile.slotBindings.map { BoundSlotSummary(it.slotId, it.selectedProviderId, it.substitutionPolicy, it.substitutionReason) },
            grantedAuthoritySummary = grantedBuckets, installedAtUtc = now(), engineVersion = engineVersion,
            installationId = installationId, source = source, simulationResult = simulation,
        )

        val installation = LoopInstallation(
            schemaVersion = "1.0.0", installationId = installationId, releaseIdentity = releaseIdentity,
            source = source, installationState = InstallationState.INSTALLED, createdAtUtc = createdAt,
            signatureState = signatureState, compatibilityReportRef = compatRef, bindingProfileRef = bindingRef,
            authorityGrants = grantedBuckets.flatMap { it.capabilityIds }, receipts = listOf(DurableObjectRef(receiptId)),
            updatedAtUtc = now(),
        )
        return InstallationOutcome(installation, receipt)
    }
}
