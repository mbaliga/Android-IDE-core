package dev.fonebrew.watch.core

import dev.fonebrew.watchbridge.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchStateTest {
    private fun reduce(vararg events: WatchEvent) = events.fold(WatchState()) { s, e -> WatchReducer.reduce(s, e) }

    @Test
    fun aFinishedReply_becomesTheLastReply_andSurvivesDismissal() {
        val s = reduce(
            AskEvent.Submitted(Fx.ID, "q", 1),
            AskEvent.ChunkReceived(Fx.terminal("answer", usage = Usage.engineReported(1, 2)), 2),
        )
        assertEquals("answer", s.lastReply!!.text)
        val dismissed = WatchReducer.reduce(s, AskEvent.Dismissed)
        assertEquals(AskState.Idle, dismissed.ask)
        assertEquals("answer", dismissed.lastReply!!.text)
    }

    @Test
    fun aFailedOrPartialReply_neverBecomesTheLastReply() {
        val s = reduce(
            AskEvent.Submitted(Fx.ID, "q", 1),
            AskEvent.ChunkReceived(Fx.token("half"), 2),
            AskEvent.StreamEnded(Fx.ID),
        )
        assertNull(s.lastReply)
        assertTrue(s.ask is AskState.Failed)
    }

    @Test
    fun eventFamiliesOnlyTouchTheirOwnSlice() {
        val s = reduce(LinkEvent.NodesObserved(listOf(Fx.observed)), LoopEvent.RunReceived(Fx.run("r"), 1))
        assertTrue(s.connection is Connection.Reachable)
        assertEquals(1, s.loops.runs.size)
        assertEquals(AskState.Idle, s.ask)
        assertNull(s.tasks.snapshot)
    }
}
