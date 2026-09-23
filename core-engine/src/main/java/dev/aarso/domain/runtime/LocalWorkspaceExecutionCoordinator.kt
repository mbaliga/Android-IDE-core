package dev.aarso.domain.runtime

import android.net.Uri
import dev.aarso.contracts.execution.AuthorityGrantRef
import dev.aarso.contracts.execution.ExecutionBudget
import dev.aarso.contracts.execution.ExecutionEvent
import dev.aarso.contracts.execution.ExecutionReceipt
import dev.aarso.contracts.execution.ExecutionRequest
import dev.aarso.contracts.execution.ExpectedOutput
import dev.aarso.contracts.execution.FgsType
import dev.aarso.contracts.execution.OperationClass
import dev.aarso.contracts.execution.RequestEnvironment
import dev.aarso.contracts.execution.SessionReauthorization
import dev.aarso.contracts.execution.TypedOperation
import dev.aarso.data.runtime.SafTermuxWorkspaceMirror
import dev.aarso.domain.execution.TermuxExecutionProvider
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first

/**
 * One explicit local execution request over a Fylz/SAF workspace.
 *
 * The caller must already hold an authority grant. This coordinator never mints or escalates one.
 */
data class LocalWorkspaceExecutionJob(
    val id: String,
    val workspaceTreeUri: Uri,
    val workspaceId: String,
    val workingRevision: String?,
    val executable: String,
    val args: List<String> = emptyList(),
    val requiredCapabilities: Set<RuntimeCapability>,
    val expectedArtifactPaths: List<String> = emptyList(),
    val authorityGrant: AuthorityGrantRef,
    val wallClockSeconds: Long = 300,
) {
    init {
        require(id.isNotBlank()) { "job id must be non-blank" }
        require(workspaceId.isNotBlank()) { "workspace id must be non-blank" }
        require(executable.isNotBlank()) { "executable must be non-blank" }
        require(wallClockSeconds >= 1) { "wallClockSeconds must be >= 1" }
    }
}

data class LocalWorkspaceExecutionOutcome(
    val runtime: RuntimeProviderProfile,
    val mirror: SafTermuxWorkspaceMirror.MirrorReceipt,
    val receipt: ExecutionReceipt,
    val artifactUris: Map<String, Uri>,
)

/**
 * End-to-end local execution spine:
 *
 * SAF/Fylz tree -> bounded Termux mirror -> receipt-bearing ExecutionProvider -> declared
 * artifacts copied back to SAF. No file-manager UI or mount logic lives here.
 */
class LocalWorkspaceExecutionCoordinator(
    private val broker: RuntimeBroker,
    private val mirror: SafTermuxWorkspaceMirror,
    private val termuxProvider: TermuxExecutionProvider,
) {
    suspend fun execute(job: LocalWorkspaceExecutionJob): LocalWorkspaceExecutionOutcome {
        val resolution = broker.resolve(
            RuntimeRequest(
                requiredCapabilities = job.requiredCapabilities,
                preferredKinds = listOf(
                    RuntimeKind.LINUX_USERSPACE,
                    RuntimeKind.JVM,
                    RuntimeKind.BROWSER,
                ),
                allowRemote = false,
            )
        )
        val matched = resolution as? RuntimeResolution.Matched
            ?: error("No ready on-device runtime satisfies: " +
                job.requiredCapabilities.joinToString { it.name.lowercase() })
        check(matched.profile.providerId == "termux-linux") {
            "Selected local runtime is not wired to this coordinator: " + matched.profile.providerId
        }

        val mirrored = mirror.materialize(
            treeUri = job.workspaceTreeUri,
            workspaceId = job.workspaceId,
        )

        val request = ExecutionRequest(
            id = job.id,
            targetId = "termux-local",
            operation = TypedOperation(
                operationClass = if (RuntimeCapability.GRADLE in job.requiredCapabilities) {
                    OperationClass.BUILD
                } else {
                    OperationClass.ONE_SHOT_COMMAND
                },
                command = job.executable,
                args = job.args,
                workingDirectory = mirrored.termuxPath,
            ),
            workingRevision = job.workingRevision,
            environment = RequestEnvironment(),
            secretHandles = emptyList(),
            budget = ExecutionBudget(
                sessionReauthorization = SessionReauthorization(
                    maxSessionSeconds = job.wallClockSeconds,
                    requiresUserReauth = false,
                ),
                thermalStateObservabilityRequired = false,
                wallClockSeconds = job.wallClockSeconds,
            ),
            authorityGrant = job.authorityGrant,
            expectedOutputs = job.expectedArtifactPaths.map {
                ExpectedOutput(role = it, mediaType = "application/octet-stream")
            },
            idempotencyKey = null,
            sideEffectExternal = false,
            fgsType = FgsType.NONE,
            openEnded = false,
        )

        val prepared = termuxProvider.prepare(request)
        val handle = termuxProvider.start(prepared)
        val receipt = termuxProvider.observe(handle)
            .filterIsInstance<ExecutionEvent.ReceiptReady>()
            .first()
            .receipt

        val artifactUris = linkedMapOf<String, Uri>()
        if (receipt.exitState == dev.aarso.contracts.execution.ExecutionExitState.SUCCEEDED_UNVERIFIED) {
            for (path in job.expectedArtifactPaths) {
                artifactUris[path] = mirror.pullArtifact(
                    termuxWorkspacePath = mirrored.termuxPath,
                    relativePath = path,
                    destinationTreeUri = job.workspaceTreeUri,
                )
            }
        }

        return LocalWorkspaceExecutionOutcome(
            runtime = matched.profile,
            mirror = mirrored,
            receipt = receipt,
            artifactUris = artifactUris,
        )
    }
}
