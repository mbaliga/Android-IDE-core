package dev.fonebrew.widget

import dev.fonebrew.data.entity.TaskEntity
import dev.fonebrew.domain.tasks.TaskDue
import dev.fonebrew.domain.tasks.TaskSource
import dev.fonebrew.domain.tasks.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextUpTest {

    private val hour = 60 * 60 * 1000L
    private val day = 24 * hour
    private val now = 1_800_000_000_000L
    private var n = 0

    private fun task(
        id: String = "t${n++}",
        title: String = "task $id",
        state: TaskState = TaskState.TODO,
        dueAt: Long? = null,
        source: TaskSource = TaskSource.MANUAL,
        order: Double = n.toDouble(),
    ) = TaskEntity(
        id = id, title = title, state = state, orderKey = order, dueAt = dueAt, source = source,
        createdAt = 1L, updatedAt = 1L,
    )

    @Test
    fun noTasks_isTheEmptyState() {
        val m = NextUp.build(emptyList(), now)
        assertTrue(m.rows.isEmpty())
        assertEquals(0, m.openCount)
        assertEquals(0, m.overdueCount)
        assertEquals(0, m.dueCount)
        assertNull(m.nextBoundaryAt)
    }

    @Test
    fun doneTasks_neverAppear_andDontCount() {
        val m = NextUp.build(listOf(task("a", state = TaskState.DONE), task("b")), now)
        assertEquals(listOf("b"), m.rows.map { it.taskId })
        assertEquals(1, m.openCount)
    }

    @Test
    fun dueWithinADay_comesFirst_mostOverdueFirst_thenTheUsersOwnOrder() {
        val rows = listOf(
            task("undated-1"),
            task("later", dueAt = now + 3 * day),
            task("today", dueAt = now + 5 * hour),
            task("undated-2"),
            task("overdue", dueAt = now - 2 * hour),
            task("soon", dueAt = now + 10 * 60 * 1000L),
        )
        val m = NextUp.build(rows, now)
        assertEquals(listOf("overdue", "soon", "today", "undated-1", "later"), m.rows.map { it.taskId })
        assertEquals(6, m.openCount)
        // Counted apart, so a task weeks overdue is never reported as "due within a day".
        assertEquals("overdue", 1, m.overdueCount)
        assertEquals("soon + today", 2, m.dueCount)
        assertEquals(
            listOf(DueKind.OVERDUE, DueKind.SOON, DueKind.TODAY, DueKind.NONE, DueKind.IN_DAYS),
            m.rows.map { it.due },
        )
        assertEquals(3, m.rows.last().inDays)
    }

    @Test
    fun rowsAreCapped_butCountsStayTheTrueTotals() {
        val rows = (1..12).map { task("t$it", dueAt = if (it <= 7) now + hour * 2 else null) }
        val m = NextUp.build(rows, now)
        assertEquals(NextUp.MAX_ROWS, m.rows.size)
        assertEquals(12, m.openCount)
        assertEquals(0, m.overdueCount)
        assertEquals(7, m.dueCount)
        assertEquals(3, NextUp.build(rows, now, maxRows = 3).rows.size)
    }

    @Test
    fun aTitleCutFromAChatTurn_isWithheld_otherSourcesAreShown() {
        val m = NextUp.build(
            listOf(
                task("chat", title = "Here is the private thing you asked me about.", source = TaskSource.CHAT),
                task("manual", title = "Renew the domain"),
                task("audit", title = "Fix lint", source = TaskSource.AUDIT),
            ),
            now,
        )
        val byId = m.rows.associateBy { it.taskId }
        assertNull("conversation text never reaches the home screen", byId.getValue("chat").title)
        assertTrue(byId.getValue("chat").withheld)
        assertEquals("Renew the domain", byId.getValue("manual").title)
        assertEquals("Fix lint", byId.getValue("audit").title)
    }

    @Test
    fun titlesAreFlattenedToOneLine_andCapped() {
        val long = "word ".repeat(60)
        val m = NextUp.build(listOf(task("a", title = "Line one\nline\ttwo  "), task("b", title = long)), now)
        assertEquals("Line one line two", m.rows[0].title)
        val capped = m.rows[1].title!!
        assertEquals(NextUp.MAX_TITLE_CHARS, capped.length)
        assertTrue(capped.endsWith("…"))
    }

    @Test
    fun theStudioOnlyStatesAreCarried() {
        val m = NextUp.build(listOf(task("a", state = TaskState.DOING), task("b", state = TaskState.BLOCKED)), now)
        assertEquals(listOf(TaskState.DOING, TaskState.BLOCKED), m.rows.map { it.state })
    }

    @Test
    fun dueKinds_useTheSameThresholdsAsTheAppsOwnToDoRow() {
        val offsets = listOf(-day, -1L, 0L, 1L, hour - 1, hour, hour + 1, day - 1, day, day + 1, 2 * day + 5, 30 * day)
        for (offset in offsets) {
            val dueAt = now + offset
            val (kind, days) = NextUp.dueKind(dueAt, now)
            val app = TaskDue.label(dueAt, now)
            val ours = when (kind) {
                DueKind.OVERDUE -> "overdue"
                DueKind.SOON -> "due soon"
                DueKind.TODAY -> "today"
                DueKind.IN_DAYS -> "in ${days}d"
                DueKind.NONE -> error("dated task")
            }
            assertEquals("offset $offset", app.text, ours)
            assertEquals(app.overdue, kind == DueKind.OVERDUE)
        }
        assertEquals(DueKind.NONE, NextUp.dueKind(null, now).first)
    }

    @Test
    fun theBoundary_isExactlyWhereTheLabelChanges() {
        val offsets = listOf(1L, 30 * 60 * 1000L, hour - 1, hour, hour + 7, day - 1, day, day + 1, 3 * day + 12345, 40 * day)
        for (offset in offsets) {
            val dueAt = now + offset
            val at = NextUp.boundaryAfter(dueAt, now)!!
            assertTrue("boundary is in the future (offset $offset)", at > now)
            assertEquals("unchanged just before (offset $offset)", NextUp.dueKind(dueAt, now), NextUp.dueKind(dueAt, at - 1))
            assertNotEquals("changed at the boundary (offset $offset)", NextUp.dueKind(dueAt, now), NextUp.dueKind(dueAt, at))
        }
        assertNull("an overdue task never changes again", NextUp.boundaryAfter(now - 1, now))
    }

    @Test
    fun theModelsNextBoundary_isTheEarliestAcrossOpenDatedTasks() {
        val m = NextUp.build(
            listOf(
                task("a", dueAt = now + 3 * day + 2 * hour),
                task("b", dueAt = now + 2 * hour),
                task("done", dueAt = now + 1000, state = TaskState.DONE),
                task("overdue", dueAt = now - 5),
            ),
            now,
        )
        assertEquals(NextUp.boundaryAfter(now + 2 * hour, now), m.nextBoundaryAt)
    }
}
