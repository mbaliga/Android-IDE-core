package dev.aarso.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import dev.aarso.data.entity.LedgerEntryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room access for the append-only usage ledger ([LedgerEntryEntity]).
 *
 * Two operations only, matching the ledger's contract: **append one entry per turn** and
 * **observe the whole ledger** as a reactive stream. There is no update or delete — the ledger
 * is append-only, and the "Myself" aggregations ([dev.aarso.domain.ledger.LedgerAggregations])
 * fold over the full list rather than mutating rows.
 *
 * [all] orders by [LedgerEntryEntity.timestampMillis] ascending so the stream is already in
 * turn order; the presenter never has to re-sort. The [Flow] re-emits whenever a new entry is
 * inserted, which is exactly what the "Myself" surface observes.
 */
@Dao
interface LedgerDao {

    /** Append one ledger row. Suspends; one call per turn (and one per Council member on fan-out). */
    @Insert
    suspend fun insert(e: LedgerEntryEntity)

    /** The full ledger in turn order, re-emitted on every append. */
    @Query("SELECT * FROM ledger_entries ORDER BY timestampMillis ASC")
    fun all(): Flow<List<LedgerEntryEntity>>
}
