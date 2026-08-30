package dev.fonebrew.data.search

import dev.fonebrew.data.entity.TaskEntity
import dev.fonebrew.domain.search.Segmenter

/**
 * Flattens [TaskEntity] rows into search-index rows — the `task:` half of the search-corpus
 * expansion (Field.kt's [dev.fonebrew.domain.search.query.Field.TASK]). Pure Kotlin, same
 * "presenter" convention as [SearchProjector]/[LoopSearchProjector]: a stateless transform, JVM-
 * tested without Room.
 *
 * Indexes exactly the fields the task named in the build plan — title, detail (notes), state —
 * `dueAt`/`tags`/Studio's Board-lens fields are dormant in the free floor (see [TaskEntity]'s own
 * KDoc) and out of scope here the same way §5b/§5c stay out of scope elsewhere in this app.
 */
object TaskSearchProjector {

    const val PROJECTION_VERSION = 1L

    data class Row(
        val taskId: String,
        val title: String,
        val body: String,
        val titleRaw: String,
        val bodyRaw: String,
        /** [dev.fonebrew.domain.tasks.TaskState] name (TODO/DOING/BLOCKED/DONE) — what a
         *  `task:<value>` facet value matches against. */
        val state: String,
        val updatedAt: Long,
        val createdAt: Long,
        val projectionVersion: Long = PROJECTION_VERSION,
    )

    fun project(tasks: List<TaskEntity>): List<Row> = tasks.map(::projectOne)

    private fun projectOne(task: TaskEntity): Row {
        val bodyRaw = task.notes
        return Row(
            taskId = task.id,
            title = Segmenter.tokenizeForIndex(task.title),
            body = Segmenter.tokenizeForIndex(bodyRaw),
            titleRaw = task.title,
            bodyRaw = bodyRaw,
            state = task.state.name,
            updatedAt = task.updatedAt,
            createdAt = task.createdAt,
        )
    }
}
