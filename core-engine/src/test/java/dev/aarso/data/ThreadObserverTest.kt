package dev.aarso.data

import dev.aarso.data.dao.DelegationEventDao
import dev.aarso.data.dao.EmbeddingDao
import dev.aarso.data.dao.MessageNodeDao
import dev.aarso.data.dao.ThreadMarkerDao
import dev.aarso.data.dao.TokenCountDao
import dev.aarso.data.entity.DelegationEventEntity
import dev.aarso.data.entity.MessageEmbeddingEntity
import dev.aarso.data.entity.MessageNodeEntity
import dev.aarso.data.entity.ThreadMarkerEntity
import dev.aarso.data.entity.TokenCountEntity
import dev.aarso.domain.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** In-memory fake, same rationale as [ThreadMarkerStoreTest]'s FakeThreadMarkerDao. */
private class FakeMessageNodeDao : MessageNodeDao {
    private val rows = MutableStateFlow<List<MessageNodeEntity>>(emptyList())
    override suspend fun insert(node: MessageNodeEntity) { rows.value = rows.value + node }
    override suspend fun getById(id: String): MessageNodeEntity? = rows.value.firstOrNull { it.id == id }
    override fun observeAll(): Flow<List<MessageNodeEntity>> = rows
    override fun observeChildren(parentId: String): Flow<List<MessageNodeEntity>> =
        MutableStateFlow(rows.value.filter { it.parentId == parentId })
    override suspend fun pathToRoot(leafId: String): List<MessageNodeEntity> {
        val byId = rows.value.associateBy { it.id }
        val out = ArrayList<MessageNodeEntity>()
        var current = byId[leafId]
        while (current != null) {
            out += current
            current = current.parentId?.let { byId[it] }
        }
        return out
    }
}

private class FakeTokenCountDao : TokenCountDao {
    override suspend fun upsert(count: TokenCountEntity) {}
    override suspend fun forNode(nodeId: String): List<TokenCountEntity> = emptyList()
}

private class FakeEmbeddingDao : EmbeddingDao {
    override suspend fun upsert(embedding: MessageEmbeddingEntity) {}
    override suspend fun forNode(nodeId: String): MessageEmbeddingEntity? = null
    override suspend fun count(): Int = 0
}

/** Named distinctly from [dev.aarso.data.ThreadMarkerStoreTest]'s own same-shaped fake — Kotlin
 *  top-level `private` declarations are file-private but still share one name resolution scope
 *  per compilation, so two files in this package can't both declare `FakeThreadMarkerDao`. */
private class ObserverFakeThreadMarkerDao : ThreadMarkerDao {
    private val rows = MutableStateFlow<List<ThreadMarkerEntity>>(emptyList())
    override suspend fun insert(marker: ThreadMarkerEntity) { rows.value = rows.value + marker }
    override suspend fun update(marker: ThreadMarkerEntity) {}
    override suspend fun delete(marker: ThreadMarkerEntity) { rows.value = rows.value.filterNot { it.id == marker.id } }
    override suspend fun getById(id: String): ThreadMarkerEntity? = rows.value.firstOrNull { it.id == id }
    override suspend fun forRoot(rootId: String): List<ThreadMarkerEntity> = rows.value.filter { it.rootId == rootId }
    override suspend fun forAnchor(msgId: String): List<ThreadMarkerEntity> = rows.value.filter { it.anchorMsgId == msgId }
    override fun observeAll(): Flow<List<ThreadMarkerEntity>> = rows
}

/** See [ObserverFakeThreadMarkerDao]'s KDoc for why this isn't just `FakeDelegationEventDao`. */
private class ObserverFakeDelegationEventDao : DelegationEventDao {
    private val rows = MutableStateFlow<List<DelegationEventEntity>>(emptyList())
    override suspend fun insert(event: DelegationEventEntity) { rows.value = rows.value + event }
    override suspend fun update(event: DelegationEventEntity) {}
    override suspend fun getById(id: String): DelegationEventEntity? = rows.value.firstOrNull { it.id == id }
    override suspend fun forRoot(rootId: String): List<DelegationEventEntity> = rows.value.filter { it.rootId == rootId }
    override fun observeAll(): Flow<List<DelegationEventEntity>> = rows
}

class ThreadObserverTest {

    private fun repository(nodeDao: MessageNodeDao) =
        MessageTreeRepository(nodeDao, FakeTokenCountDao(), FakeEmbeddingDao())

    private fun observer(
        nodeDao: MessageNodeDao = FakeMessageNodeDao(),
        markerDao: ThreadMarkerDao = ObserverFakeThreadMarkerDao(),
        delegationDao: DelegationEventDao = ObserverFakeDelegationEventDao(),
        enabled: () -> Boolean = { true },
    ) = ThreadObserver(
        repository = repository(nodeDao),
        markerStore = ThreadMarkerStore(markerDao),
        delegationStore = DelegationStore(delegationDao),
        enabled = enabled,
    )

    private fun node(id: String, parentId: String?, createdAt: Long) = MessageNodeEntity(
        id = id, parentId = parentId, role = Role.USER.wire, content = "x", modelId = null,
        createdAt = createdAt, metadataJson = null,
    )

    // ---- the off-by-default gate ----------------------------------------------------------------

    @Test fun `snapshot returns null when the observer toggle is off`() = runTest {
        val nodeDao = FakeMessageNodeDao()
        nodeDao.insert(node("root-1", null, 100L))
        val obs = observer(nodeDao = nodeDao, enabled = { false })
        assertNull(obs.snapshot())
    }

    @Test fun `remarks returns an empty list, not a fabricated remark, when the toggle is off`() = runTest {
        val nodeDao = FakeMessageNodeDao()
        nodeDao.insert(node("root-1", null, 100L))
        val obs = observer(nodeDao = nodeDao, enabled = { false })
        assertTrue(obs.remarks().isEmpty())
    }

    @Test fun `remarksSince returns an empty list when the toggle is off, even mid-comparison`() = runTest {
        val obs = observer(enabled = { false })
        val emptyGraph = dev.aarso.domain.thread.ThreadGraph(generatedAtUtc = Instant.parse("2026-08-12T00:00:00Z"))
        assertTrue(obs.remarksSince(emptyGraph).isEmpty())
    }

    // ---- on: reads the same three Room stores every other reader uses --------------------------

    @Test fun `snapshot projects the tree, markers, and delegations when enabled`() = runTest {
        val nodeDao = FakeMessageNodeDao()
        nodeDao.insert(node("root-1", null, 100L))
        nodeDao.insert(node("msg-42", "root-1", 200L))
        val markerDao = ObserverFakeThreadMarkerDao()
        markerDao.insert(
            ThreadMarkerEntity(
                id = "mk-1", rootId = "root-1", anchorMsgId = "msg-42",
                kind = dev.aarso.domain.thread.ThreadMarkerKind.CHAPTER, label = "Auth flow rewrite",
                note = null, at = 250L, source = dev.aarso.domain.thread.ThreadMarkerSource.USER, payloadJson = null,
            ),
        )
        val delegationDao = ObserverFakeDelegationEventDao()
        delegationDao.insert(
            DelegationEventEntity(
                id = "dg-1", at = 260L, kind = dev.aarso.domain.thread.DelegationKind.MODEL_PICK_BRANCH,
                rootId = "root-1", anchorMsgId = "msg-42", chosenRef = "msg-51", alternatives = emptyList(),
                outcome = dev.aarso.domain.thread.DelegationOutcome.PENDING, outcomeAt = null,
            ),
        )

        val obs = observer(nodeDao = nodeDao, markerDao = markerDao, delegationDao = delegationDao)
        val graph = obs.snapshot()!!
        assertEquals(4, graph.nodes.size)
        assertTrue(graph.nodes.any { it.id == "mk-1" })
        assertTrue(graph.nodes.any { it.id == "dg-1" })

        val remarks = obs.remarks()
        assertTrue(remarks.any { it.contains("message") })
        assertTrue(remarks.any { it.contains("marker") })
        assertTrue(remarks.any { it.contains("delegation") })
    }

    @Test fun `remarksSince describes what changed between an earlier snapshot and now`() = runTest {
        val nodeDao = FakeMessageNodeDao()
        nodeDao.insert(node("root-1", null, 100L))
        val obs = observer(nodeDao = nodeDao)
        val before = obs.snapshot()!!

        nodeDao.insert(node("msg-42", "root-1", 200L))
        val remarks = obs.remarksSince(before)
        assertTrue(remarks.any { it.contains("node") && it.contains("added") })
    }

    @Test fun `an empty tree with the toggle on produces the honest empty-graph snapshot, not null`() = runTest {
        val obs = observer()
        val graph = obs.snapshot()!!
        assertTrue(graph.nodes.isEmpty())
        assertEquals(listOf("No conversation activity captured yet."), obs.remarks())
    }
}
