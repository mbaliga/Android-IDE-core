package dev.aarso.data

import dev.aarso.contracts.workspace.BufferJournalEntry
import dev.aarso.contracts.workspace.BufferSnapshotEntry
import dev.aarso.contracts.workspace.LineEndings
import dev.aarso.contracts.workspace.RecoverySnapshot
import dev.aarso.contracts.workspace.RecoverySnapshotReason
import dev.aarso.contracts.workspace.ResourceUri
import dev.aarso.contracts.workspace.WorkspaceJournal
import dev.aarso.data.dao.BufferJournalDao
import dev.aarso.data.dao.BufferRegistryDao
import dev.aarso.data.dao.RecoverySnapshotDao
import dev.aarso.data.entity.BufferJournalEntryEntity
import dev.aarso.data.entity.BufferRegistryEntity
import dev.aarso.data.entity.RecoverySnapshotEntity
import dev.aarso.domain.contracts.Digest
import dev.aarso.domain.contracts.IdGenerator
import dev.aarso.domain.contracts.WorkspaceCodec
import dev.aarso.domain.workspace.BufferReplay
import org.json.JSONObject
import java.time.Instant

/**
 * Room-backed [WorkspaceJournal] (WP-3, WORKSPACE_KERNEL_SPEC.md §6.2/§6.3/§4 FB-RAT-WS-003/005).
 * Every [append] lands one row via [BufferJournalDao] before returning -- the durability half of
 * FB-RAT-WS-003 ("every edit is journaled before it is considered durable-enough-to-survive-a-
 * kill," per that section's own mechanism note). [checkpoint] replays each of a workspace's
 * registered buffers via [BufferReplay] and writes one [RecoverySnapshot] row; [restore] reads the
 * latest one back.
 *
 * [registerBuffer] is deliberately NOT part of the [WorkspaceJournal] interface -- see
 * [BufferRegistryEntity]'s doc comment for why a bufferId-to-workspaceId link has to exist
 * somewhere, and why it can't live on [BufferJournalEntry] itself without amending the
 * already-ratified WP-1 contract.
 */
class RoomWorkspaceJournal(
    private val journalDao: BufferJournalDao,
    private val snapshotDao: RecoverySnapshotDao,
    private val registryDao: BufferRegistryDao,
    private val now: () -> Instant = Instant::now,
) : WorkspaceJournal {

    /** Links [bufferId] to [workspaceId] so a later [checkpoint] for that workspace finds it. */
    suspend fun registerBuffer(workspaceId: String, bufferId: String, resourceUri: ResourceUri) {
        registryDao.upsert(
            BufferRegistryEntity(
                bufferId = bufferId,
                workspaceId = workspaceId,
                resourceUriJson = WorkspaceCodec.encodeResourceUri(resourceUri).toString(),
                registeredAtUtcMillis = now().toEpochMilli()
            )
        )
    }

    override suspend fun append(change: BufferJournalEntry) {
        journalDao.insert(
            BufferJournalEntryEntity(
                bufferId = change.bufferId,
                sequence = change.sequence,
                recordedAtUtcMillis = change.recordedAtUtc.toEpochMilli(),
                payloadJson = WorkspaceCodec.encodeBufferJournalEntry(change).toString()
            )
        )
    }

    override suspend fun checkpoint(workspaceId: String): RecoverySnapshot {
        val bufferIds = registryDao.bufferIdsForWorkspace(workspaceId)
        var watermark = 0L
        val snapshots = mutableListOf<BufferSnapshotEntry>()

        for (bufferId in bufferIds) {
            val entries = journalDao.forBuffer(bufferId).map { WorkspaceCodec.decodeBufferJournalEntry(JSONObject(it.payloadJson)) }
            if (entries.isEmpty()) continue
            val replayed = BufferReplay.materialize(entries)
            watermark = maxOf(watermark, replayed.lastSequence)
            if (!replayed.isOpen) continue // FB-RAT-WS-003: only non-clean/still-open buffers need capturing.

            snapshots += BufferSnapshotEntry(
                bufferId = bufferId,
                resourceUri = entries.last().resourceUri,
                journalSequence = replayed.lastSequence,
                dirty = true,
                encoding = "UTF-8",
                lineEndings = detectLineEndings(replayed.content),
                contentDigest = Digest.ofUtf8(replayed.content)
            )
        }

        val snapshot = RecoverySnapshot(
            snapshotId = "snap_" + IdGenerator.generate(),
            workspaceId = workspaceId,
            takenAtUtc = now(),
            reason = RecoverySnapshotReason.PERIODIC_CHECKPOINT,
            lastJournalSequence = watermark,
            bufferSnapshots = snapshots
        )
        snapshotDao.insert(
            RecoverySnapshotEntity(
                workspaceId = workspaceId,
                takenAtUtcMillis = snapshot.takenAtUtc.toEpochMilli(),
                payloadJson = WorkspaceCodec.encodeRecoverySnapshot(snapshot).toString()
            )
        )
        return snapshot
    }

    override suspend fun restore(workspaceId: String): RecoverySnapshot? {
        val row = snapshotDao.latestForWorkspace(workspaceId) ?: return null
        return WorkspaceCodec.decodeRecoverySnapshot(JSONObject(row.payloadJson))
    }

    private fun detectLineEndings(content: String): LineEndings {
        val hasCrlf = content.contains("\r\n")
        val bareLf = Regex("(?<!\r)\n").containsMatchIn(content)
        val bareCr = Regex("\r(?!\n)").containsMatchIn(content)
        val kinds = listOf(hasCrlf, bareLf, bareCr).count { it }
        return when {
            kinds > 1 -> LineEndings.MIXED
            hasCrlf -> LineEndings.CRLF
            bareCr -> LineEndings.CR
            else -> LineEndings.LF
        }
    }
}
