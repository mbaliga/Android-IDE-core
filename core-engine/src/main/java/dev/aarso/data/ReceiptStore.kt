package dev.aarso.data

import dev.aarso.contracts.common.ContractEnvelope
import dev.aarso.data.dao.ReceiptDao
import dev.aarso.data.entity.ReceiptEntity
import dev.aarso.domain.contracts.EnvelopeCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/**
 * Append-only, on-device receipt store (WP-2 — the last real deliverable of the shared envelope
 * library: "receipt store, append-only, on-device"). Room-backed, same shape as [LedgerStore]:
 * a thin adapter over an insert-only [ReceiptDao], with the one extra behavior a receipt store
 * specifically needs — idempotent append.
 *
 * No UI-facing seam interface (unlike [LedgerStore]/`LedgerSource`): nothing in this codebase
 * observes receipts reactively yet, so this follows the plain-class DI pattern (`TaskStore`,
 * `WatchStore`) rather than inventing an interface with no real second implementation or
 * substitution need — a UI seam can be added later, over this same class, the moment something
 * actually needs to observe it.
 */
class ReceiptStore(private val dao: ReceiptDao) {

    /**
     * Appends [envelope] as a receipt of [receiptKind], encoding its payload with
     * [encodePayload]. **Idempotent**: if [envelope] carries a non-null
     * [ContractEnvelope.idempotencyKey] and a receipt with that same key is already recorded,
     * this returns the EXISTING receipt's [ContractEnvelope.objectId] and does not insert a
     * duplicate row (FB-RAT-COM-006 "retries use idempotency keys" — this is the store-level
     * enforcement of that rule, not just a documented convention). An envelope with a null
     * idempotencyKey is always inserted (it declared no retryable side effect, so there is
     * nothing to de-duplicate against).
     *
     * @return the objectId that now has a recorded receipt — either [envelope]'s own objectId
     *   (a fresh insert) or the prior receipt's objectId (a de-duplicated retry).
     */
    suspend fun <T> append(
        receiptKind: String,
        envelope: ContractEnvelope<T>,
        encodePayload: (T) -> JSONObject
    ): String {
        val idempotencyKey = envelope.idempotencyKey
        if (idempotencyKey != null) {
            val existing = dao.findByIdempotencyKey(idempotencyKey)
            if (existing != null) return existing.objectId
        }
        val encoded = EnvelopeCodec.encode(envelope, encodePayload)
        dao.insert(
            ReceiptEntity(
                receiptKind = receiptKind,
                objectId = envelope.objectId,
                idempotencyKey = idempotencyKey,
                schemaVersion = envelope.schemaVersion,
                producerName = envelope.producer.name,
                createdAtUtcMillis = envelope.createdAtUtc.toEpochMilli(),
                payloadJson = encoded.toString()
            )
        )
        return envelope.objectId
    }

    /** The full receipt store, oldest first, each row's raw JSON parsed back into an `org.json` object (not yet decoded into a typed payload — callers know their own payload type). */
    fun all(): Flow<List<Pair<ReceiptEntity, JSONObject>>> =
        dao.all().map { rows -> rows.map { it to JSONObject(it.payloadJson) } }

    /** Every receipt recorded for one durable object, oldest first. */
    fun forObjectId(objectId: String): Flow<List<Pair<ReceiptEntity, JSONObject>>> =
        dao.forObjectId(objectId).map { rows -> rows.map { it to JSONObject(it.payloadJson) } }
}
