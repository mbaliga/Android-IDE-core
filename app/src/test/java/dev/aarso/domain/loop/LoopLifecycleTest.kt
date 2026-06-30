package dev.aarso.domain.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopLifecycleTest {

    private val draft = Loop(id = "1", name = "Late-attendance audit", bpmnXml = "<bpmn/>", createdAt = 100)

    @Test fun `only unused loops are editable`() {
        assertTrue(LoopLifecycle.isEditable(draft))
        assertFalse(LoopLifecycle.isEditable(draft.copy(state = LoopState.RUNNING)))
        assertFalse(LoopLifecycle.isEditable(draft.copy(state = LoopState.RETIRED)))
    }

    @Test fun `trigger takes unused to running and stamps the run`() {
        val r = LoopLifecycle.trigger(draft, now = 200)
        assertEquals(LoopState.RUNNING, r.state)
        assertEquals(200L, r.lastRunAt)
    }

    @Test fun `cannot trigger a non-unused loop`() {
        assertThrows(IllegalArgumentException::class.java) {
            LoopLifecycle.trigger(draft.copy(state = LoopState.RUNNING), now = 200)
        }
    }

    @Test fun `retire takes running to retired`() {
        val running = LoopLifecycle.trigger(draft, 200)
        assertEquals(LoopState.RETIRED, LoopLifecycle.retire(running, 300).state)
    }

    @Test fun `cannot retire a loop that is not running`() {
        assertThrows(IllegalArgumentException::class.java) { LoopLifecycle.retire(draft, 300) }
    }

    @Test fun `duplicating a running loop yields a non-live unused draft`() {
        val running = LoopLifecycle.trigger(draft, 200)
        val copy = LoopLifecycle.duplicate(running, newId = "2", now = 400)
        assertEquals(LoopState.UNUSED, copy.state) // never live by default
        assertEquals("2", copy.id)
        assertEquals("Late-attendance audit (copy)", copy.name)
        assertEquals("<bpmn/>", copy.bpmnXml) // definition carried over
        assertNull(copy.lastRunAt)
    }

    @Test fun `duplicate does not double-suffix copies`() {
        val once = LoopLifecycle.duplicate(draft, "2", 400)
        val twice = LoopLifecycle.duplicate(once, "3", 500)
        assertEquals("Late-attendance audit (copy)", twice.name)
    }

    @Test fun `inState filters for the tabs`() {
        val loops = listOf(
            draft,
            draft.copy(id = "2", state = LoopState.RUNNING),
            draft.copy(id = "3", state = LoopState.RETIRED),
            draft.copy(id = "4", state = LoopState.RUNNING),
        )
        assertEquals(2, LoopLifecycle.inState(loops, LoopState.RUNNING).size)
        assertEquals(1, LoopLifecycle.inState(loops, LoopState.UNUSED).size)
    }
}
