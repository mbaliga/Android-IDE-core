package dev.aarso.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room representation of one [dev.aarso.contracts.workspace.RecoverySnapshot] row (WP-3 --
 * `WorkspaceJournal.checkpoint`/`restore`, WORKSPACE_KERNEL_SPEC.md §2.8/§4 FB-RAT-WS-003).
 * Append-only, same convention as [BufferJournalEntryEntity]/[ReceiptEntity]: every checkpoint
 * writes a NEW row rather than updating a prior one, so `restore(workspaceId)` (the latest row for
 * that `workspaceId`, ordered by `takenAtUtcMillis`) always has a full history of prior
 * checkpoints to fall back to if the newest one is ever found to be corrupt -- a property a
 * destructive "one row per workspace, overwritten in place" design would not have.
 */
@Entity(
    tableName = "recovery_snapshots",
    indices = [
        Index(value = ["workspaceId", "takenAtUtcMillis"]),
    ]
)
data class RecoverySnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workspaceId: String,
    val takenAtUtcMillis: Long,
    val payloadJson: String,
)
