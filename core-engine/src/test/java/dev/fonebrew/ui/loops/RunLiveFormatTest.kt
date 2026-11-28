package dev.fonebrew.ui.loops

import dev.fonebrew.domain.loop.AgentRow
import dev.fonebrew.domain.loop.AgentStatus
import dev.fonebrew.domain.loop.GatewayDecision
import dev.fonebrew.domain.loop.RunProgress
import dev.fonebrew.domain.loop.RunView
import dev.fonebrew.domain.loop.RunViewReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunLiveFormatTest {
    @Test fun `every status has a glyph and a word, never colour alone`() {
        AgentStatus.entries.forEach {
            assertTrue(RunLiveFormat.glyph(it).isNotBlank()); assertTrue(RunLiveFormat.word(it).isNotBlank())
        }
        assertEquals(AgentStatus.entries.size, AgentStatus.entries.map { RunLiveFormat.glyph(it) }.toSet().size)
    }

    @Test fun `elapsed formats across the ranges`() {
        assertEquals("0.0s", RunLiveFormat.elapsed(0))
        assertEquals("3.4s", RunLiveFormat.elapsed(3_450))
        assertEquals("42s", RunLiveFormat.elapsed(42_900))
        assertEquals("1m 05s", RunLiveFormat.elapsed(65_000))
        assertEquals("0.0s", RunLiveFormat.elapsed(-5))
    }

    @Test fun `header names the running step and never invents tokens`() {
        var v = RunViewReducer.reduce(RunView.IDLE, RunProgress.RunStarted(0, "o", 4, 24))
        v = RunViewReducer.reduce(v, RunProgress.StepStarted(10, 0, "n", "Critic", null))
        val h = RunLiveFormat.header(v, nowMs = 1_500)
        assertTrue(h, h.startsWith("running · Critic · step 1 of ≤24 · 1.5s"))
        assertFalse(h, h.contains("tok"))
    }

    @Test fun `gateway line shows the branch taken and what was passed over`() {
        val g = GatewayDecision("gw", "no", "refine", listOf("yes"), 1)
        val line = RunLiveFormat.gateway(g, { "ok?" }, { if (it == "yes") "approve" else null })
        assertEquals("◇ ok? → refine (not: approve)", line)
    }

    @Test fun `tail keeps the end of long text`() {
        assertEquals("short", RunLiveFormat.tail("short"))
        val t = RunLiveFormat.tail("a".repeat(1000) + "END", 10)
        assertTrue(t.startsWith("…") && t.endsWith("END") && t.length == 11)
    }

    @Test fun `agent meta shows model, ticking elapsed and measured tokens only`() {
        val a = AgentRow("s", "run", "n", "r", "qwen", AgentStatus.RUNNING, startedAt = 100)
        assertEquals("qwen · 0.9s", RunLiveFormat.agentMeta(a, 1_000))
        assertEquals("qwen · 0.4s · 7 tok", RunLiveFormat.agentMeta(a.copy(status = AgentStatus.DONE, endedAt = 500, tokensIn = 3, tokensOut = 4), 9_999))
    }
}
