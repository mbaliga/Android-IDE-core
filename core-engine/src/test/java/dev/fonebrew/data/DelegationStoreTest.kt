package dev.fonebrew.data

import dev.fonebrew.data.dao.DelegationEventDao
import dev.fonebrew.data.entity.DelegationEventEntity
import dev.fonebrew.domain.thread.DelegationKind
import dev.fonebrew.domain.thread.DelegationOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory fake, same rationale as [ThreadMarkerStoreTest]'s FakeThreadMarkerDao. */
private class FakeDelegationEventDao : DelegationEventDao {
    private val rows = MutableStateFlow<List<DelegationEventEntity>>(emptyList())
    override suspend fun insert(event: DelegationEventEntity) {
        check(rows.value.none { it.id == event.id }) { "duplicate id ${event.id}" }
        rows.value = rows.value + event
    }
    override suspend fun update(event: DelegationEventEntity) { rows.value = rows.value.map { if (it.id == event.id) event else it } }
    override suspend fun getById(id: String): DelegationEventEntity? = rows.value.firstOrNull { it.id == id }
    override suspend fun forRoot(rootId: String): List<DelegationEventEntity> =
        rows.value.filter { it.rootId == rootId }.sortedBy { it.at }
    override fun observeAll(): Flow<List<DelegationEventEntity>> = rows
}

class DelegationStoreTest {

    private fun store() = DelegationStore(FakeDelegationEventDao())

    @Test fun `record creates a PENDING delegation`() = runTest {
        val store = store()
        val event = store.record(
            kind = DelegationKind.MODEL_PICK_BRANCH,
            rootId = "root-1",
            anchorMsgId = "msg-1",
            chosenRef = "msg-2",
            alternatives = listOf("msg-3", "msg-4"),
            now = 10L,
        )
        assertEquals(DelegationOutcome.PENDING, event.outcome)
        assertNull(event.outcomeAt)
        assertEquals(listOf("msg-3", "msg-4"), event.alternatives)
    }

    @Test fun `resolveOutcome moves a delegation to KEPT and stamps outcomeAt`() = runTest {
        val store = store()
        val event = store.record(DelegationKind.COUNCIL_AUTOMERGE, rootId = "root-1", now = 1L)
        store.resolveOutcome(event, DelegationOutcome.KEPT, now = 50L)
        val resolved = store.forRoot("root-1").single()
        assertEquals(DelegationOutcome.KEPT, resolved.outcome)
        assertEquals(50L, resolved.outcomeAt)
    }

    @Test fun `resolveOutcome moves a delegation to REVERTED`() = runTest {
        val store = store()
        val event = store.record(DelegationKind.GATEWAY_AUTO, rootId = "root-1", now = 1L)
        store.resolveOutcome(event, DelegationOutcome.REVERTED, now = 60L)
        assertEquals(DelegationOutcome.REVERTED, store.forRoot("root-1").single().outcome)
    }

    @Test fun `resolveOutcome rejects resolving back to PENDING`() = runTest {
        val store = store()
        val event = store.record(DelegationKind.AUTO_DEFAULT, rootId = "root-1", now = 1L)
        try {
            store.resolveOutcome(event, DelegationOutcome.PENDING)
            org.junit.Assert.fail("expected IllegalArgumentException for resolving back to PENDING")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("PENDING"))
        }
    }

    @Test fun `forRoot returns delegations oldest first, scoped to that root`() = runTest {
        val store = store()
        store.record(DelegationKind.MODEL_PICK_BRANCH, rootId = "root-1", now = 20L)
        store.record(DelegationKind.MODEL_PICK_BRANCH, rootId = "root-1", now = 10L)
        store.record(DelegationKind.MODEL_PICK_BRANCH, rootId = "root-2", now = 5L)
        val ordered = store.forRoot("root-1")
        assertEquals(2, ordered.size)
        assertEquals(listOf(10L, 20L), ordered.map { it.at })
    }

    @Test fun `the delegations flow observes inserts`() = runTest {
        val store = store()
        store.record(DelegationKind.MODEL_PICK_BRANCH, rootId = "root-1", now = 1L)
        assertEquals(1, store.delegations.first().size)
    }
}
