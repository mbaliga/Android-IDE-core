package dev.fonebrew.watch.runtime

import dev.fonebrew.watch.core.AskFailure
import dev.fonebrew.watch.core.AskState
import dev.fonebrew.watch.core.Connection
import dev.fonebrew.watch.core.Fx
import dev.fonebrew.watch.core.KeyValueStore
import dev.fonebrew.watch.core.LinkFailure
import dev.fonebrew.watch.core.LoopEntry
import dev.fonebrew.watch.core.ObservedNode
import dev.fonebrew.watch.core.ReplyRecord
import dev.fonebrew.watch.core.SnapshotCache
import dev.fonebrew.watch.core.UnreachableCause
import dev.fonebrew.watchbridge.AskChunk
import dev.fonebrew.watchbridge.AskError
import dev.fonebrew.watchbridge.AskRequest
import dev.fonebrew.watchbridge.ContractHello
import dev.fonebrew.watchbridge.DecodeResult
import dev.fonebrew.watchbridge.StreamFraming
import dev.fonebrew.watchbridge.TaskToggleRequest
import dev.fonebrew.watchbridge.TaskToggleResult
import dev.fonebrew.watchbridge.Usage
import dev.fonebrew.watchbridge.WatchBridgeCodec
import dev.fonebrew.watchbridge.WatchMessage
import dev.fonebrew.watchbridge.WatchPaths
import dev.fonebrew.watchbridge.WatchTaskState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

internal class FakeLink : PhoneLink {
    class Sent(val nodeId: String, val path: String, val payload: ByteArray)

    var nodes: List<ObservedNode> = listOf(Fx.observed)
    var nodesFailure: LinkFailure? = null
    var sendFailure: LinkFailure? = null
    val sent = mutableListOf<Sent>()

    override suspend fun connectedNodes(): List<ObservedNode> {
        nodesFailure?.let { throw LinkException(it) }
        return nodes
    }

    override suspend fun send(nodeId: String, path: String, payload: ByteArray) {
        sendFailure?.let { throw LinkException(it) }
        sent += Sent(nodeId, path, payload)
    }

    fun decodedOn(path: String): List<WatchMessage> =
        sent.filter { it.path == path }.map { (WatchBridgeCodec.decode(it.payload) as DecodeResult.Decoded).message }
}

internal class RecordingEffects : WatchEffects {
    var tasks = 0
    var replies = 0
    override fun tasksChanged() { tasks++ }
    override fun lastReplyChanged() { replies++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class WatchControllerTest {

    private class Rig(scope: TestScope, val link: FakeLink = FakeLink(), val store: MapStoreForController = MapStoreForController()) {
        val effects = RecordingEffects()
        private var ids = 0
        val controller = WatchController(
            link = link,
            cache = SnapshotCache(store),
            scope = scope.backgroundScope,
            clock = { scope.currentTime },
            newRequestId = { "req-${++ids}" },
            effects = effects,
        )
        val state get() = controller.state.value
    }

    private fun TestScope.rig(configure: FakeLink.() -> Unit = {}) = Rig(this, FakeLink().apply(configure))

    private fun decoded(message: WatchMessage): DecodeResult = WatchBridgeCodec.decode(WatchBridgeCodec.encodeToBytes(message))

    private fun frames(vararg messages: WatchMessage): ByteArray = messages.fold(ByteArray(0)) { acc, m -> acc + StreamFraming.encodeFrame(m) }

    // ---- Ask: sending ----

    @Test
    fun ask_sendsOneAskRequestOnTheAskPath_andWaitsInSent() = runTest {
        val r = rig()
        val result = r.controller.ask("  what is up?  ")
        assertEquals(AskSubmission.Accepted("req-1"), result)
        assertTrue(r.state.ask is AskState.Sent)
        runCurrent()
        assertEquals(listOf<WatchMessage>(AskRequest("req-1", "what is up?")), r.link.decodedOn(WatchPaths.ASK))
        assertEquals("node-1", r.link.sent.single().nodeId)
        assertTrue("a successful send proves the link", r.state.connection is Connection.Reachable)
    }

    @Test
    fun ask_rejectsBlankOrOversizedPromptsInWords_andSendsNothing() = runTest {
        val r = rig()
        assertTrue(r.controller.ask("   ") is AskSubmission.Rejected)
        assertTrue(r.controller.ask(null) is AskSubmission.Rejected)
        runCurrent()
        assertEquals(AskState.Idle, r.state.ask)
        assertTrue(r.link.sent.isEmpty())
    }

    @Test
    fun ask_whileOneIsInFlight_isRejected() = runTest {
        val r = rig()
        r.controller.ask("one")
        val second = r.controller.ask("two") as AskSubmission.Rejected
        assertTrue(second.reason.contains("Still waiting"))
        runCurrent()
        assertEquals(1, r.link.sent.size)
    }

    @Test
    fun ask_isRefusedWhenTheVersionsDoNotMatch_withTheReasonInWords() = runTest {
        val r = rig()
        r.controller.onPhoneMessage("node-1", WatchPaths.HELLO, """{"contract":"watchbrew","v":2,"type":"ContractHello","payload":{}}""".toByteArray())
        assertTrue(r.state.connection is Connection.Incompatible)
        val rejected = r.controller.ask("hi") as AskSubmission.Rejected
        assertTrue(rejected.reason.contains("Update this app"))
        runCurrent()
        assertTrue(r.link.sent.isEmpty())
    }

    @Test
    fun ask_withNoPairedPhone_failsRenderedNotSpinning() = runTest {
        val r = rig { nodes = emptyList() }
        r.controller.ask("hi")
        runCurrent()
        val failed = r.state.ask as AskState.Failed
        assertEquals(AskFailure.SendFailed(LinkFailure.NO_NODE), failed.failure)
        assertEquals(Connection.Unreachable(UnreachableCause.NO_PHONE_CONNECTED), r.state.connection)
        assertTrue(r.link.sent.isEmpty())
    }

    @Test
    fun ask_whenPlayServicesAreMissing_saysSo() = runTest {
        val r = rig { nodesFailure = LinkFailure.API_UNAVAILABLE }
        r.controller.ask("hi")
        runCurrent()
        assertEquals(AskFailure.SendFailed(LinkFailure.API_UNAVAILABLE), (r.state.ask as AskState.Failed).failure)
        assertEquals(Connection.Unreachable(UnreachableCause.LINK_UNAVAILABLE), r.state.connection)
    }

    @Test
    fun ask_whenTheSendItselfFails_failsTheAsk_andMarksTheLink() = runTest {
        val r = rig { sendFailure = LinkFailure.OTHER }
        r.controller.ask("hi")
        runCurrent()
        assertEquals(AskFailure.SendFailed(LinkFailure.OTHER), (r.state.ask as AskState.Failed).failure)
        assertEquals(Connection.Unreachable(UnreachableCause.LINK_ERROR), r.state.connection)
    }

    // ---- Ask: the watchdogs ----

    @Test
    fun noReplyAtAll_becomesNoAnswerAfterTheFirstChunkTimeout_notBefore() = runTest {
        val r = rig()
        r.controller.ask("hi")
        runCurrent()
        advanceTimeBy(WatchTimeouts().firstChunkMs - 1)
        assertTrue(r.state.ask is AskState.Sent)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(AskFailure.NoAnswer, (r.state.ask as AskState.Failed).failure)
    }

    @Test
    fun aChunkReplacesTheFirstChunkClock_soNoAnswerNeverFiresOnAReplyThatIsFlowing() = runTest {
        val r = rig()
        r.controller.ask("hi")
        runCurrent()
        advanceTimeBy(10_000)
        r.controller.openReplySession("node-1").onFrame(decoded(Fx.token("par", id = "req-1")))
        advanceTimeBy(WatchTimeouts().firstChunkMs) // well past where the first-chunk watchdog would have fired
        runCurrent()
        // The first-chunk watchdog was replaced by the idle one, so the failure is Stalled (never NoAnswer).
        assertEquals(AskFailure.Stalled, (r.state.ask as AskState.Failed).failure)
    }

    @Test
    fun streamingThatGoesSilent_isStalledExactlyAtTheIdleTimeout_withThePartialKept() = runTest {
        val r = rig()
        r.controller.ask("hi")
        runCurrent()
        val session = r.controller.openReplySession("node-1")
        session.onFrame(decoded(Fx.token("abc", id = "req-1")))
        val t = WatchTimeouts().idleMs
        advanceTimeBy(t - 1)
        assertTrue(r.state.ask is AskState.Streaming)
        advanceTimeBy(1)
        runCurrent()
        val failed = r.state.ask as AskState.Failed
        assertEquals(AskFailure.Stalled, failed.failure)
        assertEquals("abc", failed.partial!!.text)
    }

    @Test
    fun eachChunkRestartsTheIdleClock() = runTest {
        val r = rig()
        r.controller.ask("hi")
        runCurrent()
        val session = r.controller.openReplySession("node-1")
        val idle = WatchTimeouts().idleMs
        session.onFrame(decoded(Fx.token("a", id = "req-1")))
        advanceTimeBy(idle - 1)
        session.onFrame(decoded(Fx.token("b", id = "req-1")))
        advanceTimeBy(idle - 1)
        assertTrue("the second chunk re-armed the watchdog", r.state.ask is AskState.Streaming)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(AskFailure.Stalled, (r.state.ask as AskState.Failed).failure)
    }

    @Test
    fun aStalledReply_thatThenFinishes_isRevivedIntoADoneReply() = runTest {
        val r = rig()
        r.controller.ask("hi")
        runCurrent()
        val session = r.controller.openReplySession("node-1")
        session.onFrame(decoded(Fx.token("one ", id = "req-1")))
        advanceTimeBy(WatchTimeouts().idleMs)
        runCurrent()
        assertEquals(AskFailure.Stalled, (r.state.ask as AskState.Failed).failure)
        session.onFrame(decoded(Fx.terminal("two", id = "req-1")))
        assertEquals("one two", (r.state.ask as AskState.Done).reply.text)
    }

    @Test
    fun aFinishedReply_cancelsTheWatchdog_noLateTimeoutCanSpoilIt() = runTest {
        val r = rig()
        r.controller.ask("hi")
        runCurrent()
        r.controller.openReplySession("node-1").onFrame(decoded(Fx.terminal("ok", id = "req-1")))
        advanceTimeBy(10 * 60_000)
        runCurrent()
        assertTrue(r.state.ask is AskState.Done)
    }

    // ---- Ask: the reply channel ----

    @Test
    fun aStreamedReply_accumulates_finishesOnTheTerminalChunk_andIsCachedAsTheLastReply() = runTest {
        val r = rig()
        r.controller.ask("hi")
        runCurrent()
        advanceTimeBy(3_000)
        r.controller.onReplyChannel(
            "node-1",
            ByteArrayInputStream(
                frames(
                    Fx.token("Hel", id = "req-1", watched = true, engine = "cloud-x"),
                    Fx.token("lo ", id = "req-1", watched = true, engine = "cloud-x"),
                    Fx.terminal("world", id = "req-1", watched = true, engine = "cloud-x", usage = Usage.engineReported(4, 6)),
                ),
            ),
        )
        val done = r.state.ask as AskState.Done
        assertEquals("Hello world", done.reply.text)
        assertTrue("the watched-object flag reaches the wrist", done.reply.watchedObject)
        assertEquals("cloud-x", done.reply.engineId)
        assertEquals(Usage.engineReported(4, 6), done.reply.usage)
        assertEquals(3_000L, done.reply.completedAtMs)
        assertEquals(done.reply, r.state.lastReply)
        assertEquals(1, r.effects.replies)
        assertEquals(done.reply.copy(), SnapshotCache(r.store).loadLastReply())
    }

    @Test
    fun aStreamThatClosesWithoutATerminalChunk_isALostLink_withThePartialKept() = runTest {
        val r = rig()
        r.controller.ask("hi")
        runCurrent()
        r.controller.onReplyChannel("node-1", ByteArrayInputStream(frames(Fx.token("half", id = "req-1"))))
        val failed = r.state.ask as AskState.Failed
        assertEquals(AskFailure.LinkLost, failed.failure)
        assertEquals("half", failed.partial!!.text)
        assertNull("a broken reply is never the 'last reply'", r.state.lastReply)
        assertEquals(0, r.effects.replies)
    }

    @Test
    fun aStreamCutMidFrame_isALostLink() = runTest {
        val r = rig()
        r.controller.ask("hi")
        runCurrent()
        val bytes = frames(Fx.token("a", id = "req-1"), Fx.terminal("b", id = "req-1"))
        r.controller.onReplyChannel("node-1", ByteArrayInputStream(bytes.copyOf(bytes.size - 2)))
        assertEquals(AskFailure.LinkLost, (r.state.ask as AskState.Failed).failure)
    }

    @Test
    fun aFrameThatDoesNotDecode_damagesTheReply_andALaterTerminalChunkCannotRepairIt() = runTest {
        val r = rig()
        r.controller.ask("hi")
        runCurrent()
        val garbage = "not json".toByteArray()
        val bytes = frames(Fx.token("a", id = "req-1")) + byteArrayOf(0, 0, 0, garbage.size.toByte()) + garbage + frames(Fx.terminal("z", id = "req-1"))
        r.controller.onReplyChannel("node-1", ByteArrayInputStream(bytes))
        assertEquals(AskFailure.DamagedStream, (r.state.ask as AskState.Failed).failure)
        assertNull(r.state.lastReply)
    }

    @Test
    fun aBrokenFrameLength_damagesTheReply() = runTest {
        val r = rig()
        r.controller.ask("hi")
        runCurrent()
        r.controller.onReplyChannel("node-1", ByteArrayInputStream(byteArrayOf(0, 0, 0, 0)))
        assertEquals(AskFailure.DamagedStream, (r.state.ask as AskState.Failed).failure)
    }

    @Test
    fun anAskErrorFrame_showsThePhonesOwnWords() = runTest {
        val r = rig()
        r.controller.ask("hi")
        runCurrent()
        r.controller.onReplyChannel("node-1", ByteArrayInputStream(frames(AskError("req-1", "Phone is out of memory for this model"))))
        assertEquals(AskFailure.PhoneRefused("Phone is out of memory for this model"), (r.state.ask as AskState.Failed).failure)
    }

    @Test
    fun aStreamForADismissedAsk_cannotTouchTheNextOne() = runTest {
        val r = rig()
        r.controller.ask("first")
        runCurrent()
        r.controller.dismissAsk()
        r.controller.ask("second")
        runCurrent()
        r.controller.onReplyChannel("node-1", ByteArrayInputStream(frames(Fx.terminal("stale", id = "req-1"))))
        val s = r.state.ask
        assertTrue("req-2 is still waiting; req-1's reply is ignored: $s", s is AskState.Sent && s.requestId == "req-2")
    }

    @Test
    fun aLateReply_afterNoAnswer_stillArrives() = runTest {
        val r = rig()
        r.controller.ask("hi")
        runCurrent()
        advanceTimeBy(WatchTimeouts().firstChunkMs)
        runCurrent()
        assertEquals(AskFailure.NoAnswer, (r.state.ask as AskState.Failed).failure)
        r.controller.onReplyChannel("node-1", ByteArrayInputStream(frames(Fx.terminal("better late", id = "req-1"))))
        assertEquals("better late", (r.state.ask as AskState.Done).reply.text)
    }

    @Test
    fun aReplyChannelWhileNothingIsInFlight_isHarmless() = runTest {
        val r = rig()
        r.controller.onReplyChannel("node-1", ByteArrayInputStream(frames(Fx.token("x", id = "ghost"))))
        assertEquals(AskState.Idle, r.state.ask)
    }

    // ---- Tasks ----

    private fun TestScope.withTasks(): Rig {
        val r = rig()
        r.controller.onPhoneMessage(
            "node-1", WatchPaths.TASKS,
            WatchBridgeCodec.encodeToBytes(Fx.snapshot(Fx.task("a", "Buy milk"), Fx.task("c", "Ship", WatchTaskState.DONE))),
        )
        return r
    }

    @Test
    fun aTaskSnapshot_fromThePhone_isShown_cached_andAnnouncedToTheComplication() = runTest {
        val r = withTasks()
        assertEquals(2, r.state.tasks.snapshot!!.tasks.size)
        assertTrue(r.state.connection is Connection.Reachable)
        assertEquals(1, r.effects.tasks)
        assertNotNull(SnapshotCache(r.store).loadTasks())
    }

    @Test
    fun togglingATask_sendsADesiredState_andChangesNoRowUntilThePhoneConfirms() = runTest {
        val r = withTasks()
        r.controller.toggleTask("a")
        runCurrent()
        assertEquals(listOf<WatchMessage>(TaskToggleRequest("a", true)), r.link.decodedOn(WatchPaths.TASK_TOGGLE))
        assertEquals(WatchTaskState.TODO, r.state.tasks.snapshot!!.tasks.first { it.id == "a" }.state)
        assertTrue(r.state.tasks.pending.containsKey("a"))

        r.controller.onPhoneMessage("node-1", WatchPaths.TASK_TOGGLE_RESULT, WatchBridgeCodec.encodeToBytes(TaskToggleResult.Applied("a", true)))
        assertEquals(WatchTaskState.DONE, r.state.tasks.snapshot!!.tasks.first { it.id == "a" }.state)
        assertTrue(r.state.tasks.pending.isEmpty())
    }

    @Test
    fun aDoneTask_isAskedToReopen_theOppositeOfItsConfirmedState() = runTest {
        val r = withTasks()
        r.controller.toggleTask("c")
        runCurrent()
        assertEquals(listOf<WatchMessage>(TaskToggleRequest("c", false)), r.link.decodedOn(WatchPaths.TASK_TOGGLE))
    }

    @Test
    fun aSecondTapWhileOneIsPending_sendsNothingMore_andAnUnknownTaskIsIgnored() = runTest {
        val r = withTasks()
        r.controller.toggleTask("a")
        r.controller.toggleTask("a")
        r.controller.toggleTask("missing")
        runCurrent()
        assertEquals(1, r.link.sent.size)
    }

    @Test
    fun aToggleThePhoneNeverAnswers_timesOutWithANotice_changesNothing_andAsksForAFreshList() = runTest {
        val r = withTasks()
        r.controller.toggleTask("a")
        runCurrent()
        advanceTimeBy(WatchTimeouts().toggleMs)
        runCurrent()
        assertTrue(r.state.tasks.pending.isEmpty())
        assertTrue(r.state.tasks.notices["a"]!!.contains("No confirmation"))
        assertEquals(WatchTaskState.TODO, r.state.tasks.snapshot!!.tasks.first { it.id == "a" }.state)
        assertTrue("a resync (SYNC pull) was requested", r.link.sent.any { it.path == WatchPaths.SYNC })
        assertFalse(r.state.tasks.needsResync)
    }

    @Test
    fun aRejectedToggle_showsThePhonesReason_andResyncs() = runTest {
        val r = withTasks()
        r.controller.toggleTask("a")
        runCurrent()
        r.controller.onPhoneMessage("node-1", WatchPaths.TASK_TOGGLE_RESULT, WatchBridgeCodec.encodeToBytes(TaskToggleResult.Rejected("a", "the task no longer exists")))
        runCurrent()
        assertEquals("Phone: the task no longer exists", r.state.tasks.notices["a"])
        assertTrue(r.link.sent.any { it.path == WatchPaths.SYNC })
    }

    @Test
    fun aToggleThatCouldNotBeSent_saysNothingWasChanged() = runTest {
        val r = withTasks()
        r.link.sendFailure = LinkFailure.OTHER
        r.controller.toggleTask("a")
        runCurrent()
        assertTrue(r.state.tasks.pending.isEmpty())
        assertTrue(r.state.tasks.notices["a"]!!.contains("Nothing was changed"))
    }

    // ---- sync / hello / loops / hydration ----

    @Test
    fun refresh_queriesNodes_thenPullsWithAnEmptySyncMessage() = runTest {
        val r = rig()
        r.controller.refresh()
        runCurrent()
        val sync = r.link.sent.single()
        assertEquals(WatchPaths.SYNC, sync.path)
        assertEquals("the SYNC pull has no body", 0, sync.payload.size)
    }

    @Test
    fun refresh_withNoNodes_rendersUnreachable_andSendsNothing() = runTest {
        val r = rig { nodes = emptyList() }
        r.controller.refresh()
        runCurrent()
        assertEquals(Connection.Unreachable(UnreachableCause.NO_PHONE_CONNECTED), r.state.connection)
        assertTrue(r.link.sent.isEmpty())
    }

    @Test
    fun aCompatibleHello_namesTheFonebrewVersion() = runTest {
        val r = rig()
        r.controller.onPhoneMessage("node-1", WatchPaths.HELLO, WatchBridgeCodec.encodeToBytes(ContractHello.forThisBuild("0.3.1")))
        assertEquals("0.3.1", (r.state.connection as Connection.Reachable).phoneAppVersion)
    }

    @Test
    fun loopRuns_arriveOnePerMessage_andAreCached() = runTest {
        val r = rig()
        r.controller.onPhoneMessage("node-1", WatchPaths.LOOPS, WatchBridgeCodec.encodeToBytes(Fx.run("r1", step = "fetch")))
        r.controller.onPhoneMessage("node-1", WatchPaths.LOOPS, WatchBridgeCodec.encodeToBytes(Fx.run("r2")))
        assertEquals(listOf("r2", "r1"), r.state.loops.runs.map { it.snapshot.runId })
        assertEquals(2, SnapshotCache(r.store).loadLoops().size)
    }

    @Test
    fun garbageOrMisaddressedMessages_changeNothing() = runTest {
        val r = rig()
        r.controller.onPhoneMessage("node-1", WatchPaths.TASKS, "garbage".toByteArray())
        r.controller.onPhoneMessage("node-1", "/not/ours", WatchBridgeCodec.encodeToBytes(Fx.snapshot(Fx.task("a"))))
        r.controller.onPhoneMessage("node-1", WatchPaths.ASK, WatchBridgeCodec.encodeToBytes(Fx.snapshot(Fx.task("a"))))
        assertNull(r.state.tasks.snapshot)
        assertEquals(Connection.Checking, r.state.connection)
    }

    @Test
    fun hydrate_opensOnTheLastKnownFacts_withTheirRealAge() = runTest {
        val store = MapStoreForController()
        val seed = SnapshotCache(store)
        seed.saveTasks(Fx.snapshot(Fx.task("a")), 1_000)
        seed.saveLoops(listOf(LoopEntry(Fx.run("r1"), 2_000)))
        seed.saveLastReply(ReplyRecord("r", "cached answer", "m", false, Usage.engineReported(1, 1), 3_000))
        val r = Rig(this, FakeLink(), store)
        r.controller.hydrate()
        assertEquals(1_000L, r.state.tasks.receivedAtMs)
        assertEquals(1, r.state.loops.runs.size)
        assertEquals("cached answer", r.state.lastReply!!.text)
        assertEquals("the connection is never claimed from a cache", Connection.Checking, r.state.connection)
    }

    // ---- the wrist only ever talks watch -> phone, on contract paths ----

    @Test
    fun everythingTheWatchSends_isOnAWatchToPhonePath() = runTest {
        val r = withTasks()
        r.controller.ask("hi")
        r.controller.toggleTask("a")
        r.controller.refresh()
        runCurrent()
        val allowed = setOf(WatchPaths.ASK, WatchPaths.TASK_TOGGLE, WatchPaths.SYNC)
        assertTrue(r.link.sent.isNotEmpty())
        for (s in r.link.sent) assertTrue("watch sent on ${s.path}", s.path in allowed)
    }
}

/** Distinct name so this file's private Rig can take a store without importing the core test helper (internal to another source set file). */
internal class MapStoreForController : KeyValueStore {
    val map = LinkedHashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}
