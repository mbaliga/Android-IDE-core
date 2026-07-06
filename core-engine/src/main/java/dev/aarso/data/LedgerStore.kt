package dev.aarso.data

import dev.aarso.data.dao.LedgerDao
import dev.aarso.domain.ledger.LedgerEntry
import dev.aarso.ui.state.LedgerSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed implementation of the [LedgerSource] seam.
 *
 * This is the concrete data-layer source the UI seam ([dev.aarso.ui.state.LedgerSource])
 * deliberately omits from the open-core interface set — the host wires it at startup with a real
 * [LedgerDao]. It does two things and nothing more:
 *
 * - [entries] streams the persisted ledger, mapping each row through the pure
 *   [LedgerMapper] so the presenter only ever sees domain [LedgerEntry]s, never Room entities.
 * - [append] writes one entry per turn (and one per Council member on a fan-out).
 *
 * The append-only contract lives in the DAO (insert-only, no update/delete); this store is a
 * thin, side-effect-free adapter over it. On-thesis: an **on-device** read/write only — no
 * telemetry, no server rollup.
 */
class LedgerStore(private val dao: LedgerDao) : LedgerSource {

    /** The full ledger as domain entries, re-emitted on every append. */
    override fun entries(): Flow<List<LedgerEntry>> =
        dao.all().map { rows -> rows.map(LedgerMapper::toDomain) }

    /** Append one ledger entry (mapped to its persistable row). */
    suspend fun append(entry: LedgerEntry) = dao.insert(LedgerMapper.toEntity(entry))
}
