package dev.fonebrew.domain.loop.authoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** §10's two-row table, plus the "no pointer-only state" invariant. */
class PointerLayoutMachineTest {

    private val work = AuthoringContinuity(
        selectedNodeId = "critic",
        draftRevision = 7L,
        viewportFocusNodeId = "proposer",
        unsavedEditorState = "label=refine",
    )

    @Test
    fun `starts in PHONE_LAYOUT`() {
        assertEquals(PointerLayout.PHONE_LAYOUT, PointerLayoutMachine.start().layout)
    }

    @Test
    fun `connect moves PHONE_LAYOUT to POINTER_LAYOUT`() {
        val s = PointerLayoutMachine.apply(PointerLayoutMachine.start(work), PointerLayoutMachine.Event.PointerConnected)
        assertEquals(PointerLayout.POINTER_LAYOUT, s.layout)
    }

    @Test
    fun `disconnect moves POINTER_LAYOUT back to PHONE_LAYOUT`() {
        val s0 = PointerLayoutMachine.apply(PointerLayoutMachine.start(work), PointerLayoutMachine.Event.PointerConnected)
        val s1 = PointerLayoutMachine.apply(s0, PointerLayoutMachine.Event.PointerDisconnected)
        assertEquals(PointerLayout.PHONE_LAYOUT, s1.layout)
    }

    @Test
    fun `repeated events are idempotent`() {
        val connected = PointerLayoutMachine.apply(PointerLayoutMachine.start(work), PointerLayoutMachine.Event.PointerConnected)
        assertEquals(connected, PointerLayoutMachine.apply(connected, PointerLayoutMachine.Event.PointerConnected))
        val phone = PointerLayoutMachine.apply(connected, PointerLayoutMachine.Event.PointerDisconnected)
        assertEquals(phone, PointerLayoutMachine.apply(phone, PointerLayoutMachine.Event.PointerDisconnected))
        assertEquals(PointerLayoutMachine.start(work), PointerLayoutMachine.apply(PointerLayoutMachine.start(work), PointerLayoutMachine.Event.PointerDisconnected))
    }

    @Test
    fun `selection draft revision viewport and unsaved state carry over both ways`() {
        val connected = PointerLayoutMachine.apply(PointerLayoutMachine.start(work), PointerLayoutMachine.Event.PointerConnected)
        assertSame(work, connected.continuity)
        val back = PointerLayoutMachine.apply(connected, PointerLayoutMachine.Event.PointerDisconnected)
        assertSame(work, back.continuity)
    }

    @Test
    fun `no state exists only under POINTER_LAYOUT`() {
        // Any state reachable in POINTER_LAYOUT differs from a PHONE_LAYOUT state only in the layout
        // tag: disconnecting yields the identical continuity, so nothing is stranded there.
        for (c in listOf(AuthoringContinuity(), work, work.copy(selectedNodeId = null))) {
            val pointer = PointerLayoutMachine.apply(PointerLayoutMachine.start(c), PointerLayoutMachine.Event.PointerConnected)
            val phone = PointerLayoutMachine.apply(pointer, PointerLayoutMachine.Event.PointerDisconnected)
            assertEquals(PointerLayoutMachine.start(c), phone)
            assertEquals(2, PointerLayout.values().size)
        }
    }
}
