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

    /** Waterfall span writer (Studio's Waterfall lens, journey J8 — `docs/P5_INVENTORY.md` §2
     *  names this exact method as the missing writer that lens was blocked on; `TaskEntity`
     *  already carried [TaskEntity.startAt]/[TaskEntity.endAt] as dormant, Studio-set columns).
     *  Either bound may be cleared independently by passing `null` — a task with only [startAt]
     *  renders open-ended, one with neither renders in the Waterfall's "unscheduled" rail — but
     *  when both are present [startAt] must not be after [endAt]. */
    suspend fun setSpan(
        task: TaskEntity,
        startAt: Long?,
        endAt: Long?,
        now: Long = System.currentTimeMillis(),
    ) {
        if (startAt != null && endAt != null) {
            require(startAt <= endAt) {
                "task span start ($startAt) must be on or before its end ($endAt)"
            }
        }
        dao.update(task.copy(startAt = startAt, endAt = endAt, updatedAt = now))
    }

    /** Waterfall dependency-edge writer (same follow-up as [setSpan]). Replaces [task]'s whole
     *  [TaskEntity.dependsOn] set in one write. Validated against the store's current rows
     *  *before* anything is persisted — reject-the-whole-write, not a partial apply:
     *  - every id in [dependsOn] must name a task that actually exists;
     *  - [task] may not depend on itself;
     *  - the edge may not close a **cycle** — starting from each proposed target and following
     *    the *existing* `dependsOn` edges of other tasks must never lead back to [task.id]. A
     *    cycle would give the Waterfall lens's DAG-only layout ([dev.fonebrew.domain.tasks]'s
     *    consumers, e.g. Studio's `TaskWaterfall.order`) no legal topological order to draw, so
     *    it is rejected here rather than left for a lens to fail on later.
     *
     *  Any violation throws [IllegalArgumentException] with a message naming exactly what was
     *  wrong (which id, which rule) — never a silent partial write.
     */
    suspend fun setDependsOn(
        task: TaskEntity,
        dependsOn: List<String>,
        now: Long = System.currentTimeMillis(),
    ) {
        require(task.id !in dependsOn) { "task '${task.id}' cannot depend on itself" }

        val byId = dao.getAll().associateBy { it.id }
        for (id in dependsOn) {
            require(id in byId) { "dependency target '$id' does not exist" }
        }

        // Would following `dependsOn` edges (as they exist today) from `from` ever reach
        // task.id? If any proposed target does, adding task.id -> target closes a cycle.
        fun leadsBackToTask(from: String, visited: MutableSet<String> = mutableSetOf()): Boolean {
            if (from == task.id) return true
            if (!visited.add(from)) return false
            return byId[from]?.dependsOn.orEmpty().any { leadsBackToTask(it, visited) }
        }
        for (id in dependsOn) {
            require(!leadsBackToTask(id)) {
                "setting '${task.id}' to depend on '$id' would create a dependency cycle"
            }
        }

        dao.update(task.copy(dependsOn = dependsOn, updatedAt = now))
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
