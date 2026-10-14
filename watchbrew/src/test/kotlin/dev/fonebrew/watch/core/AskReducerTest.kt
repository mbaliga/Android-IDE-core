package dev.fonebrew.watch.core

import dev.fonebrew.watchbridge.AskError
import dev.fonebrew.watchbridge.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AskReducerTest {
    private fun run(vararg events: AskEvent, from: AskState = AskState.Idle): AskState =
        events.fold(from) { s, e -> AskReducer.reduce(s, e) }

    private val submit = AskEvent.Submitted(Fx.ID, "what is up", atMs = 1_000)
    private fun chunk(c: dev.fonebrew.watchbridge.AskChunk, at: Long = 2_000) = AskEvent.ChunkReceived(c, at)

    // ---- the happy path: accumulation ----

    @Test
    fun submit_movesIdleToSent() {
        assertEquals(AskState.Sent(Fx.ID, "what is up", 1_000), run(submit))
    }

    @Test
    fun chunksAccumulateInOrder_andOnlyATerminalChunkFinishes() {
        val s1 = run(submit, chunk(Fx.token("Hel"), 2_000))
        assertTrue(s1 is AskState.Streaming)
        val s2 = run(chunk(Fx.token("lo, "), 3_000), from = s1)
        assertEquals("Hello, ", (s2 as AskState.Streaming).text)
        assertEquals(3_000, s2.lastChunkAtMs)
        assertEquals(1_000, s2.sentAtMs)

        val done = run(chunk(Fx.terminal("world", usage = Usage.engineReported(7, 9)), 4_000), from = s2)
        done as AskState.Done
        assertEquals("Hello, world", done.reply.text)
        assertEquals(Usage.engineReported(7, 9), done.reply.usage)
        assertEquals(4_000, done.reply.completedAtMs)
        assertEquals("what is up", done.prompt)
    }

    @Test
    fun aTerminalChunkAloneFinishesAReply_andKeepsEstimatedUsageEstimated() {
        val done = run(submit, chunk(Fx.terminal("ok", usage = Usage.estimate(4, null))))
        done as AskState.Done
        assertTrue(done.reply.usage.estimated)
        assertNull(done.reply.usage.outputTokens) // unknown stays unknown, never 0
    }

    @Test
    fun whitespaceAndEmptyChunkTextIsKeptVerbatim() {
        val s = run(submit, chunk(Fx.token(" ")), chunk(Fx.token("")), chunk(Fx.token("a\n")))
        assertEquals(" a\n", (s as AskState.Streaming).text)
    }

    @Test
    fun secondSubmitWhileInFlight_isIgnored() {
        val sent = run(submit)
        assertSame(sent, run(AskEvent.Submitted("req-2", "other", 5_000), from = sent))
        val streaming = run(chunk(Fx.token("x")), from = sent)
        assertSame(streaming, run(AskEvent.Submitted("req-2", "other", 5_000), from = streaming))
    }

    @Test
    fun aNewAskIsAcceptedAfterDoneOrFailed() {
        val done = run(submit, chunk(Fx.terminal("ok")))
        assertTrue(run(AskEvent.Submitted("req-2", "again", 9_000), from = done) is AskState.Sent)
        val failed = run(submit, AskEvent.SendFailed(Fx.ID, LinkFailure.NO_NODE))
        assertTrue(run(AskEvent.Submitted("req-2", "again", 9_000), from = failed) is AskState.Sent)
    }

    // ---- who answered ----

    @Test
    fun watchedFlagOnlyRatchetsUp_aReplyThatEverTouchedCloudIsWatched() {
        val s = run(
            submit,
            chunk(Fx.token("a", watched = false)),
            chunk(Fx.token("b", watched = true)),
            chunk(Fx.token("c", watched = false)),
        ) as AskState.Streaming
        assertTrue(s.watchedObject)
        val done = run(chunk(Fx.terminal("d", watched = false)), from = s) as AskState.Done
        assertTrue("a later on-device chunk must not downgrade a watched reply", done.reply.watchedObject)
    }

    @Test
    fun onDeviceReplyStaysOnDevice_andEngineIdIsTheLatestChunks() {
        val done = run(submit, chunk(Fx.token("a", engine = "m1")), chunk(Fx.terminal("b", engine = "m2"))) as AskState.Done
        assertFalse(done.reply.watchedObject)
        assertEquals("m2", done.reply.engineId)
    }

    // ---- failures are final facts, each distinct ----

    @Test
    fun sendFailure_failsTheSentAsk_withTheLinkCause() {
        val s = run(submit, AskEvent.SendFailed(Fx.ID, LinkFailure.API_UNAVAILABLE)) as AskState.Failed
        assertEquals(AskFailure.SendFailed(LinkFailure.API_UNAVAILABLE), s.failure)
        assertNull(s.partial)
    }

    @Test
    fun phoneRefusal_keepsThePhonesOwnWords_andAnyPartialText() {
        val s = run(submit, chunk(Fx.token("par")), AskEvent.PhoneRefused(AskError(Fx.ID, "Phone is out of memory for this model"))) as AskState.Failed
        assertEquals(AskFailure.PhoneRefused("Phone is out of memory for this model"), s.failure)
        assertEquals("par", s.partial!!.text)
    }

    @Test
    fun streamEnd_withoutATerminalChunk_isALostLink_neverASuccess() {
        val s = run(submit, chunk(Fx.token("half an answ")), AskEvent.StreamEnded(Fx.ID)) as AskState.Failed
        assertEquals(AskFailure.LinkLost, s.failure)
        assertEquals("half an answ", s.partial!!.text)
    }

    @Test
    fun streamEnd_beforeAnyChunk_isAlsoALostLink() {
        val s = run(submit, AskEvent.StreamEnded(Fx.ID)) as AskState.Failed
        assertEquals(AskFailure.LinkLost, s.failure)
        assertNull(s.partial)
    }

    @Test
    fun streamEnd_afterATerminalChunk_changesNothing() {
        val done = run(submit, chunk(Fx.terminal("ok")))
        assertSame(done, run(AskEvent.StreamEnded(Fx.ID), from = done))
    }

    @Test
    fun damagedStream_failsWithPartial_andLateChunksDoNotReviveIt() {
        val s = run(submit, chunk(Fx.token("a")), AskEvent.StreamDamaged(Fx.ID))
        assertEquals(AskFailure.DamagedStream, (s as AskState.Failed).failure)
        assertSame(s, run(chunk(Fx.token("b")), from = s))
        assertSame(s, run(chunk(Fx.terminal("c")), from = s))
    }

    @Test
    fun lostLinkAndSendFailure_areFinal_chunksDoNotReviveThem() {
        val lost = run(submit, chunk(Fx.token("a")), AskEvent.StreamEnded(Fx.ID))
        assertSame(lost, run(chunk(Fx.terminal("b")), from = lost))
    }

    // ---- timeouts are the watch's guesses: revivable ----

    @Test
    fun firstChunkTimeout_isNoAnswer_andIgnoresAStaleMarker() {
        val sent = run(submit)
        assertSame(sent, run(AskEvent.TimedOut(Fx.ID, marker = 999), from = sent))
        val failed = run(AskEvent.TimedOut(Fx.ID, marker = 1_000), from = sent) as AskState.Failed
        assertEquals(AskFailure.NoAnswer, failed.failure)
    }

    @Test
    fun idleTimeout_isStalled_withPartial_andAWatchdogArmedBeforeANewerChunkIsIgnored() {
        val streaming = run(submit, chunk(Fx.token("abc"), 2_000), chunk(Fx.token("def"), 3_000))
        // The watchdog armed at the first chunk (marker 2_000) is stale: a newer chunk landed at 3_000.
        assertSame(streaming, run(AskEvent.TimedOut(Fx.ID, 2_000), from = streaming))
        val stalled = run(AskEvent.TimedOut(Fx.ID, 3_000), from = streaming) as AskState.Failed
        assertEquals(AskFailure.Stalled, stalled.failure)
        assertEquals("abcdef", stalled.partial!!.text)
    }

    @Test
    fun aLateReplyRevivesANoAnswerAsk() {
        val noAnswer = run(submit, AskEvent.TimedOut(Fx.ID, 1_000))
        val revived = run(chunk(Fx.token("late "), 60_000), from = noAnswer)
        assertEquals("late ", (revived as AskState.Streaming).text)
        val done = run(chunk(Fx.terminal("reply"), 61_000), from = revived) as AskState.Done
        assertEquals("late reply", done.reply.text)
    }

    @Test
    fun aLateChunkRevivesAStalledAsk_appendingToThePartial_andKeepingTheWatchedRatchet() {
        val streaming = run(submit, chunk(Fx.token("one ", watched = true), 2_000))
        val stalled = run(AskEvent.TimedOut(Fx.ID, 2_000), from = streaming)
        val done = run(chunk(Fx.terminal("two", watched = false), 40_000), from = stalled) as AskState.Done
        assertEquals("one two", done.reply.text)
        assertTrue(done.reply.watchedObject)
    }

    @Test
    fun phoneRefusal_outranksTheWatchsGuessesAndALostLink_butNeverOverturnsAFinishedReply() {
        val noAnswer = run(submit, AskEvent.TimedOut(Fx.ID, 1_000))
        val refused = run(AskEvent.PhoneRefused(AskError(Fx.ID, "model not loaded")), from = noAnswer) as AskState.Failed
        assertEquals(AskFailure.PhoneRefused("model not loaded"), refused.failure)

        val lost = run(submit, chunk(Fx.token("x")), AskEvent.StreamEnded(Fx.ID))
        assertEquals(AskFailure.PhoneRefused("why"), (run(AskEvent.PhoneRefused(AskError(Fx.ID, "why")), from = lost) as AskState.Failed).failure)

        val done = run(submit, chunk(Fx.terminal("ok")))
        assertSame(done, run(AskEvent.PhoneRefused(AskError(Fx.ID, "late")), from = done))
    }

    @Test
    fun aSendFailureIsNotOverwrittenByALaterRefusal() {
        val failed = run(submit, AskEvent.SendFailed(Fx.ID, LinkFailure.NO_NODE))
        assertSame(failed, run(AskEvent.PhoneRefused(AskError(Fx.ID, "x")), from = failed))
    }

    // ---- other requests and dismissal ----

    @Test
    fun everythingForAnotherRequestId_isIgnored() {
        val sent = run(submit)
        assertSame(sent, run(chunk(Fx.token("x", id = "other")), from = sent))
        assertSame(sent, run(AskEvent.PhoneRefused(AskError("other", "x")), from = sent))
        assertSame(sent, run(AskEvent.StreamEnded("other"), from = sent))
        assertSame(sent, run(AskEvent.StreamDamaged("other"), from = sent))
        assertSame(sent, run(AskEvent.SendFailed("other", LinkFailure.NO_NODE), from = sent))
        assertSame(sent, run(AskEvent.TimedOut("other", 1_000), from = sent))
    }

    @Test
    fun dismissed_returnsToIdle_andLateChunksForTheDismissedAskAreDropped() {
        val streaming = run(submit, chunk(Fx.token("x")))
        val idle = run(AskEvent.Dismissed, from = streaming)
        assertEquals(AskState.Idle, idle)
        assertSame(idle, run(chunk(Fx.token("late")), from = idle))
        assertSame(idle, run(chunk(Fx.terminal("late")), from = idle))
    }

    @Test
    fun inFlight_isExactlySentAndStreaming() {
        assertFalse(AskState.Idle.inFlight)
        assertTrue(run(submit).inFlight)
        assertTrue(run(submit, chunk(Fx.token("x"))).inFlight)
        assertFalse(run(submit, chunk(Fx.terminal("x"))).inFlight)
        assertFalse(run(submit, AskEvent.TimedOut(Fx.ID, 1_000)).inFlight)
    }

    // ---- presenter ----

    @Test
    fun everyFailure_isWorded_andDistinct() {
        val failures = listOf(
            AskFailure.PhoneRefused("why"),
            AskFailure.NoAnswer,
            AskFailure.Stalled,
            AskFailure.LinkLost,
            AskFailure.DamagedStream,
            AskFailure.SendFailed(LinkFailure.NO_NODE),
            AskFailure.SendFailed(LinkFailure.API_UNAVAILABLE),
            AskFailure.SendFailed(LinkFailure.OTHER),
        )
        val views = failures.map { AskFailurePresenter.present(it) }
        views.forEach { assertTrue(it.headline.isNotBlank() && it.detail.isNotBlank()) }
        assertEquals("headlines are distinct, so no two failures read alike", views.size, views.map { it.headline }.toSet().size)
        assertEquals("why", AskFailurePresenter.present(AskFailure.PhoneRefused("why")).detail)
    }

    @Test
    fun aDroppedStream_neverReadsAsAFinishedAnswer() {
        val v = AskFailurePresenter.present(AskFailure.LinkLost)
        assertTrue(v.detail.contains("not a finished answer"))
    }
}
