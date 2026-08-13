package dev.aarso.data

import dev.aarso.contracts.common.ContractEnvelope
import dev.aarso.contracts.common.ProducerRef
import dev.aarso.data.dao.ReceiptDao
import dev.aarso.data.entity.ReceiptEntity
import dev.aarso.domain.contracts.EnvelopeCodec
import dev.aarso.domain.curation.CompactedMessage
import dev.aarso.domain.curation.CompactionReceiptCodec
import dev.aarso.domain.curation.Fidelity
import dev.aarso.domain.curation.FidelityReason
import dev.aarso.domain.curation.MessageFate
import dev.aarso.domain.curation.Receipt
import dev.aarso.domain.curation.ResolvedFidelity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** In-memory [ReceiptDao] — same rationale as [ReceiptStoreTest]'s own FakeReceiptDao: Room needs
 *  a real SQLite binding this JVM gate doesn't have. Named differently and kept as its own fake
 *  (rather than reusing [ReceiptStoreTest]'s) since Kotlin's file-private top-level declarations
 *  still share one class-name namespace across a module — two files can't both declare a
 *  private `FakeReceiptDao` in the same package. */
private class FakeCompactionReceiptDao : ReceiptDao {
    private val rows = MutableStateFlow<List<ReceiptEntity>>(emptyList())
    override suspend fun insert(e: ReceiptEntity) { rows.value = rows.value + e.copy(id = rows.value.size.toLong() + 1) }
    override fun all(): Flow<List<ReceiptEntity>> = rows
    override suspend fun findByIdempotencyKey(idempotencyKey: String): ReceiptEntity? =
        rows.value.filter { it.idempotencyKey == idempotencyKey }.maxByOrNull { it.createdAtUtcMillis }
    override fun forObjectId(objectId: String): Flow<List<ReceiptEntity>> =
        rows.map { list -> list.filter { it.objectId == objectId } }
}

/**
 * THREAD_TOPOLOGY_PLAN.md WP3's "receipt persistence via existing ReceiptStore": proves a
 * [Receipt] survives the exact round trip [dev.aarso.ui.ChatViewModel.runCompaction] performs on
 * a real run (encode → [ReceiptStore.append] → [ReceiptStore.forObjectId] → [EnvelopeCodec.decode])
 * and the one [dev.aarso.ui.ChatViewModel]'s own boundary lookup performs later — the exact same
 * path [dev.aarso.domain.curation.CompactionReceiptCodecTest] exercises directly against the
 * codec, but here through the real store.
 */
class CompactionReceiptStoreTest {

    private val receipt = Receipt(
        runAt = 1_700_000_000_000L,
        entries = listOf(
            CompactedMessage(
                msgId = "m1",
                fate = MessageFate.KEPT_VERBATIM,
                resolution = ResolvedFidelity(Fidelity.F3, mustInclude = false, isFailureTombstone = false, reason = FidelityReason.VERDICT_POSITIVE),
                text = "exact original text",
            ),
            CompactedMessage(
                msgId = "m2",
                fate = MessageFate.GIST,
                resolution = ResolvedFidelity(Fidelity.F1, mustInclude = false, isFailureTombstone = false, reason = FidelityReason.DEFAULT_ORDINARY),
                text = "a gist",
            ),
        ),
        directivesHonored = 1,
        directivesTotal = 1,
    )

    @Test fun `a compaction receipt round-trips through ReceiptStore append and forObjectId`() = runTest {
        val store = ReceiptStore(FakeCompactionReceiptDao())
        val envelope = ContractEnvelope(
            schemaVersion = "1.0.0",
            objectId = "compaction-run-1",
            createdAtUtc = Instant.ofEpochMilli(receipt.runAt),
            producer = ProducerRef(name = "core-engine", version = "1.0.0"),
            payload = receipt,
        )
        val returnedId = store.append("compaction-run", envelope, CompactionReceiptCodec::encodeReceipt)
        assertEquals("compaction-run-1", returnedId)

        val row = store.forObjectId("compaction-run-1").first().single()
        val decoded = EnvelopeCodec.decode(row.second, CompactionReceiptCodec::decodeReceipt)
        assertEquals(receipt, decoded.payload)
    }

    @Test fun `forObjectId for an unrecorded id returns nothing to decode`() = runTest {
        val store = ReceiptStore(FakeCompactionReceiptDao())
        assertEquals(emptyList<Any>(), store.forObjectId("never-recorded").first())
    }
}
