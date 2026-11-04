package dev.fonebrew.domain.authority

import dev.fonebrew.contracts.authority.AuthorityDecision
import dev.fonebrew.contracts.authority.Principal
import dev.fonebrew.contracts.authority.PrincipalKind
import dev.fonebrew.contracts.authority.PrincipalStatus
import dev.fonebrew.contracts.authority.ResourceKind
import dev.fonebrew.contracts.authority.ResourceScope
import dev.fonebrew.contracts.common.ProducerRef
import dev.fonebrew.data.ReceiptStore
import dev.fonebrew.data.dao.ReceiptDao
import dev.fonebrew.data.entity.ReceiptEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Same FakeReceiptDao rationale as [dev.fonebrew.data.ReceiptStoreTest] -- no real SQLite binding in this JVM gate. */
private class FakeReceiptDao : ReceiptDao {
    private val rows = MutableStateFlow<List<ReceiptEntity>>(emptyList())
    override suspend fun insert(e: ReceiptEntity) { rows.value = rows.value + e.copy(id = rows.value.size.toLong() + 1) }
    override fun all(): Flow<List<ReceiptEntity>> = rows
    override suspend fun findByIdempotencyKey(idempotencyKey: String): ReceiptEntity? = null
    override fun forObjectId(objectId: String): Flow<List<ReceiptEntity>> = rows.map { list -> list.filter { it.objectId == objectId } }
}

class AuditedAuthorityEngineTest {

    @Test
    fun `every evaluate call is also recorded as an append-only authority-decision receipt`() = runTest {
        val dao = FakeReceiptDao()
        val receiptStore = ReceiptStore(dao)
        val principals = InMemoryPrincipalStore().apply {
            add(Principal("user-1", PrincipalKind.USER, "Owner", null, PrincipalStatus.ACTIVE, Instant.parse("2026-01-01T00:00:00Z")))
        }
        val audited = AuditedAuthorityEngine(
            engine = AuthorityEngine(InMemoryGrantStore(), principals, "1.0.0"),
            receiptStore = receiptStore, producer = ProducerRef("core-engine", "1.0.0")
        )

        val decision = audited.evaluate("req1", "user-1", "fb.repo.read", ResourceScope(ResourceKind.REPOSITORY, "org/repo"))
        assertTrue(decision is AuthorityDecision.Deny) // no grant registered -- default deny, still audited

        val receipts = receiptStore.all().first()
        assertEquals(1, receipts.size)
        val (entity, json) = receipts.single()
        assertEquals("authority-decision", entity.receiptKind)
        assertEquals(decision.decisionId, entity.objectId)
        assertEquals("DENY", json.getJSONObject("payload").getString("outcome"))
    }
}
