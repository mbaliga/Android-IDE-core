package dev.aarso.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.aarso.data.entity.BufferRegistryEntity

/**
 * Room access for [BufferRegistryEntity] -- the bufferId-to-workspaceId link WP-3 needs beyond
 * `WorkspaceJournal`'s own interface (see that entity's doc comment for why). Upsert, not
 * append-only: re-registering the same `bufferId` (e.g. re-opening a buffer that was closed and
 * reopened) legitimately replaces its row rather than accumulating history nobody reads --
 * unlike the journal/snapshot tables, this is pure bookkeeping, not a domain-authoritative log.
 */
@Dao
interface BufferRegistryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: BufferRegistryEntity)

    @Query("SELECT bufferId FROM buffer_registry WHERE workspaceId = :workspaceId")
    suspend fun bufferIdsForWorkspace(workspaceId: String): List<String>
}
