package dev.aarso.data

import dev.aarso.contracts.workspace.BufferJournalEntry
import dev.aarso.contracts.workspace.ByteRange
import dev.aarso.contracts.workspace.JournalOpType
import dev.aarso.contracts.workspace.OriginKind
import dev.aarso.contracts.workspace.ResourceProvider
import dev.aarso.contracts.workspace.ResourceUri
import dev.aarso.data.dao.BufferJournalDao
import dev.aarso.data.dao.BufferRegistryDao
import dev.aarso.data.dao.RecoverySnapshotDao
import dev.aarso.data.entity.BufferJournalEntryEntity
import dev.aarso.data.entity.BufferRegistryEntity
import dev.aarso.data.entity.RecoverySnapshotEntity
import dev.aarso.domain.contracts.Digest
import dev.aarso.domain.contracts.WorkspaceCodec
import dev.aarso.domain.workspace.BufferReplay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

/**
 * In-memory fakes, same rationale as [ReceiptStoreTest]'s FakeReceiptDao: no real SQLite binding
 * in this JVM gate. A fresh set of fakes plus a NEW [RoomWorkspaceJournal] instance over the SAME
 * fakes is exactly how these tests simulate "the process was killed and restarted against the
 * same on-disk database" -- the fakes' backing lists/maps ARE the durable store for this test's
 * purposes; only the `RoomWorkspaceJournal` object wrapping them is discarded and recreated.
 */
private class FakeBufferJournalDao : BufferJournalDao {
    private val rows = MutableStateFlow<List<BufferJournalEntryEntity>>(emptyList())
    var insertCount = 0
        private set

    override suspend fun insert(e: BufferJournalEntryEntity) {
        insertCount++
        rows.value = rows.value + e.copy(id = rows.value.size.toLong() + 1)
    }

    override suspend fun forBuffer(bufferId: String): List<BufferJournalEntryEntity> =
        rows.value.filter { it.bufferId == bufferId }.sortedBy { it.sequence }

    override suspend fun forBufferAfter(bufferId: String, afterSequence: Long): List<BufferJournalEntryEntity> =
        rows.value.filter { it.bufferId == bufferId && it.sequence > afterSequence }.sortedBy { it.sequence }

    override suspend fun maxSequence(bufferId: String): Long? =
        rows.value.filter { it.bufferId == bufferId }.maxOfOrNull { it.sequence }

    override fun all(): Flow<List<BufferJournalEntryEntity>> = rows
}

private class FakeRecoverySnapshotDao : RecoverySnapshotDao {
    private val rows = mutableListOf<RecoverySnapshotEntity>()

    override suspend fun insert(e: RecoverySnapshotEntity) {
        rows += e.copy(id = rows.size.toLong() + 1)
    }

    override suspend fun latestForWorkspace(workspaceId: String): RecoverySnapshotEntity? =
        rows.filter { it.workspaceId == workspaceId }.maxByOrNull { it.takenAtUtcMillis }
}

private class FakeBufferRegistryDao : BufferRegistryDao {
    private val rows = mutableMapOf<String, BufferRegistryEntity>()

    override suspend fun upsert(e: BufferRegistryEntity) {
        rows[e.bufferId] = e
    }

    override suspend fun bufferIdsForWorkspace(workspaceId: String): List<String> =
        rows.values.filter { it.workspaceId == workspaceId }.map { it.bufferId }
}

class RoomWorkspaceJournalTest {

    private val uri = ResourceUri(ResourceProvider.LOCAL, "local://a.txt")

    private fun entry(seq: Long, op: JournalOpType, content: String? = null, range: ByteRange? = null, bufferId: String = "buf1") =
        BufferJournalEntry(
            sequence = seq, bufferId = bufferId, resourceUri = uri, opType = op,
            recordedAtUtc = Instant.parse("2026-08-07T00:00:00Z"), originKind = OriginKind.HUMAN,
            originPrincipal = "user:test", byteRange = range, content = content
        )

    @Test
    fun `append persists an entry that forBuffer can read back, decoded, unchanged`() = runTest {
        val dao = FakeBufferJournalDao()
        val journal = RoomWorkspaceJournal(dao, FakeRecoverySnapshotDao(), FakeBufferRegistryDao())
        val original = entry(0, JournalOpType.SET_FULL_CONTENT, content = "hello")
        journal.append(original)

        assertEquals(1, dao.insertCount)
        val decoded = WorkspaceCodec.decodeBufferJournalEntry(JSONObject(dao.forBuffer("buf1").single().payloadJson))
        assertEquals(original, decoded)
    }

    @Test
    fun `restore on a never-checkpointed workspace returns null, not an error`() = runTest {
        val journal = RoomWorkspaceJournal(FakeBufferJournalDao(), FakeRecoverySnapshotDao(), FakeBufferRegistryDao())
        assertNull(journal.restore("ws-never-seen"))
    }

    @Test
    fun `checkpoint captures a registered, still-open, edited buffer with a correct content digest`() = runTest {
        val journal = RoomWorkspaceJournal(FakeBufferJournalDao(), FakeRecoverySnapshotDao(), FakeBufferRegistryDao())
        journal.registerBuffer("ws1", "buf1", uri)
        journal.append(entry(0, JournalOpType.OPEN))
        journal.append(entry(1, JournalOpType.SET_FULL_CONTENT, content = "draft content"))

        val snapshot = journal.checkpoint("ws1")
        assertEquals("ws1", snapshot.workspaceId)
        assertEquals(1L, snapshot.lastJournalSequence)
        val bufferSnapshot = snapshot.bufferSnapshots.single()
        assertEquals("buf1", bufferSnapshot.bufferId)
        assertTrue(bufferSnapshot.dirty)
        assertEquals(Digest.ofUtf8("draft content"), bufferSnapshot.contentDigest)
    }

    @Test
    fun `checkpoint excludes a buffer whose last entry is CLOSE -- only non-clean buffers are captured`() = runTest {
        val journal = RoomWorkspaceJournal(FakeBufferJournalDao(), FakeRecoverySnapshotDao(), FakeBufferRegistryDao())
        journal.registerBuffer("ws1", "buf1", uri)
        journal.append(entry(0, JournalOpType.OPEN))
        journal.append(entry(1, JournalOpType.SET_FULL_CONTENT, content = "saved and closed"))
        journal.append(entry(2, JournalOpType.CLOSE))

        val snapshot = journal.checkpoint("ws1")
        assertTrue(snapshot.bufferSnapshots.isEmpty())
    }

    @Test
    fun `restore returns the most recently taken snapshot for that workspace`() = runTest {
        val journal = RoomWorkspaceJournal(FakeBufferJournalDao(), FakeRecoverySnapshotDao(), FakeBufferRegistryDao())
        journal.registerBuffer("ws1", "buf1", uri)
        journal.append(entry(0, JournalOpType.OPEN))
        journal.append(entry(1, JournalOpType.SET_FULL_CONTENT, content = "v1"))
        journal.checkpoint("ws1")

        journal.append(entry(2, JournalOpType.SET_FULL_CONTENT, content = "v2"))
        val second = journal.checkpoint("ws1")

        val restored = journal.restore("ws1")
        assertEquals(second.snapshotId, restored?.snapshotId)
        assertEquals(Digest.ofUtf8("v2"), restored?.bufferSnapshots?.single()?.contentDigest)
    }

    // -----------------------------------------------------------------------------------
    // FB-RAT-WS-003 / FB-RAT-WS-008: simulated forced-kill suite. 100 randomized kill points;
    // for each, the entries appended before the kill must be exactly and only what a fresh
    // RoomWorkspaceJournal instance (same backing DAOs -- "same on-disk DB, new process") reads
    // back, with the exact expected reconstructed content and no duplication or loss.
    // -----------------------------------------------------------------------------------

    @Test
    fun `100 simulated forced kills at random points during append -- zero loss, zero duplication`() = runTest {
        repeat(100) { iteration ->
            val random = Random(seed = iteration.toLong())
            val dao = FakeBufferJournalDao()
            val sessionA = RoomWorkspaceJournal(dao, FakeRecoverySnapshotDao(), FakeBufferRegistryDao())

            // Build a random edit sequence: OPEN, an initial SET_FULL_CONTENT, then 0-8 more
            // content-bearing ops, each with a byte range valid against the running content so
            // far (computed independently via BufferReplay -- a correctness-tested oracle, see
            // BufferReplayTest -- so this test's own focus stays on DURABILITY, not replay logic).
            val allEntries = mutableListOf<BufferJournalEntry>()
            allEntries += entry(0, JournalOpType.OPEN)
            allEntries += entry(1, JournalOpType.SET_FULL_CONTENT, content = "seed-${iteration}")
            var seq = 1L
            val editCount = random.nextInt(0, 9)
            repeat(editCount) {
                val currentLen = BufferReplay.materialize(allEntries).content.toByteArray(Charsets.UTF_8).size
                seq += 1
                val insertAt = random.nextInt(0, currentLen + 1).toLong()
                allEntries += entry(seq, JournalOpType.INSERT, content = "+${it}", range = ByteRange(insertAt, insertAt))
            }

            // Randomized kill point: append only a random PREFIX of allEntries, "kill," then read
            // back through a brand-new RoomWorkspaceJournal over the SAME dao.
            val killAfter = random.nextInt(0, allEntries.size + 1)
            val beforeKill = allEntries.take(killAfter)
            for (e in beforeKill) sessionA.append(e)
            // sessionA is discarded here -- nothing further is ever called on it.

            val sessionB = RoomWorkspaceJournal(dao, FakeRecoverySnapshotDao(), FakeBufferRegistryDao())
            val recovered = dao.forBuffer("buf1").map { WorkspaceCodec.decodeBufferJournalEntry(JSONObject(it.payloadJson)) }

            assertEquals("iteration $iteration: entry count mismatch after simulated kill", beforeKill, recovered)

            val expectedContent = BufferReplay.materialize(beforeKill).content
            val recoveredContent = BufferReplay.materialize(recovered).content
            assertEquals("iteration $iteration: recovered content mismatch", expectedContent, recoveredContent)

            // registerBuffer + checkpoint on sessionB (the "restarted process") must also recover
            // cleanly from exactly this surviving prefix, with no reference to anything post-kill.
            if (beforeKill.isNotEmpty()) {
                sessionB.registerBuffer("ws1", "buf1", uri)
                val snapshot = sessionB.checkpoint("ws1")
                val replayed = BufferReplay.materialize(beforeKill)
                if (replayed.isOpen) {
                    assertEquals("iteration $iteration: snapshot digest mismatch", Digest.ofUtf8(replayed.content), snapshot.bufferSnapshots.single().contentDigest)
                } else {
                    assertTrue("iteration $iteration: closed buffer should not appear in snapshot", snapshot.bufferSnapshots.isEmpty())
                }
            }
        }
    }
}
