package dev.fonebrew.data

import dev.fonebrew.data.dao.TaskDao
import dev.fonebrew.data.entity.TaskEntity
import dev.fonebrew.domain.tasks.TaskOrdering
import dev.fonebrew.domain.tasks.TaskSource
import dev.fonebrew.domain.tasks.TaskState
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * The Task substrate (CORE_PHASES.md §"Data models"): free floor reads/writes only
 * title/notes/state(TODO↔DONE)/orderKey/dueAt through this API. Paid lenses (Board/
 * List/Waterfall) write the other fields directly via the same [TaskDao] — same table,
 * no migration on unlock.
 */
class TaskStore(private val dao: TaskDao) {

    val tasks: Flow<List<TaskEntity>> = dao.observeAll()

    /** New task, appended to the end of manual order. */
    suspend fun create(
        title: String,
        notes: String = "",
        dueAt: Long? = null,
        now: Long = System.currentTimeMillis(),
    ): TaskEntity {
        val task = TaskEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            notes = notes,
            orderKey = TaskOrdering.keyForAppend(dao.maxOrderKey()),
            dueAt = dueAt,
            createdAt = now,
            updatedAt = now,
        )
        dao.insert(task)
        return task
    }

    suspend fun rename(
        task: TaskEntity,
        title: String,
        notes: String = task.notes,
        now: Long = System.currentTimeMillis(),
    ) {
        dao.update(task.copy(title = title, notes = notes, updatedAt = now))
    }

    suspend fun setDueAt(task: TaskEntity, dueAt: Long?, now: Long = System.currentTimeMillis()) {
        dao.update(task.copy(dueAt = dueAt, updatedAt = now))
    }

    /** The free floor's only state transition: TODO ↔ DONE (swipe/checkbox). Paid Board
     *  lens writes DOING/BLOCKED directly — [setState] handles any of the four. */
    suspend fun toggleDone(task: TaskEntity, now: Long = System.currentTimeMillis()) {
        val done = task.state != TaskState.DONE
        dao.update(
            task.copy(
                state = if (done) TaskState.DONE else TaskState.TODO,
                doneAt = if (done) now else null,
                updatedAt = now,
            ),
        )
    }

    suspend fun setState(task: TaskEntity, state: TaskState, now: Long = System.currentTimeMillis()) {
        dao.update(
            task.copy(
                state = state,
                doneAt = if (state == TaskState.DONE) now else null,
                updatedAt = now,
            ),
        )
    }

    /** Drag-reorder: [before]/[after] are the new neighbours' current [TaskEntity.orderKey]. */
    suspend fun reorder(
        task: TaskEntity,
        before: Double?,
        after: Double?,
        now: Long = System.currentTimeMillis(),
    ) {
        dao.update(task.copy(orderKey = TaskOrdering.keyBetween(before, after), updatedAt = now))
    }

    suspend fun delete(task: TaskEntity) = dao.delete(task)

    /** Paid-layer seed insert (task templates, audit/incident promotion, §5.3/§8.4) —
     *  free UI never calls this directly, but the store is the same either way. */
    suspend fun createFrom(
        title: String,
        source: TaskSource,
        sourceRef: String?,
        projectId: String? = null,
        tags: List<String> = emptyList(),
        now: Long = System.currentTimeMillis(),
    ): TaskEntity {
        val task = TaskEntity(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            title = title,
            orderKey = TaskOrdering.keyForAppend(dao.maxOrderKey()),
            source = source,
            sourceRef = sourceRef,
            tags = tags,
            createdAt = now,
            updatedAt = now,
        )
        dao.insert(task)
        return task
    }
}
