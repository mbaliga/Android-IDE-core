package dev.aarso.data

import dev.aarso.data.dao.TaskDao
import dev.aarso.data.entity.TaskEntity
import dev.aarso.domain.tasks.TaskSource
import dev.aarso.domain.tasks.TaskState
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
}
