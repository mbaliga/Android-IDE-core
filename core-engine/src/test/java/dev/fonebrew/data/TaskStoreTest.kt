package dev.fonebrew.data

import dev.fonebrew.data.dao.TaskDao
import dev.fonebrew.data.entity.TaskEntity
import dev.fonebrew.domain.tasks.TaskSource
import dev.fonebrew.domain.tasks.TaskState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [TaskDao] — Room itself needs a real SQLite binding this JVM gate doesn't
 *  have (no Robolectric/instrumented test here), so [TaskStore]'s CRUD/ordering logic is
 *  exercised against the DAO *interface* instead, exactly as it would be against Room's
 *  generated implementation. */
private class FakeTaskDao : TaskDao {
    private val rows = MutableStateFlow<List<TaskEntity>>(emptyList())

    override suspend fun insert(task: TaskEntity) {
        check(rows.value.none { it.id == task.id }) { "duplicate id ${task.id}" }
        rows.value = rows.value + task
    }

    override suspend fun update(task: TaskEntity) {
        rows.value = rows.value.map { if (it.id == task.id) task else it }
    }

    override suspend fun delete(task: TaskEntity) {
        rows.value = rows.value.filterNot { it.id == task.id }
    }

    override suspend fun getById(id: String): TaskEntity? = rows.value.firstOrNull { it.id == id }

    override fun observeAll(): Flow<List<TaskEntity>> =
        rows.map { list -> list.sortedWith(compareBy({ it.orderKey }, { it.createdAt })) }

    override suspend fun getAll(): List<TaskEntity> = rows.value

    override suspend fun maxOrderKey(): Double? = rows.value.maxOfOrNull { it.orderKey }
}

class TaskStoreTest {

    private fun store() = TaskStore(FakeTaskDao())

    @Test
    fun `create appends with an ascending order key and default TODO state`() = runTest {
        val store = store()
        val a = store.create("first", now = 1L)
        val b = store.create("second", now = 2L)
        assertTrue(b.orderKey > a.orderKey)
        assertEquals(TaskState.TODO, a.state)
        assertEquals(a.createdAt, a.updatedAt)
    }

    @Test
    fun `store CRUD round-trips through observeAll`() = runTest {
        val store = store()
        val task = store.create("buy milk", now = 1L)
        assertEquals(listOf(task), store.tasks.first())

        store.rename(task, title = "buy oat milk", now = 2L)
        val renamed = store.tasks.first().single()
        assertEquals("buy oat milk", renamed.title)
        assertEquals(2L, renamed.updatedAt)

        store.delete(renamed)
        assertTrue(store.tasks.first().isEmpty())
    }

    @Test
    fun `toggleDone sets DONE plus doneAt, and back to TODO clears it`() = runTest {
        val store = store()
        val task = store.create("ship it", now = 1L)

        store.toggleDone(task, now = 10L)
        val done = store.tasks.first().single()
        assertEquals(TaskState.DONE, done.state)
        assertEquals(10L, done.doneAt)

        store.toggleDone(done, now = 20L)
        val reopened = store.tasks.first().single()
        assertEquals(TaskState.TODO, reopened.state)
        assertNull(reopened.doneAt)
    }

    @Test
    fun `setState to DONE stamps doneAt, away from DONE clears it`() = runTest {
        val store = store()
        val task = store.create("triage", now = 1L)
        store.setState(task, TaskState.DOING, now = 2L)
        assertNull(store.tasks.first().single().doneAt)
        store.setState(store.tasks.first().single(), TaskState.DONE, now = 3L)
        assertEquals(3L, store.tasks.first().single().doneAt)
    }

    @Test
    fun `reorder places a row strictly between its new neighbours`() = runTest {
        val store = store()
        val a = store.create("a", now = 1L)
        val b = store.create("b", now = 2L)
        val c = store.create("c", now = 3L)
        // Move c between a and b.
        store.reorder(c, before = a.orderKey, after = b.orderKey, now = 4L)
        val ordered = store.tasks.first()
        assertEquals(listOf("a", "c", "b"), ordered.map { it.title })
    }

    @Test
    fun `createFrom carries source and sourceRef for paid-layer promotion`() = runTest {
        val store = store()
        val task = store.createFrom(
            title = "fix crash",
            source = TaskSource.AUDIT,
            sourceRef = "audit-item-7",
            now = 1L,
        )
        assertEquals(TaskSource.AUDIT, task.source)
        assertEquals("audit-item-7", task.sourceRef)
    }

    @Test
    fun `createFrom persists notes so a converted turn keeps its body`() = runTest {
        val store = store()
        store.createFrom(
            title = "answer from chat",
            source = TaskSource.CHAT,
            sourceRef = "node.abc123",
            notes = "full turn text, markdown intact",
            now = 1L,
        )
        assertEquals("full turn text, markdown intact", store.tasks.first().single().notes)
    }

    @Test
    fun `createFrom defaults notes to empty when the caller supplies none`() = runTest {
        val store = store()
        store.createFrom(title = "bare", source = TaskSource.AUDIT, sourceRef = null, now = 1L)
        assertEquals("", store.tasks.first().single().notes)
    }

    // ── setSpan (Waterfall writer, docs/P5_INVENTORY.md §2) ────────────────────────────────

    @Test
    fun `setSpan writes startAt and endAt`() = runTest {
        val store = store()
        val task = store.create("design the header", now = 1L)
        store.setSpan(task, startAt = 100L, endAt = 200L, now = 2L)
        val updated = store.tasks.first().single()
        assertEquals(100L, updated.startAt)
        assertEquals(200L, updated.endAt)
        assertEquals(2L, updated.updatedAt)
    }

    @Test
    fun `setSpan allows an open-ended start with no end`() = runTest {
        val store = store()
        val task = store.create("ongoing", now = 1L)
        store.setSpan(task, startAt = 100L, endAt = null, now = 2L)
        val updated = store.tasks.first().single()
        assertEquals(100L, updated.startAt)
        assertNull(updated.endAt)
    }

    @Test
    fun `setSpan clears both bounds back to null`() = runTest {
        val store = store()
        val task = store.create("clear me", now = 1L)
        store.setSpan(task, startAt = 100L, endAt = 200L, now = 2L)
        val spanned = store.tasks.first().single()
        store.setSpan(spanned, startAt = null, endAt = null, now = 3L)
        val cleared = store.tasks.first().single()
        assertNull(cleared.startAt)
        assertNull(cleared.endAt)
    }

    @Test
    fun `setSpan rejects a start strictly after its end`() = runTest {
        val store = store()
        val task = store.create("backwards", now = 1L)
        try {
            store.setSpan(task, startAt = 200L, endAt = 100L)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("200"))
            assertTrue(e.message!!.contains("100"))
        }
        // Rejected write leaves the row untouched.
        assertNull(store.tasks.first().single().startAt)
    }

    @Test
    fun `setSpan allows a point-in-time span where start equals end`() = runTest {
        val store = store()
        val task = store.create("same day", now = 1L)
        store.setSpan(task, startAt = 100L, endAt = 100L, now = 2L)
        val updated = store.tasks.first().single()
        assertEquals(100L, updated.startAt)
        assertEquals(100L, updated.endAt)
    }

    // ── setDependsOn (Waterfall writer, docs/P5_INVENTORY.md §2) ───────────────────────────

    @Test
    fun `setDependsOn writes a dependency list naming existing tasks`() = runTest {
        val store = store()
        val a = store.create("a", now = 1L)
        val b = store.create("b", now = 2L)
        store.setDependsOn(b, listOf(a.id), now = 3L)
        val updated = store.tasks.first().single { it.id == b.id }
        assertEquals(listOf(a.id), updated.dependsOn)
        assertEquals(3L, updated.updatedAt)
    }

    @Test
    fun `setDependsOn rejects a target that does not exist`() = runTest {
        val store = store()
        val a = store.create("a", now = 1L)
        try {
            store.setDependsOn(a, listOf("no-such-task"))
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("no-such-task"))
        }
        assertTrue(store.tasks.first().single().dependsOn.isEmpty())
    }

    @Test
    fun `setDependsOn rejects self-dependency`() = runTest {
        val store = store()
        val a = store.create("a", now = 1L)
        try {
            store.setDependsOn(a, listOf(a.id))
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains(a.id))
        }
    }

    @Test
    fun `setDependsOn rejects a direct two-task cycle`() = runTest {
        val store = store()
        val a = store.create("a", now = 1L)
        val b = store.create("b", now = 2L)
        // b depends on a — fine, no cycle yet.
        store.setDependsOn(b, listOf(a.id), now = 3L)
        // Now closing it: a depends on b would make a -> b -> a.
        val freshA = store.tasks.first().single { it.id == a.id }
        try {
            store.setDependsOn(freshA, listOf(b.id))
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("cycle"))
        }
        assertTrue(store.tasks.first().single { it.id == a.id }.dependsOn.isEmpty())
    }

    @Test
    fun `setDependsOn rejects a longer dependency cycle through a third task`() = runTest {
        val store = store()
        val a = store.create("a", now = 1L)
        val b = store.create("b", now = 2L)
        val c = store.create("c", now = 3L)
        // b depends on c, c depends on a: fine so far, no cycle.
        store.setDependsOn(store.tasks.first().single { it.id == b.id }, listOf(c.id), now = 4L)
        store.setDependsOn(store.tasks.first().single { it.id == c.id }, listOf(a.id), now = 5L)
        // Now closing it: a depends on b would make a -> b -> c -> a, a cycle.
        val freshA = store.tasks.first().single { it.id == a.id }
        try {
            store.setDependsOn(freshA, listOf(b.id))
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("cycle"))
        }
        assertTrue(store.tasks.first().single { it.id == a.id }.dependsOn.isEmpty())
    }

    @Test
    fun `setDependsOn replaces the whole set, not an incremental add`() = runTest {
        val store = store()
        val a = store.create("a", now = 1L)
        val b = store.create("b", now = 2L)
        val c = store.create("c", now = 3L)
        var target = store.create("target", now = 4L)
        store.setDependsOn(target, listOf(a.id, b.id), now = 5L)
        target = store.tasks.first().single { it.id == target.id }
        store.setDependsOn(target, listOf(c.id), now = 6L)
        val updated = store.tasks.first().single { it.id == target.id }
        assertEquals(listOf(c.id), updated.dependsOn)
    }
}
