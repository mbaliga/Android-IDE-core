// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
package dev.fonebrew.domain.loop

import dev.fonebrew.contracts.loops.AuthorityRung
import dev.fonebrew.contracts.loops.BindingProfile
import dev.fonebrew.contracts.loops.BindingProvenance
import dev.fonebrew.contracts.loops.BindingRecord
import dev.fonebrew.contracts.loops.CapabilityRequest
import dev.fonebrew.contracts.loops.CompatibilityOutcome
import dev.fonebrew.contracts.loops.GrantedAuthorityBucket
import dev.fonebrew.contracts.loops.InstallationState
import dev.fonebrew.contracts.loops.LoopPackageManifest
import dev.fonebrew.contracts.loops.PackageSignatureState
import dev.fonebrew.contracts.loops.ReleaseIdentity
import dev.fonebrew.contracts.loops.SimulationOutcome
import dev.fonebrew.contracts.loops.SimulationResult
import dev.fonebrew.contracts.loops.SubstitutionPolicy
import dev.fonebrew.contracts.loops.TransferChannel
import dev.fonebrew.contracts.loops.TransferEnvelope
import dev.fonebrew.contracts.loops.ValidationReportFinding
import dev.fonebrew.domain.bpmn.BpmnGraph
import dev.fonebrew.domain.contracts.Digest
import java.time.Instant

/**
 * Reviewer-facing capability bucketing (`LOOP_IMPORT_ACTIVATION_CONTRACT.md` §6's seven-bucket
 * mapping onto [AuthorityRung]) plus this codebase's own copy of `capability-ids.v1.json`'s
 * authorityRung column, kept local rather than parsed from the registry file at runtime (no
 * asset-bundling path exists for `schemas/` today) — extend this table if the registry grows;
 * an unrecognized capability ID maps to `null` and is always treated as the strictest bucket
 * (Destructive) so an unknown request can never slip into a low-friction approval.
 */
object LoopCapabilityAuthority {

    val AUTHORITY_RUNG_BY_ID: Map<String, AuthorityRung> = mapOf(
        "fb.workspace.observe" to AuthorityRung.OBSERVE,
        "fb.repo.read" to AuthorityRung.READ,
        "fb.marketplace.import" to AuthorityRung.READ,
        "fb.repo.propose_change" to AuthorityRung.PROPOSE,
        "fb.device.usb_permission" to AuthorityRung.PROPOSE,
        "fb.repo.write_draft" to AuthorityRung.MODIFY_DRAFT,
        "fb.repo.commit" to AuthorityRung.EXECUTE_REVERSIBLE,
        "fb.exec.local_process" to AuthorityRung.EXECUTE_REVERSIBLE,
        "fb.device.serial_read" to AuthorityRung.EXECUTE_REVERSIBLE,
        "fb.model.local_inference" to AuthorityRung.EXECUTE_REVERSIBLE,
        "fb.repo.push_remote" to AuthorityRung.EXECUTE_EXTERNAL,
        "fb.exec.ssh_remote" to AuthorityRung.EXECUTE_EXTERNAL,
        "fb.ci.dispatch" to AuthorityRung.EXECUTE_EXTERNAL,
        "fb.network.egress" to AuthorityRung.EXECUTE_EXTERNAL,
        "fb.secret.use" to AuthorityRung.EXECUTE_EXTERNAL,
        "fb.model.cloud_inference" to AuthorityRung.EXECUTE_EXTERNAL,
        "fb.repo.history_rewrite" to AuthorityRung.EXECUTE_DESTRUCTIVE,
        "fb.device.flash" to AuthorityRung.EXECUTE_DESTRUCTIVE,
        "fb.marketplace.publish" to AuthorityRung.PUBLISH_OR_RELEASE,
        "fb.release.publish" to AuthorityRung.PUBLISH_OR_RELEASE,
    )

    /** §6's review-bucket table, verbatim. */
    fun bucketFor(rung: AuthorityRung): String = when (rung) {
        AuthorityRung.OBSERVE -> "Observe"
        AuthorityRung.READ -> "Read"
        AuthorityRung.PROPOSE -> "Propose"
        AuthorityRung.MODIFY_DRAFT, AuthorityRung.EXECUTE_REVERSIBLE -> "Write"
        AuthorityRung.EXECUTE_EXTERNAL -> "External"
        AuthorityRung.EXECUTE_DESTRUCTIVE -> "Destructive"
        AuthorityRung.PUBLISH_OR_RELEASE -> "Publish"
    }

    /** §7 (FB-RAT-PHN-009): the buckets whose presence requires the dangerous-first-run simulation gate. */
    val SIMULATION_GATED_BUCKETS = setOf("Write", "External", "Destructive", "Publish")
}

/** One capability request as shown on the authority-review screen. `authorityRung` is null for
 *  an unrecognized capability ID — the presenter always treats that as [AuthorityRung.EXECUTE_DESTRUCTIVE]
 *  for bucketing (never lets an unknown ID default to a low-friction bucket). */
data class CapabilityReviewItem(val request: CapabilityRequest, val authorityRung: AuthorityRung, val bucket: String, val recognized: Boolean)

/** One model-binding slot the import needs a local model bound to before install (§5) — this
 *  app's real binding placeholder: a loop node's requested model ID from `definition.json`. */
data class ModelBindingSlot(val slotId: String, val requestedModelId: String?, val nodeIds: List<String>)

/** Everything the review screen needs to show before the user decides anything (§4 PREVIEWED, §6 WAITING_AUTHORITY). */
data class LoopImportPreview(
    val manifest: LoopPackageManifest,
    val graph: BpmnGraph,
    val objective: String,
    val readme: String?,
    val licenseText: String?,
    val authorName: String?,
    val findings: List<ValidationReportFinding>,
    val capabilityItems: List<CapabilityReviewItem>,
    val modelBindingSlots: List<ModelBindingSlot>,
    val requiresSimulationAcknowledgement: Boolean,
    val signaturePosture: String,
)

/** The user's WAITING_AUTHORITY decision (§6) plus the always-required simulation-gap acknowledgement (§7) —
 *  one combined review screen, per this app's honest reality that no per-node simulator exists yet. */
data class AuthorityDecision(val approvedCapabilityIds: Set<String>, val simulationGapAcknowledged: Boolean)

sealed interface LoopImportOutcome {
    data class Installed(
        val installation: dev.fonebrew.contracts.loops.LoopInstallation,
        val receipt: dev.fonebrew.contracts.loops.LoopActivationReceipt,
        val graph: BpmnGraph,
        val objective: String,
        val importProvenanceExt: Map<String, String>,
    ) : LoopImportOutcome

    data class Rejected(val reason: String, val findings: List<ValidationReportFinding>) : LoopImportOutcome
    data class Cancelled(val installation: dev.fonebrew.contracts.loops.LoopInstallation?) : LoopImportOutcome
}

object LoopImportReview {

    fun buildPreview(decoded: LoopPackageCodec.DecodedLoopPackage, findings: List<ValidationReportFinding>): LoopImportPreview {
        val capItems = decoded.manifest.capabilityRequests.map { req ->
            val rung = LoopCapabilityAuthority.AUTHORITY_RUNG_BY_ID[req.capabilityId]
            val recognized = rung != null
            val effectiveRung = rung ?: AuthorityRung.EXECUTE_DESTRUCTIVE
            CapabilityReviewItem(req, effectiveRung, LoopCapabilityAuthority.bucketFor(effectiveRung), recognized)
        }
        val slots = modelBindingSlots(decoded.graph)
        // Honest reality (see LoopInstallationDriver's default runSimulation): no per-node
        // simulator exists in this codebase, so SKIPPED_NO_SIMULATOR_AVAILABLE is unconditional
        // and §7's acknowledgement gate is unconditional too, not just for the four gated buckets.
        return LoopImportPreview(
            manifest = decoded.manifest, graph = decoded.graph, objective = decoded.objective,
            readme = decoded.readme, licenseText = decoded.licenseText, authorName = decoded.authorName,
            findings = findings, capabilityItems = capItems, modelBindingSlots = slots,
            requiresSimulationAcknowledgement = true,
            signaturePosture = if (decoded.manifest.signatureRef != null) "signed (unverifiable — no publisher-key trust store in this build)" else "unsigned",
        )
    }

    /** One slot per distinct requested model id, plus a `model.default` slot for every node with
     *  no explicit model — always at least one slot, satisfying [BindingProfile]'s non-empty
     *  `slotBindings` requirement without inventing a placeholder this app has no other model of. */
    fun modelBindingSlots(graph: BpmnGraph): List<ModelBindingSlot> {
        val byModel = graph.nodes.filter { !it.kind.name.contains("EVENT") && !it.kind.name.contains("GATEWAY") }
            .groupBy { it.ext["model"] }
        return byModel.entries.sortedBy { it.key ?: "" }.map { (modelId, nodes) ->
            val slotId = if (modelId != null) "model.$modelId" else "model.default"
            ModelBindingSlot(slotId, modelId, nodes.map { it.id })
        }
    }
}

/**
 * Drives [LoopInstallationDriver] through its eleven states for the `PACKAGE_IMPORT` profile,
 * turning its suspend-callback seams into two user decisions: which local model backs each
 * binding slot (§5), and which requested capabilities are granted plus the simulation-gap
 * acknowledgement (§6-§7, one combined review since this app has no working simulator). Pure
 * Kotlin — the SAF byte read and the actual review dialogs are the Android/Compose layer that
 * calls this (owner-verified; not exercised here).
 */
class LoopImportPresenter(private val driver: LoopInstallationDriver = LoopInstallationDriver()) {

    suspend fun run(
        packageBytes: ByteArray,
        sourceDescription: String,
        chooseModelBinding: suspend (slot: ModelBindingSlot) -> String?,
        decideAuthority: suspend (preview: LoopImportPreview) -> AuthorityDecision?,
        engineVersion: String = "1.0.0",
    ): LoopImportOutcome {
        val decoded = try {
            LoopPackageCodec.decode(packageBytes)
        } catch (e: LoopPackageCodec.UnsafePackageException) {
            return LoopImportOutcome.Rejected(e.message ?: "Package rejected.", e.findings)
        }
        val findings = LoopPackageCodec.scan(packageBytes)
        val preview = LoopImportReview.buildPreview(decoded, findings)

        val actualDigest = Digest.of(packageBytes)
        val envelope = TransferEnvelope(
            transferChannel = TransferChannel.FILE, expectedMediaType = LoopPackageCodec.PACKAGE_MEDIA_TYPE,
            expectedDigest = actualDigest, sourceDescription = sourceDescription,
        )
        val releaseIdentity = ReleaseIdentity(decoded.manifest.packageIdentity.loopId, decoded.manifest.packageIdentity.semanticVersion, actualDigest)

        var simulationAck = false

        val outcome = driver.install(
            packageBytes = packageBytes, source = envelope, releaseIdentity = releaseIdentity,
            parseManifest = { LoopPackageCodec.decode(it).manifest },
            evaluateCompatibility = { evaluateCompatibility(it.compatibilityDeclaration.engineVersionRange, engineVersion) },
            resolveBindings = { resolveBindings(preview.modelBindingSlots, actualDigest, chooseModelBinding) },
            resolveAuthority = { manifest ->
                val decision = decideAuthority(preview)
                if (decision == null || !decision.simulationGapAcknowledged) {
                    null
                } else {
                    simulationAck = true
                    resolveAuthorityGrants(manifest.capabilityRequests, decision.approvedCapabilityIds)
                }
            },
            runSimulation = { SimulationResult(SimulationOutcome.SKIPPED_NO_SIMULATOR_AVAILABLE, gapAcknowledgedByUser = simulationAck) },
            engineVersion = engineVersion,
        )

        val installation = outcome.installation
        val receipt = outcome.receipt
        return if (receipt != null) {
            LoopImportOutcome.Installed(
                installation, receipt, decoded.graph, decoded.objective,
                importProvenanceExt(decoded, releaseIdentity, receipt.signatureState),
            )
        } else if (installation.installationState == InstallationState.CANCELLED) {
            LoopImportOutcome.Cancelled(installation)
        } else {
            LoopImportOutcome.Rejected("Import stopped at ${installation.installationState} — see the validation findings.", findings)
        }
    }

    /** Very small `>=A.B.C <D.E.F` range check — the only shape every fixture and this codec's
     *  own exports use (`ENGINE_VERSION_RANGE`); not a general SemVer-range parser. */
    private fun evaluateCompatibility(range: String, engineVersion: String): CompatibilityOutcome {
        val parts = Regex("^>=(\\d+\\.\\d+\\.\\d+)\\s*<(\\d+\\.\\d+\\.\\d+)$").find(range.trim())
            ?: return CompatibilityOutcome.UNSUPPORTED
        val (min, max) = parts.destructured
        val engine = engineVersion.split('.').map { it.toInt() }
        fun cmp(a: List<Int>, b: List<Int>) = (0..2).map { a.getOrElse(it) { 0 } - b.getOrElse(it) { 0 } }.firstOrNull { it != 0 } ?: 0
        val minV = min.split('.').map { it.toInt() }
        val maxV = max.split('.').map { it.toInt() }
        return if (cmp(engine, minV) >= 0 && cmp(engine, maxV) < 0) CompatibilityOutcome.COMPATIBLE else CompatibilityOutcome.BLOCKED
    }

    private suspend fun resolveBindings(
        slots: List<ModelBindingSlot>, digest: dev.fonebrew.contracts.common.IntegrityRef,
        chooseModelBinding: suspend (ModelBindingSlot) -> String?,
    ): BindingProfile? {
        val records = mutableListOf<BindingRecord>()
        for (slot in slots) {
            val chosen = chooseModelBinding(slot) ?: return null
            records += BindingRecord(
                slotId = slot.slotId, selectedProviderId = chosen,
                substitutionPolicy = if (chosen == slot.requestedModelId) SubstitutionPolicy.EXACT_ONLY else SubstitutionPolicy.USER_APPROVED,
                provenance = BindingProvenance.LOCAL, targetType = "model",
                substitutionReason = if (chosen != slot.requestedModelId) "requested model '${slot.requestedModelId}' is not installed on this device; user substituted '$chosen'" else null,
            )
        }
        return BindingProfile(
            schemaVersion = "1.0.0", profileId = "bind_" + java.util.UUID.randomUUID().toString().take(12),
            packageDigest = digest, slotBindings = records, resolvedAtUtc = Instant.now(),
        )
    }

    /** §6 (FB-RAT-IMP-005): denying ANY requested capability cancels the install rather than
     *  activating with a silently narrowed grant — this manifest shape has no per-request
     *  "optional" flag, so every request is treated as required. */
    private fun resolveAuthorityGrants(requests: List<CapabilityRequest>, approvedIds: Set<String>): List<GrantedAuthorityBucket>? {
        if (requests.any { it.capabilityId !in approvedIds }) return null
        if (requests.isEmpty()) return emptyList()
        return requests.groupBy { LoopCapabilityAuthority.AUTHORITY_RUNG_BY_ID[it.capabilityId] ?: AuthorityRung.EXECUTE_DESTRUCTIVE }
            .map { (rung, reqs) -> GrantedAuthorityBucket(rung, reqs.map { it.capabilityId }) }
    }

    private fun importProvenanceExt(
        decoded: LoopPackageCodec.DecodedLoopPackage, releaseIdentity: ReleaseIdentity, signatureState: PackageSignatureState,
    ): Map<String, String> = buildMap {
        put("importedFrom", "loop-package")
        put("importedLoopId", releaseIdentity.loopId)
        put("importedSemanticVersion", releaseIdentity.semanticVersion)
        put("importedPackageDigest", releaseIdentity.packageContentDigest.digestHex)
        put("importedSignatureState", signatureState.name)
        put("importedOn", Instant.now().toString())
        decoded.authorName?.let { put("importedAuthor", it) }
        decoded.manifest.license?.let { put("importedLicense", it) }
    }
}
