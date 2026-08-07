package dev.aarso.domain.authority

import dev.aarso.contracts.authority.AuthorityDecision
import dev.aarso.contracts.authority.ResourceScope
import dev.aarso.contracts.common.ContractEnvelope
import dev.aarso.contracts.common.ProducerRef
import dev.aarso.data.ReceiptStore
import dev.aarso.domain.contracts.AuthorityCodec
import java.time.Instant

/**
 * Wires [AuthorityEngine] through WP-2's [ReceiptStore] (the WP-4 brief's "receipts wired through
 * WP-2 store"): every [evaluate] call is both a live policy decision AND an append-only audit
 * entry (CAPABILITY_AUTHORITY_MODEL.md §3.3 -- `AuthorityDecisionRecord` is itself append-only,
 * FB-RAT-COM-006), using the exact same general-purpose receipt mechanism WP-2 built, not a
 * second bespoke audit log invented for this domain alone.
 */
class AuditedAuthorityEngine(
    private val engine: AuthorityEngine,
    private val receiptStore: ReceiptStore,
    private val producer: ProducerRef,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun evaluate(
        requestObjectId: String,
        principalId: String,
        capabilityId: String,
        resourceScope: ResourceScope,
        purpose: String? = null,
    ): AuthorityDecision {
        val decision = engine.evaluate(requestObjectId, principalId, capabilityId, resourceScope, purpose)
        val envelope = ContractEnvelope(
            schemaVersion = "1.0.0",
            objectId = decision.decisionId,
            createdAtUtc = now(),
            producer = producer,
            payload = decision,
        )
        receiptStore.append("authority-decision", envelope, AuthorityCodec::encodeAuthorityDecision)
        return decision
    }
}
