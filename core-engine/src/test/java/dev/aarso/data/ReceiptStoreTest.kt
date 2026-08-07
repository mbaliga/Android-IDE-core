package dev.aarso.data

import dev.aarso.contracts.common.ContractEnvelope
import dev.aarso.contracts.common.ErrorEnvelope
import dev.aarso.contracts.common.ErrorSeverity
import dev.aarso.contracts.common.ProducerRef
import dev.aarso.contracts.common.SideEffectState
import dev.aarso.data.dao.ReceiptDao
import dev.aarso.data.entity.ReceiptEntity
import dev.aarso.domain.contracts.EnvelopeCodec
import dev.aarso.domain.contracts.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** In-memory [ReceiptDao] — same rationale as [WatchStoreTest]'s FakeWatchDao: Room needs a real
 *  SQLite binding this JVM gate doesn't have, so [ReceiptStore]'s logic is exercised against the
 *  DAO interface instead. */
private class FakeReceiptDao : ReceiptDao {
    private val rows = MutableStateFlow<List<ReceiptEntity>>(emptyList())
    var insertCount = 0
        private set

    override suspend fun insert(e: ReceiptEntity) {
        insertCount++
        rows.value = rows.value + e.copy(id = rows.value.size.toLong() + 1)
    }

    override fun all(): Flow<List<ReceiptEntity>> = rows.map { it.sortedBy(ReceiptEntity::createdAtUtcMillis) }

    override suspend fun findByIdempotencyKey(idempotencyKey: String): ReceiptEntity? =
        rows.value.filter { it.idempotencyKey == idempotencyKey }.maxByOrNull { it.createdAtUtcMillis }

    override fun forObjectId(objectId: String): Flow<List<ReceiptEntity>> =
        rows.map { list -> list.filter { it.objectId == objectId }.sortedBy(ReceiptEntity::createdAtUtcMillis) }
}

class ReceiptStoreTest {

    private fun errorEnvelope(objectId: String, idempotencyKey: String? = null) = ContractEnvelope(
        schemaVersion = "1.0.0",
        objectId = objectId,
        createdAtUtc = Instant.parse("2026-08-07T12:00:00Z"),
        producer = ProducerRef(name = "core-engine", version = "1.0.0"),
        idempotencyKey = idempotencyKey,
        payload = ErrorEnvelope(
            code = "TEST_ERROR",
            severity = ErrorSeverity.ERROR,
            retryable = true,
            sideEffectState = SideEffectState.NONE,
            detail = "detail",
            recoveryAction = "retry",
            userMessage = "Something went wrong."
        )
    )

    @Test
    fun `append inserts one row and returns the envelope's own objectId`() = runTest {
        val dao = FakeReceiptDao()
        val store = ReceiptStore(dao)
        val id = "err_" + IdGenerator.generate()
        val returned = store.append("execution", errorEnvelope(id), EnvelopeCodec::encodeErrorEnvelope)
        assertEquals(id, returned)
        assertEquals(1, dao.insertCount)
    }

    @Test
    fun `append is append-only -- the store never exposes an update or delete path`() {
        // Structural assertion: ReceiptStore's own public API has no update/delete method.
        // (There is no negative-space test possible beyond "the method does not exist" — this
        // documents the invariant next to the ones that DO exercise runtime behavior.)
        val methods = ReceiptStore::class.java.declaredMethods.map { it.name }
        assertTrue(methods.none { it.contains("update", ignoreCase = true) || it.contains("delete", ignoreCase = true) })
    }

    @Test
    fun `append with a repeated idempotencyKey does not insert a second row`() = runTest {
        val dao = FakeReceiptDao()
        val store = ReceiptStore(dao)
        val first = store.append("execution", errorEnvelope("err_1", idempotencyKey = "retry-key"), EnvelopeCodec::encodeErrorEnvelope)
        val second = store.append("execution", errorEnvelope("err_2", idempotencyKey = "retry-key"), EnvelopeCodec::encodeErrorEnvelope)
        assertEquals(first, second) // the SECOND call returns the FIRST receipt's objectId
        assertEquals(1, dao.insertCount) // only one row was ever inserted
    }

    @Test
    fun `append with no idempotencyKey always inserts, even for the same objectId twice`() = runTest {
        val dao = FakeReceiptDao()
        val store = ReceiptStore(dao)
        store.append("execution", errorEnvelope("err_x"), EnvelopeCodec::encodeErrorEnvelope)
        store.append("execution", errorEnvelope("err_x"), EnvelopeCodec::encodeErrorEnvelope)
        assertEquals(2, dao.insertCount)
    }

    @Test
    fun `append with different idempotencyKeys both insert`() = runTest {
        val dao = FakeReceiptDao()
        val store = ReceiptStore(dao)
        store.append("execution", errorEnvelope("err_a", idempotencyKey = "key-a"), EnvelopeCodec::encodeErrorEnvelope)
        store.append("execution", errorEnvelope("err_b", idempotencyKey = "key-b"), EnvelopeCodec::encodeErrorEnvelope)
        assertEquals(2, dao.insertCount)
    }

    @Test
    fun `all emits the appended receipt with a parseable payloadJson`() = runTest {
        val dao = FakeReceiptDao()
        val store = ReceiptStore(dao)
        store.append("execution", errorEnvelope("err_parse"), EnvelopeCodec::encodeErrorEnvelope)
        val (entity, json) = store.all().first().single()
        assertEquals("err_parse", entity.objectId)
        assertEquals("TEST_ERROR", json.getJSONObject("payload").getString("code"))
    }

    @Test
    fun `forObjectId only returns receipts for that object`() = runTest {
        val dao = FakeReceiptDao()
        val store = ReceiptStore(dao)
        store.append("execution", errorEnvelope("err_target"), EnvelopeCodec::encodeErrorEnvelope)
        store.append("execution", errorEnvelope("err_other"), EnvelopeCodec::encodeErrorEnvelope)
        val forTarget = store.forObjectId("err_target").first()
        assertEquals(1, forTarget.size)
        assertEquals("err_target", forTarget.single().first.objectId)
    }
}
