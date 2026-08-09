package dev.aarso.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A small implementation-level bookkeeping table WP-3 needs beyond what
 * `dev.aarso.contracts.workspace.WorkspaceJournal`'s public interface declares: **neither
 * `BufferJournalEntry` nor `DocumentBuffer` carries a `workspaceId` field** (contracts/kotlin/
 * WorkspaceContracts.kt, WP-1 -- checked, not an oversight in this file: `Project.workspaceId`
 * exists, but a `DocumentBuffer` only points at a `resourceUri`, never at the `Project`/`Workspace`
 * it was opened under). `WorkspaceJournal.checkpoint(workspaceId)` still has to answer "which
 * buffers belong to this workspace" to build a `RecoverySnapshot.bufferSnapshots[]` — this table
 * is that link, populated once per buffer (typically alongside its `OPEN` journal entry) via
 * `RoomWorkspaceJournal.registerBuffer`, a method beyond the `WorkspaceJournal` interface's own
 * minimal surface. Flagged here, not silently added: this is a genuine gap in the WP-1 contract
 * corpus's object model, worked around at the implementation layer rather than by changing an
 * already-ratified Kotlin contract file this pass does not own.
 */
@Entity(
    tableName = "buffer_registry",
    indices = [Index(value = ["workspaceId"])]
)
data class BufferRegistryEntity(
    @PrimaryKey val bufferId: String,
    val workspaceId: String,
    val resourceUriJson: String,
    val registeredAtUtcMillis: Long,
)
