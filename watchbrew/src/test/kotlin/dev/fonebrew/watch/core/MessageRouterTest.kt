package dev.fonebrew.watch.core

import dev.fonebrew.watchbridge.AskError
import dev.fonebrew.watchbridge.ContractHello
import dev.fonebrew.watchbridge.DecodeResult
import dev.fonebrew.watchbridge.TaskToggleResult
import dev.fonebrew.watchbridge.WatchBridgeCodec
import dev.fonebrew.watchbridge.WatchMessage
import dev.fonebrew.watchbridge.WatchPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRouterTest {
    private val node = "node-1"
    private fun decode(m: WatchMessage) = WatchBridgeCodec.decode(WatchBridgeCodec.encodeToBytes(m))
    private fun route(path: String, m: WatchMessage) = MessageRouter.route(path, decode(m), node, 5_000)

    @Test
    fun hello_onTheHelloPath_becomesAHelloEvent() {
        val events = route(WatchPaths.HELLO, ContractHello.forThisBuild("0.3.1"))
        assertEquals(listOf<WatchEvent>(LinkEvent.HelloReceived(node, ContractHello.forThisBuild("0.3.1"))), events)
    }

    @Test
    fun taskSnapshot_isProofOfLife_andCarriesTheReceiptTime() {
        val snap = Fx.snapshot(Fx.task("a"))
        val events = route(WatchPaths.TASKS, snap)
        assertEquals(listOf(LinkEvent.PhoneHeard(node), TaskEvent.SnapshotReceived(snap, 5_000)), events)
    }

    @Test
    fun toggleResult_andLoopRun_routeToTheirReducers() {
        val applied = TaskToggleResult.Applied("a", true)
        assertEquals(listOf(LinkEvent.PhoneHeard(node), TaskEvent.ToggleResultReceived(applied)), route(WatchPaths.TASK_TOGGLE_RESULT, applied))
        val run = Fx.run("r1")
        assertEquals(listOf(LinkEvent.PhoneHeard(node), LoopEvent.RunReceived(run, 5_000)), route(WatchPaths.LOOPS, run))
    }

    @Test
    fun aMessageOnTheWrongPath_isDroppedNotInterpreted() {
        // A task snapshot is not a hello; a hello is not a task list.
        assertTrue(route(WatchPaths.HELLO, Fx.snapshot(Fx.task("a"))).isEmpty())
        assertTrue(route(WatchPaths.TASKS, ContractHello.forThisBuild("0.3.1")).isEmpty())
        assertTrue(route(WatchPaths.LOOPS, TaskToggleResult.Applied("a", true)).isEmpty())
        assertTrue(route(WatchPaths.TASK_TOGGLE_RESULT, Fx.run("r")).isEmpty())
    }

    @Test
    fun pathsThatAreNotPhoneToWatchMessages_areIgnored() {
        for (path in listOf(WatchPaths.ASK, WatchPaths.SYNC, WatchPaths.TASK_TOGGLE, WatchPaths.ASK_REPLY, "/other/app", "")) {
            assertTrue("path $path", route(path, ContractHello.forThisBuild("1")).isEmpty())
        }
    }

    @Test
    fun malformedBytes_areDropped_neverHalfApplied() {
        assertTrue(MessageRouter.route(WatchPaths.TASKS, WatchBridgeCodec.decode("{not json".toByteArray()), node, 1).isEmpty())
        assertTrue(MessageRouter.route(WatchPaths.TASKS, WatchBridgeCodec.decode(ByteArray(0)), node, 1).isEmpty())
    }

    @Test
    fun anEnvelopeFromAnotherContractVersion_becomesAnIncompatibleEvent() {
        val v2 = """{"contract":"watchbrew","v":2,"type":"ContractHello","payload":{"anything":"goes"}}"""
        val events = MessageRouter.route(WatchPaths.HELLO, WatchBridgeCodec.decode(v2), node, 1)
        assertEquals(1, events.size)
        val inc = events.single() as LinkEvent.EnvelopeIncompatible
        assertTrue(inc.mismatch.peerIsNewer)
    }

    // ---- the reply channel ----

    @Test
    fun replyFrames_chunksAndErrors_becomeAskEvents_withProofOfLife() {
        val chunk = Fx.token("hi")
        assertEquals(
            listOf(LinkEvent.PhoneHeard(node), AskEvent.ChunkReceived(chunk, 5_000)),
            MessageRouter.routeReplyFrame(decode(chunk), node, 5_000, Fx.ID),
        )
        val err = AskError(Fx.ID, "nope")
        assertEquals(
            listOf(LinkEvent.PhoneHeard(node), AskEvent.PhoneRefused(err)),
            MessageRouter.routeReplyFrame(decode(err), node, 5_000, Fx.ID),
        )
    }

    @Test
    fun aMalformedReplyFrame_damagesTheStream_aReplyWithAHoleIsNeverShownComplete() {
        val events = MessageRouter.routeReplyFrame(DecodeResult.Malformed("bad"), node, 1, Fx.ID)
        assertEquals(listOf<WatchEvent>(AskEvent.StreamDamaged(Fx.ID)), events)
        assertTrue(MessageRouter.routeReplyFrame(DecodeResult.Malformed("bad"), node, 1, null).isEmpty())
    }

    @Test
    fun anIncompatibleReplyFrame_marksTheLinkIncompatible_andFailsTheAsk() {
        val v2 = """{"contract":"watchbrew","v":2,"type":"AskChunk","payload":{}}"""
        val events = MessageRouter.routeReplyFrame(WatchBridgeCodec.decode(v2), node, 1, Fx.ID)
        assertTrue(events[0] is LinkEvent.EnvelopeIncompatible)
        assertEquals(AskEvent.StreamDamaged(Fx.ID), events[1])
    }

    @Test
    fun aNonReplyMessageInsideTheReplyChannel_isIgnored() {
        assertTrue(MessageRouter.routeReplyFrame(decode(ContractHello.forThisBuild("1")), node, 1, Fx.ID).isEmpty())
    }
}
