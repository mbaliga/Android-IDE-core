package dev.fonebrew.data.search

import dev.fonebrew.data.entity.TaskEntity
import dev.fonebrew.domain.tasks.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSearchProjectorTest {

    private fun task(
        id: String = "t1",
        title: String = "Fix the build",
        notes: String = "",
        state: TaskState = TaskState.TODO,
    ) = TaskEntity(id = id, title = title, notes = notes, orderKey = 0.0, createdAt = 0, updatedAt = 0, state = state)

    @Test fun `title and body come from title and notes`() {
        val row = TaskSearchProjector.project(listOf(task(title = "Ship v1", notes = "needs a changelog"))).single()
        assertEquals("Ship v1", row.titleRaw)
        assertEquals("needs a changelog", row.bodyRaw)
    }

    @Test fun `state is carried through as the TaskState name`() {
        val row = TaskSearchProjector.project(listOf(task(state = TaskState.DONE))).single()
        assertEquals("DONE", row.state)
    }

    @Test fun `segmented fields are lowercased for FTS matching`() {
        val row = TaskSearchProjector.project(listOf(task(title = "GRADLE Build"))).single()
        assertTrue(row.title.contains("gradle"))
    }

    @Test fun `one row per task, in input order`() {
        val rows = TaskSearchProjector.project(listOf(task(id = "a"), task(id = "b")))
        assertEquals(listOf("a", "b"), rows.map { it.taskId })
    }
}
