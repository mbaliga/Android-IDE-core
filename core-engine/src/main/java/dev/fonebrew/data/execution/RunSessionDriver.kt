package dev.fonebrew.data.execution

import dev.fonebrew.contracts.authority.ResourceKind
import dev.fonebrew.contracts.authority.ResourceScope
import dev.fonebrew.contracts.common.ContractEnvelope
import dev.fonebrew.contracts.common.ProducerRef
import dev.fonebrew.contracts.execution.ExecutionEvent
import dev.fonebrew.contracts.execution.ExecutionProvider
import dev.fonebrew.contracts.execution.ExecutionReceipt
import dev.fonebrew.data.GitHostStore
import dev.fonebrew.data.GitTransport
import dev.fonebrew.data.ReceiptStore
import dev.fonebrew.data.RemoteHostStore
import dev.fonebrew.domain.authority.AuditedAuthorityEngine
import dev.fonebrew.domain.authority.InMemoryGrantStore
import dev.fonebrew.domain.contracts.ExecutionCodec
import dev.fonebrew.domain.contracts.IdGenerator
import dev.fonebrew.domain.execution.CiActionsExecutionProvider
import dev.fonebrew.domain.execution.LocalProcessExecutionProvider
import dev.fonebrew.domain.execution.RunAuthorityGate
import dev.fonebrew.domain.execution.RunAuthorityUiState
import dev.fonebrew.domain.execution.RunGrants
import dev.fonebrew.domain.execution.RunRequestFactory
import dev.fonebrew.domain.execution.RunTarget
import dev.fonebrew.domain.execution.SshExecutionProvider
import dev.fonebrew.domain.remote.Identity
import dev.fonebrew.domain.remote.RemoteTransport
import dev.fonebrew.domain.remote.Trust
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.time.Instant

/**
 * The Develop -> Run panel's execution glue -- the first real consumer of the WP-4 Execution
 * Contract + Authority engine and the WP-5 SSH/CI providers (`di/AppContainer.kt`'s own
 * "No consumer wired in yet" notes on both, now resolved here). Every run is [evaluate]d through
 * [authorityEngine] before a single [ExecutionProvider] method is called -- there is no path in
 * this class that reaches [execute] without a matched grant id, mirroring the architecture spine
 * ("execution goes THROUGH authority") rather than treating that as a UI-layer suggestion.
 *
 * No new secret handling: SSH auth resolves through [remoteHostStore]'s existing Keystore-backed
 * refs (the same lookup [dev.fonebrew.ui.develop.HardwareFacet] already does), CI auth through
 * [gitHostStore]'s existing Keystore-backed token (the same lookup [dev.fonebrew.data.BuildsRepo]
 * already does) -- this class stores no credential of its own.
 */
class RunSessionDriver(
    private val authorityEngine: AuditedAuthorityEngine,
    private val principalId: String,
    private val grantStore: InMemoryGrantStore,
    private val receiptStore: ReceiptStore,
    private val localProvider: LocalProcessExecutionProvider,
    private val remoteHostStore: RemoteHostStore,
    private val newSshTransport: () -> RemoteTransport,
    private val gitHostStore: GitHostStore,
    private val gitTransport: GitTransport,
    private val producer: ProducerRef,
    private val now: () -> Instant = Instant::now,
) {

    /** Step 1 of every run, never skippable from the UI: ask authority for this target's own
     *  capability, scoped to this target's own [ResourceScope]. */
    suspend fun evaluate(target: RunTarget): RunAuthorityUiState {
        val decision = authorityEngine.evaluate(
            requestObjectId = "run_req_" + IdGenerator.generate(),
            principalId = principalId,
            capabilityId = target.capabilityId,
            resourceScope = ResourceScope(ResourceKind.EXECUTION_TARGET, target.targetId),
            purpose = RunGrants.PURPOSE,
        )
        return RunAuthorityGate.classify(decision)
    }

    /** Step 1b, reached only from [RunAuthorityUiState.NeedsGrant]: the user's explicit,
     *  target-scoped consent, then a real re-evaluation against the new grant -- never an
     *  assumption that granting implies allowing (a watched target's grant still demands its own
     *  per-run confirm; see [RunGrants]'s doc comment). */
    suspend fun grantAndReevaluate(target: RunTarget): RunAuthorityUiState {
        grantStore.add(RunGrants.defaultGrantFor(target, principalId, now()))
        return evaluate(target)
    }

    /**
     * Step 2: prepare -> start -> observe the real provider for [target], authority already
     * cleared (an `Allowed` grantId, or the grantId a `NeedsConfirmation` prompt named after the
     * user confirmed). Null means this target has no reachable provider right now -- e.g. a CI
     * host with no stored token -- surfaced honestly rather than a silent no-op.
     */
    fun execute(target: RunTarget, command: String, grantId: String): Flow<ExecutionEvent>? {
        val provider = providerFor(target) ?: return null
        val request = RunRequestFactory.build(target, command, grantId, now = now)
        return flow {
            val prepared = provider.prepare(request)
            val handle = provider.start(prepared)
            emitAll(provider.observe(handle))
        }
    }

    /** Appends the terminal [ExecutionReceipt] to [receiptStore] -- the exact append-only sink
     *  WP-2 left for this (`AppContainer`'s own comment: "later work packages (execution
     *  receipts, ...) call ReceiptStore.append"). */
    suspend fun persistReceipt(receipt: ExecutionReceipt) {
        val envelope = ContractEnvelope(
            schemaVersion = "1.0.0",
            objectId = receipt.receiptId,
            createdAtUtc = now(),
            producer = producer,
            payload = receipt,
        )
        receiptStore.append("execution-receipt", envelope, ExecutionCodec::encodeExecutionReceipt)
    }

    private fun providerFor(target: RunTarget): ExecutionProvider? = when (target) {
        is RunTarget.Local -> localProvider
        is RunTarget.Ssh -> sshProviderFor(target)
        is RunTarget.Ci -> ciProviderFor(target)
    }

    /** One provider per call, matching [dev.fonebrew.data.DeviceRepo.exec]'s own "fresh
     *  connection per request, not a shared pool" precedent for this same SSH spine. Trust is
     *  never prompted from inside a Run -- [dev.fonebrew.data.DeviceRepo]'s own gate, "proceed
     *  only on an already-vetted host"; pinning a new/changed host key stays the Remote screen's
     *  job. */
    private fun sshProviderFor(target: RunTarget.Ssh): SshExecutionProvider = SshExecutionProvider(
        transportFactory = newSshTransport,
        hostResolver = { target.host },
        identityResolver = { identityFor(target.host.alias) },
        initialKnownHosts = remoteHostStore.knownHosts.value,
        approveTrust = { verdict -> verdict is Trust.Vetted },
    )

    private fun identityFor(alias: String): Identity {
        val ref = remoteHostStore.hostSecret(alias)
        return when {
            ref == null -> Identity.Agent
            ref.isKey -> Identity.PublicKey(ref.id)
            else -> Identity.Password(ref.id)
        }
    }

    private fun ciProviderFor(target: RunTarget.Ci): CiActionsExecutionProvider? {
        val token = gitHostStore.token(target.host.id) ?: return null
        return CiActionsExecutionProvider(host = target.host, token = token, transport = gitTransport)
    }
}
