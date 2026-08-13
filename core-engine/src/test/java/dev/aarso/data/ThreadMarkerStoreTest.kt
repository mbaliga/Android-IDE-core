package dev.aarso.data

import dev.aarso.data.dao.ThreadMarkerDao
import dev.aarso.data.entity.ThreadMarkerEntity
import dev.aarso.domain.thread.ThreadMarkerKind
import dev.aarso.domain.thread.ThreadMarkerSource
import dev.aarso.domain.tree.TreeFork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory fake — same rationale as [CurationStoreTest]'s FakeVersionDao: Room needs a real
 *  SQLite binding this JVM gate doesn't have, so [ThreadMarkerStore]'s logic is exercised against
 *  the DAO interface instead. */
private class FakeThreadMarkerDao : ThreadMarkerDao {
    private val rows = MutableStateFlow<List<ThreadMarkerEntity>>(emptyList())
    override suspend fun insert(marker: ThreadMarkerEntity) {
        check(rows.value.none { it.id == marker.id }) { "duplicate id ${marker.id}" }
        rows.value = rows.value + marker
    }
    override suspend fun update(marker: ThreadMarkerEntity) { rows.value = rows.value.map { if (it.id == marker.id) marker else it } }
    override suspend fun delete(marker: ThreadMarkerEntity) { rows.value = rows.value.filterNot { it.id == marker.id } }
    override suspend fun getById(id: String): ThreadMarkerEntity? = rows.value.firstOrNull { it.id == id }
    override suspend fun forRoot(rootId: String): List<ThreadMarkerEntity> =
        rows.value.filter { it.rootId == rootId }.sortedBy { it.at }
    override suspend fun forAnchor(msgId: String): List<ThreadMarkerEntity> =
        rows.value.filter { it.anchorMsgId == msgId }.sortedByDescending { it.at }
    override fun observeAll(): Flow<List<ThreadMarkerEntity>> = rows
}

class ThreadMarkerStoreTest {

    private fun store() = ThreadMarkerStore(FakeThreadMarkerDao())

    @Test fun `markChapter then forAnchor round-trips`() = runTest {
        val store = store()
        val marker = store.markChapter("root-1", "msg-42", label = "Auth flow rewrite", now = 10L)
        assertEquals(ThreadMarkerKind.CHAPTER, marker.kind)
        assertEquals(ThreadMarkerSource.USER, marker.source)
        val found = store.forAnchor("msg-42")
        assertEquals(1, found.size)
        assertEquals("Auth flow rewrite", found.single().label)
    }

    @Test fun `renameChapter updates label and note in place`() = runTest {
        val store = store()
        val marker = store.markChapter("root-1", "msg-42", label = "draft", now = 1L)
        store.renameChapter(marker, label = "final", note = "shipped")
        val updated = store.forAnchor("msg-42").single()
        assertEquals("final", updated.label)
        assertEquals("shipped", updated.note)
    }

    @Test fun `renameChapter rejects a non-CHAPTER marker`() = runTest {
        val store = store()
        val marker = store.markSessionStart("root-1", now = 1L)
        try {
            store.renameChapter(marker, label = "should fail")
            org.junit.Assert.fail("expected IllegalArgumentException for renaming a non-CHAPTER marker")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("CHAPTER"))
        }
    }

    @Test fun `removeChapter deletes the row`() = runTest {
        val store = store()
        val marker = store.markChapter("root-1", "msg-42", label = "draft", now = 1L)
        store.removeChapter(marker)
        assertTrue(store.forAnchor("msg-42").isEmpty())
    }

    @Test fun `markSessionStart creates a marker with no label and an optional anchor`() = runTest {
        val store = store()
        val marker = store.markSessionStart("root-1", now = 5L)
        assertEquals(ThreadMarkerKind.SESSION_START, marker.kind)
        assertNull(marker.label)
        assertNull(marker.anchorMsgId)
        assertEquals(1, store.forRoot("root-1").size)
    }

    @Test fun `forRoot returns markers oldest first`() = runTest {
        val store = store()
        store.markChapter("root-1", "msg-2", label = "second", now = 20L)
        store.markChapter("root-1", "msg-1", label = "first", now = 10L)
        val ordered = store.forRoot("root-1")
        assertEquals(listOf("first", "second"), ordered.map { it.label })
    }

    @Test fun `markers scoped to a different root are not returned`() = runTest {
        val store = store()
        store.markChapter("root-1", "msg-1", label = "in root 1", now = 1L)
        store.markChapter("root-2", "msg-2", label = "in root 2", now = 2L)
        assertEquals(1, store.forRoot("root-1").size)
        assertEquals(1, store.forRoot("root-2").size)
    }

    @Test fun `the markers flow observes inserts`() = runTest {
        val store = store()
        store.markChapter("root-1", "msg-1", label = "chapter one", now = 1L)
        assertEquals(1, store.markers.first().size)
    }

    // ---- markLineageSource (THREAD_TOPOLOGY_PLAN.md WP2) --------------------------------------

    @Test fun `markLineageSource creates a SYSTEM LINEAGE_SRC marker with no anchor`() = runTest {
        val store = store()
        val marker = store.markLineageSource(
            newRootId = "new-root", srcRootId = "old-root", srcNodeId = "old-msg",
            lineageKind = TreeFork.LineageKind.FORK, now = 10L,
        )
        assertEquals(ThreadMarkerKind.LINEAGE_SRC, marker.kind)
        assertEquals(ThreadMarkerSource.SYSTEM, marker.source)
        assertNull(marker.anchorMsgId)
        assertEquals("new-root", marker.rootId)
    }

    @Test fun `markLineageSource payload carries the src pointers and lineage kind`() = runTest {
        val store = store()
        val marker = store.markLineageSource(
            newRootId = "new-root", srcRootId = "old-root", srcNodeId = "old-msg",
            lineageKind = TreeFork.LineageKind.SPAWN, now = 10L,
        )
        val payload = org.json.JSONObject(marker.payloadJson!!)
        assertEquals("old-root", payload.getString("srcRootId"))
        assertEquals("old-msg", payload.getString("srcNodeId"))
        assertEquals("SPAWN", payload.getString("lineageKind"))
    }

    @Test fun `markLineageSource is scoped to the new root via forRoot`() = runTest {
        val store = store()
        store.markLineageSource("new-root", "old-root", "old-msg", TreeFork.LineageKind.FORK, now = 1L)
        val found = store.forRoot("new-root")
        assertEquals(1, found.size)
        assertEquals(ThreadMarkerKind.LINEAGE_SRC, found.single().kind)
    }
}
