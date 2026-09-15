package dev.fonebrew.ui.loops

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.aarso.hyle.cells.HyleButton
import dev.aarso.hyle.cells.HyleCard
import dev.aarso.hyle.cells.HyleChip
import dev.aarso.hyle.cells.HyleDropdownField
import dev.aarso.hyle.cells.HyleFocusLens
import dev.aarso.hyle.cells.HyleLensActions
import dev.aarso.hyle.cells.HyleLensHeading
import dev.aarso.hyle.theme.LocalHyleColors
import dev.fonebrew.contracts.loops.ValidationReportFinding
import dev.fonebrew.domain.bpmn.BpmnGraph
import dev.fonebrew.domain.loop.AuthorityDecision
import dev.fonebrew.domain.loop.LoopImportOutcome
import dev.fonebrew.domain.loop.LoopImportPresenter
import dev.fonebrew.domain.loop.LoopImportPreview
import dev.fonebrew.domain.loop.LoopImportReview
import dev.fonebrew.domain.loop.LoopPackageCodec
import dev.fonebrew.domain.model.ModelSpec
import kotlinx.coroutines.launch

private sealed interface ImportStage {
    data object PickingFile : ImportStage
    data class Reviewing(val bytes: ByteArray, val preview: LoopImportPreview) : ImportStage
    data object Installing : ImportStage
    data class Rejected(val reason: String, val findings: List<ValidationReportFinding>) : ImportStage
}

/**
 * "Import package…" (LoopRoom): SAF `OPEN_DOCUMENT` → [LoopPackageCodec] decode + safety scan →
 * an authority-review screen showing the manifest's identity/license/provenance AND every
 * capability request as an explicit approve/deny, plus the always-present no-simulator
 * acknowledgement (`LoopInstallationDriver`'s `READY_TO_SIMULATE` gate, FB-RAT-PHN-009) — never
 * auto-activated. Model binding slots (this app's real binding-placeholder equivalent — a
 * node's requested model id, `LoopImportReview.modelBindingSlots`) are resolved to a locally
 * available model before Install enables. Rejection (unsafe/incompatible) short-circuits before
 * this screen ever renders — [LoopPackageCodec.decode] runs first.
 *
 * [initialBytes] (asoc-reachability audit item 5, 2026-09-15): LoopRoom's "Templates" browse
 * sheet ([LoopTemplatesDialog]) hands a bundled asset's bytes straight in, skipping only the SAF
 * picker — decode, scan, the full authority-review screen and [LoopImportPresenter] itself are
 * identical either way, so a bundled template gets exactly the same review a user-picked file
 * does, never a trust shortcut around it.
 */
@Composable
fun ImportLoopPackageDialog(
    localModels: List<ModelSpec>,
    onDismiss: () -> Unit,
    onImported: (graph: BpmnGraph, objective: String, provenanceExt: Map<String, String>) -> Unit,
    initialBytes: ByteArray? = null,
    /** [dev.fonebrew.contracts.loops.TransferEnvelope.sourceDescription] — shown in the
     *  installed loop's own import provenance. */
    sourceDescription: String = "SAF document",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember {
        mutableStateOf<ImportStage>(
            if (initialBytes != null) {
                try {
                    val decoded = LoopPackageCodec.decode(initialBytes)
                    val findings = LoopPackageCodec.scan(initialBytes)
                    ImportStage.Reviewing(initialBytes, LoopImportReview.buildPreview(decoded, findings))
                } catch (e: LoopPackageCodec.UnsafePackageException) {
                    ImportStage.Rejected(e.message ?: "Package rejected.", e.findings)
                }
            } else {
                ImportStage.PickingFile
            },
        )
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { onDismiss(); return@rememberLauncherForActivityResult }
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes == null) { stage = ImportStage.Rejected("Couldn't read that file.", emptyList()); return@rememberLauncherForActivityResult }
        try {
            val decoded = LoopPackageCodec.decode(bytes)
            val findings = LoopPackageCodec.scan(bytes)
            stage = ImportStage.Reviewing(bytes, LoopImportReview.buildPreview(decoded, findings))
        } catch (e: LoopPackageCodec.UnsafePackageException) {
            stage = ImportStage.Rejected(e.message ?: "Package rejected.", e.findings)
        }
    }
    // A Templates pick already has its bytes — only the SAF flow needs the picker launched.
    LaunchedEffect(Unit) { if (initialBytes == null) picker.launch(arrayOf("*/*")) }

    when (val s = stage) {
        is ImportStage.PickingFile -> {}
        is ImportStage.Installing -> BusyLens("Installing…")
        is ImportStage.Rejected -> RejectedPackageLens(s.reason, s.findings, onDismiss)
        is ImportStage.Reviewing -> AuthorityReviewLens(
            preview = s.preview, localModels = localModels, onCancel = onDismiss,
            onApprove = { bindings, approvedCapIds ->
                stage = ImportStage.Installing
                scope.launch {
                    val outcome = LoopImportPresenter().run(
                        packageBytes = s.bytes, sourceDescription = sourceDescription,
                        chooseModelBinding = { slot -> bindings[slot.slotId] },
                        decideAuthority = { AuthorityDecision(approvedCapIds, simulationGapAcknowledged = true) },
                    )
                    when (outcome) {
                        is LoopImportOutcome.Installed -> onImported(outcome.graph, outcome.objective, outcome.importProvenanceExt)
                        is LoopImportOutcome.Rejected -> stage = ImportStage.Rejected(outcome.reason, outcome.findings)
                        is LoopImportOutcome.Cancelled -> onDismiss()
                    }
                }
            },
        )
    }
}

@Composable
private fun BusyLens(label: String) {
    HyleFocusLens(visible = true, onDismiss = null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RejectedPackageLens(reason: String, findings: List<ValidationReportFinding>, onDismiss: () -> Unit) {
    HyleFocusLens(visible = true, onDismiss = onDismiss) {
        HyleLensHeading("Can't import this package", reason)
        Column(
            Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (f in findings) {
                Text("${f.code} — ${f.message}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HyleLensActions { HyleButton("Close", onClick = onDismiss) }
    }
}

/**
 * The authority-review screen (`LOOP_IMPORT_ACTIVATION_CONTRACT.md` §4 PREVIEWED, §6
 * WAITING_AUTHORITY, §7 simulation gate) — identity/license/provenance, every capability
 * request with an individual approve toggle (default denied: "Allow all" is not a primary
 * action, §6), model bindings, and the honest no-simulator acknowledgement this build always
 * needs (no per-node simulator exists here — [LoopImportPreview.requiresSimulationAcknowledgement]
 * is unconditional, not just for the Write/External/Destructive/Publish buckets §7 names).
 */
@Composable
private fun AuthorityReviewLens(
    preview: LoopImportPreview,
    localModels: List<ModelSpec>,
    onCancel: () -> Unit,
    onApprove: (bindings: Map<String, String>, approvedCapabilityIds: Set<String>) -> Unit,
) {
    val m = preview.manifest
    var approved by remember { mutableStateOf(setOf<String>()) }
    var bindings by remember {
        mutableStateOf(
            preview.modelBindingSlots.associate { slot ->
                val exact = localModels.firstOrNull { it.id == slot.requestedModelId }
                slot.slotId to (exact?.id ?: localModels.firstOrNull()?.id ?: "")
            },
        )
    }
    var simulationAck by remember { mutableStateOf(false) }
    val allBound = preview.modelBindingSlots.all { bindings[it.slotId]?.isNotBlank() == true }
    val allApproved = m.capabilityRequests.all { it.capabilityId in approved }
    val canInstall = allBound && allApproved && simulationAck && localModels.isNotEmpty()

    HyleFocusLens(visible = true, onDismiss = onCancel) {
        HyleLensHeading("Review before install", "Nothing runs until you approve every item below.")
        Column(
            Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HyleCard {
                Text("${m.packageIdentity.loopId} · v${m.packageIdentity.semanticVersion}", style = MaterialTheme.typography.titleSmall)
                Text("License: ${m.license ?: "not declared"}", style = MaterialTheme.typography.labelSmall)
                Text("Signature: ${preview.signaturePosture}", style = MaterialTheme.typography.labelSmall, color = LocalHyleColors.current.violet)
                m.provenance?.producer?.let { Text("Producer: ${it.name} ${it.version}", style = MaterialTheme.typography.labelSmall) }
                m.provenance?.createdAtUtc?.let { Text("Exported: $it", style = MaterialTheme.typography.labelSmall) }
                preview.authorName?.let { Text("Author: $it", style = MaterialTheme.typography.labelSmall) }
            }

            if (preview.findings.isNotEmpty()) {
                Text("Scan notes (non-blocking)", style = MaterialTheme.typography.labelMedium)
                for (f in preview.findings) {
                    Text("· ${f.message}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (m.capabilityRequests.isNotEmpty()) {
                Text("Capability requests — approve each individually", style = MaterialTheme.typography.labelMedium)
                for (item in preview.capabilityItems) {
                    HyleCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(item.request.capabilityId, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${item.bucket}${if (!item.recognized) " · unrecognized capability" else ""}",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                item.request.justification?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                            }
                            HyleChip(
                                item.request.capabilityId in approved,
                                {
                                    approved = if (item.request.capabilityId in approved) approved - item.request.capabilityId else approved + item.request.capabilityId
                                },
                                if (item.request.capabilityId in approved) "Approved" else "Deny",
                            )
                        }
                    }
                }
            } else {
                Text("Requests no capabilities.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (preview.modelBindingSlots.isNotEmpty()) {
                Text("Model bindings", style = MaterialTheme.typography.labelMedium)
                if (localModels.isEmpty()) {
                    Text("No local model available — download one first (Models room).", style = MaterialTheme.typography.labelSmall, color = LocalHyleColors.current.violet)
                }
                for (slot in preview.modelBindingSlots) {
                    val options = localModels.map { (if (it.isOnDevice) "⌂ " else "☁ ") + it.displayName }
                    val currentId = bindings[slot.slotId]
                    val currentIndex = localModels.indexOfFirst { it.id == currentId }
                    HyleDropdownField(
                        value = if (currentIndex >= 0) options[currentIndex] else "Choose a model",
                        options = options,
                        onSelect = { idx -> bindings = bindings + (slot.slotId to localModels[idx].id) },
                        label = slot.requestedModelId?.let { "${slot.slotId} (requested: $it)" } ?: slot.slotId,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            HyleCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "No simulator is available to preview this loop's behavior before its first real run.",
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f),
                    )
                    HyleChip(simulationAck, { simulationAck = !simulationAck }, if (simulationAck) "Acknowledged" else "Acknowledge")
                }
            }
        }
        HyleLensActions {
            HyleButton("Cancel", secondary = true, onClick = onCancel)
            Spacer(Modifier.width(8.dp))
            HyleButton("Approve & Install", enabled = canInstall, onClick = { onApprove(bindings, approved) })
        }
    }
}
