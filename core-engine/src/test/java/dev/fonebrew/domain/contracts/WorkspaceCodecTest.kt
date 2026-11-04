package dev.fonebrew.domain.contracts

import dev.fonebrew.contracts.workspace.BufferJournalEntry
import dev.fonebrew.contracts.workspace.BufferSnapshotEntry
import dev.fonebrew.contracts.workspace.ByteRange
import dev.fonebrew.contracts.workspace.JournalOpType
import dev.fonebrew.contracts.workspace.LineEndings
import dev.fonebrew.contracts.workspace.OriginKind
import dev.fonebrew.contracts.workspace.RecoverySnapshot
import dev.fonebrew.contracts.workspace.RecoverySnapshotReason
import dev.fonebrew.contracts.workspace.ResourceProvider
import dev.fonebrew.contracts.workspace.ResourceUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WorkspaceCodecTest {

    private val uri = ResourceUri(ResourceProvider.LOCAL, "local://a.txt", displayPath = "a.txt")

    @Test
    fun `ResourceUri round-trips including a null displayPath`() {
        val bare = ResourceUri(ResourceProvider.SSH, "ssh://host/path")
        val decoded = WorkspaceCodec.decodeResourceUri(WorkspaceCodec.encodeResourceUri(bare))
        assertEquals(bare, decoded)
    }

    @Test
    fun `BufferJournalEntry round-trips a byte-range op with content`() {
        val original = BufferJournalEntry(
            sequence = 5, bufferId = "buf1", resourceUri = uri, opType = JournalOpType.INSERT,
            recordedAtUtc = Instant.parse("2026-08-07T09:00:00Z"), originKind = OriginKind.HUMAN,
            originPrincipal = "user:me", byteRange = ByteRange(3, 3), content = "xyz"
        )
        val decoded = WorkspaceCodec.decodeBufferJournalEntry(WorkspaceCodec.encodeBufferJournalEntry(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `BufferJournalEntry round-trips a lifecycle op with no content or byteRange`() {
        val original = BufferJournalEntry(
            sequence = 0, bufferId = "buf1", resourceUri = uri, opType = JournalOpType.OPEN,
            recordedAtUtc = Instant.parse("2026-08-07T09:00:00Z"), originKind = OriginKind.AGENT,
            originPrincipal = "agent:x"
        )
        val decoded = WorkspaceCodec.decodeBufferJournalEntry(WorkspaceCodec.encodeBufferJournalEntry(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `BufferJournalEntry round-trip preserves an unknown field end to end`() {
        val original = BufferJournalEntry(
            sequence = 1, bufferId = "buf1", resourceUri = uri, opType = JournalOpType.SET_FULL_CONTENT,
            recordedAtUtc = Instant.parse("2026-08-07T09:00:00Z"), originKind = OriginKind.HUMAN,
            originPrincipal = "user:me", content = "hi"
        )
        val json = WorkspaceCodec.encodeBufferJournalEntry(original)
        json.put("futureField", "from a newer minor version")

        val decoded = WorkspaceCodec.decodeBufferJournalEntry(json)
        assertEquals("from a newer minor version", decoded.unknownFields["futureField"])

        val reEncoded = WorkspaceCodec.encodeBufferJournalEntry(decoded)
        assertEquals("from a newer minor version", reEncoded.getString("futureField"))
    }

    @Test
    fun `RecoverySnapshot round-trips its bufferSnapshots list`() {
        val original = RecoverySnapshot(
            snapshotId = "snap_1", workspaceId = "ws1", takenAtUtc = Instant.parse("2026-08-07T09:00:00Z"),
            reason = RecoverySnapshotReason.PERIODIC_CHECKPOINT, lastJournalSequence = 3,
            bufferSnapshots = listOf(
                BufferSnapshotEntry(
                    bufferId = "buf1", resourceUri = uri, journalSequence = 3, dirty = true,
                    encoding = "UTF-8", lineEndings = LineEndings.LF, contentDigest = Digest.ofUtf8("content")
                )
            )
        )
        val decoded = WorkspaceCodec.decodeRecoverySnapshot(WorkspaceCodec.encodeRecoverySnapshot(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `RecoverySnapshot with zero bufferSnapshots round-trips to an empty list, not null`() {
        val original = RecoverySnapshot(
            snapshotId = "snap_2", workspaceId = "ws1", takenAtUtc = Instant.parse("2026-08-07T09:00:00Z"),
            reason = RecoverySnapshotReason.MANUAL, lastJournalSequence = 0, bufferSnapshots = emptyList()
        )
        val decoded = WorkspaceCodec.decodeRecoverySnapshot(WorkspaceCodec.encodeRecoverySnapshot(original))
        assertEquals(original, decoded)
        assertTrue(decoded.bufferSnapshots.isEmpty())
    }
}
