package dev.fonebrew.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.fonebrew.domain.tasks.TaskSource
import dev.fonebrew.domain.tasks.TaskState

/**
 * Room representation of the Task substrate (CORE_PHASES.md §"Data models"): the free
 * floor reads/writes only [title]/[notes]/[state] (TODO↔DONE)/[orderKey]/[dueAt] — every
 * other field is dormant in free, set only by an above-core layer's paid lenses (Board/
 * List/Waterfall). One table either way, so unlocking Studio migrates nothing.
 */
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val projectId: String? = null,
    val title: String,
    val notes: String = "",
    val state: TaskState = TaskState.TODO,
    /** Manual ordering (fractional insert) — see [dev.fonebrew.domain.tasks.TaskOrdering]. */
    val orderKey: Double,
    val dueAt: Long? = null,
    /** Waterfall span — Studio-set, dormant in free. */
    val startAt: Long? = null,
    val endAt: Long? = null,
    /** Waterfall dependency edges — Studio-set, dormant in free. */
    val dependsOn: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val source: TaskSource = TaskSource.MANUAL,
    val sourceRef: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val doneAt: Long? = null,
)
