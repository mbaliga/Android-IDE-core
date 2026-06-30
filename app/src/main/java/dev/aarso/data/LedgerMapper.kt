package dev.aarso.data

import dev.aarso.data.entity.LedgerEntryEntity
import dev.aarso.domain.ledger.InteractionModel
import dev.aarso.domain.ledger.LedgerEntry
import dev.aarso.domain.ledger.Provenance
import dev.aarso.domain.ledger.Status
import dev.aarso.domain.ledger.Tier

/**
 * **Pure** translation between the persisted [LedgerEntryEntity] (enums as `String` names) and
 * the domain [LedgerEntry] (rich enums). No Room, no Android, no I/O — given the two data
 * classes this is a total, deterministic function, which is why it is the only piece of the
 * ledger data layer that is unit-tested on the JVM ([dev.aarso.data.LedgerMapperTest]).
 *
 * Enum handling is the whole point of the seam:
 * - **to domain**: `enumValueOf<…>(entity.field)` re-parses the stored `name`.
 * - **to entity**: `domain.field.name` writes the stable `name` string.
 *
 * Storing the `name` (not the ordinal) means the round-trip survives enum reordering. The
 * surrogate [LedgerEntryEntity.id] is intentionally dropped on the way to the domain (the domain
 * has no row id) and defaulted to `0` on the way back — Room assigns the real id at insert time.
 */
object LedgerMapper {

    /** Persisted row → pure domain entry. Re-parses each enum `name` via [enumValueOf]. */
    fun toDomain(e: LedgerEntryEntity): LedgerEntry = LedgerEntry(
        timestampMillis = e.timestampMillis,
        projectId = e.projectId,
        chatId = e.chatId,
        nodeId = e.nodeId,
        model = e.model,
        provider = e.provider,
        provenance = enumValueOf<Provenance>(e.provenance),
        interactionModel = enumValueOf<InteractionModel>(e.interactionModel),
        councilMemberId = e.councilMemberId,
        inputTokens = e.inputTokens,
        outputTokens = e.outputTokens,
        estCostMinor = e.estCostMinor,
        latencyMs = e.latencyMs,
        tier = enumValueOf<Tier>(e.tier),
        status = enumValueOf<Status>(e.status),
        estimated = e.estimated,
    )

    /** Domain entry → persistable row. Writes each enum's stable `.name`; id left for Room. */
    fun toEntity(d: LedgerEntry): LedgerEntryEntity = LedgerEntryEntity(
        timestampMillis = d.timestampMillis,
        projectId = d.projectId,
        chatId = d.chatId,
        nodeId = d.nodeId,
        model = d.model,
        provider = d.provider,
        provenance = d.provenance.name,
        interactionModel = d.interactionModel.name,
        councilMemberId = d.councilMemberId,
        inputTokens = d.inputTokens,
        outputTokens = d.outputTokens,
        estCostMinor = d.estCostMinor,
        latencyMs = d.latencyMs,
        tier = d.tier.name,
        status = d.status.name,
        estimated = d.estimated,
    )
}
