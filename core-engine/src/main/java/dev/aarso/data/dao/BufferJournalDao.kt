package dev.aarso.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import dev.aarso.data.entity.BufferJournalEntryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room access for the append-only buffer journal ([BufferJournalEntryEntity]) -- the
 * [dev.aarso.contracts.workspace.WorkspaceJournal.append] backing store, WP-3.
 */
@Dao
interface BufferJournalDao {

    /** Append one journal entry. Suspends; never updates or deletes an existing row. */
    @Insert
    suspend fun insert(e: BufferJournalEntryEntity)

    /** One buffer's full journal, oldest first -- what a full-history replay reads. */
    @Query("SELECT * FROM buffer_journal_entries WHERE bufferId = :bufferId ORDER BY sequence ASC")
    suspend fun forBuffer(bufferId: String): List<BufferJournalEntryEntity>

    /**
     * One buffer's journal strictly after [afterSequence] (exclusive) -- what a
     * checkpoint-then-replay recovery reads: the snapshot already covers everything up to and
     * including `afterSequence`, only entries recorded after it need re-applying.
     */
    @Query("SELECT * FROM buffer_journal_entries WHERE bufferId = :bufferId AND sequence > :afterSequence ORDER BY sequence ASC")
    suspend fun forBufferAfter(bufferId: String, afterSequence: Long): List<BufferJournalEntryEntity>

    /** The highest `sequence` recorded for [bufferId] so far, or null if it has no entries yet. */
    @Query("SELECT MAX(sequence) FROM buffer_journal_entries WHERE bufferId = :bufferId")
    suspend fun maxSequence(bufferId: String): Long?

    /** Live view of the whole journal, for UI/debug surfaces -- every table in this store has one. */
    @Query("SELECT * FROM buffer_journal_entries ORDER BY recordedAtUtcMillis ASC")
    fun all(): Flow<List<BufferJournalEntryEntity>>
}
