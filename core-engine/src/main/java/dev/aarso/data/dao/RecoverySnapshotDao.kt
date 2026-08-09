package dev.aarso.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import dev.aarso.data.entity.RecoverySnapshotEntity

/**
 * Room access for the append-only recovery-snapshot store ([RecoverySnapshotEntity]) -- the
 * [dev.aarso.contracts.workspace.WorkspaceJournal.checkpoint]/`restore` backing store, WP-3.
 */
@Dao
interface RecoverySnapshotDao {

    /** Append one snapshot. Suspends; never updates or deletes an existing row. */
    @Insert
    suspend fun insert(e: RecoverySnapshotEntity)

    /**
     * The most recent snapshot for [workspaceId], or null if this workspace has never been
     * checkpointed -- [dev.aarso.contracts.workspace.WorkspaceJournal.restore]'s "a brand-new
     * workspace has no snapshot yet" case is a valid, expected outcome (WORKSPACE_KERNEL_SPEC.md
     * §6.2), not an error.
     */
    @Query("SELECT * FROM recovery_snapshots WHERE workspaceId = :workspaceId ORDER BY takenAtUtcMillis DESC LIMIT 1")
    suspend fun latestForWorkspace(workspaceId: String): RecoverySnapshotEntity?
}
