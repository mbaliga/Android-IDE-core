package dev.fonebrew.watchbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every message encodes and decodes back to an equal value, over text and over UTF-8 bytes. */
class RoundTripTest {

    @Test
    fun everyMessageType_isCoveredBySamples() {
        // The codec's `when` is compile-time exhaustive; this pins that the SAMPLES keep up with it.
        val covered = ALL_SAMPLES.map { it.wireType() }.toSet()
        assertEquals(WireType.entries.toSet(), covered)
        assertEquals(8, WireType.entries.size)
    }

    @Test
    fun everySample_roundTripsThroughTextAndBytes() {
        for (sample in ALL_SAMPLES) assertRoundTrips(sample)
    }

    @Test
    fun encodingIsStable_decodeThenEncodeDecodesToTheSameValue() {
        for (sample in ALL_SAMPLES) {
            val once = WatchBridgeCodec.encode(sample)
            val decoded = (WatchBridgeCodec.decode(once) as DecodeResult.Decoded).message
            val twice = WatchBridgeCodec.encode(decoded)
            assertEquals(sample, (WatchBridgeCodec.decode(twice) as DecodeResult.Decoded).message)
        }
    }

    @Test
    fun hello_roundTrips() {
        val hello = decodedAs<ContractHello>(WatchBridgeCodec.encode(ContractHello(1, "0.3.1")))
        assertEquals(1, hello.contractVersion)
        assertEquals("0.3.1", hello.phoneAppVersion)
    }

    @Test
    fun askRequest_preservesTrickyTextExactly() {
        val prompt = "a \"q\" \\ / \n\t \u0001 </script> \u2028 ï 日本語 🙂  trailing  "
        val back = decodedAs<AskRequest>(WatchBridgeCodec.encode(AskRequest("r", prompt)))
        assertEquals(prompt, back.prompt) // not trimmed, not normalized
    }

    @Test
    fun askChunk_streamedReply_keepsOrderIdentityAndWatchedObject() {
        // A cloud reply as the phone would send it: tokens, then the terminal chunk.
        val wire = listOf(
            AskChunk.token("r1", "Hel", "cloud-model-a", watchedObject = true),
            AskChunk.token("r1", "lo ", "cloud-model-a", watchedObject = true),
            AskChunk.terminal("r1", "there", "cloud-model-a", true, Usage.engineReported(5, 3)),
        ).map { WatchBridgeCodec.encodeToBytes(it) }

        val received = wire.map { decodedAs<AskChunk>(String(it, Charsets.UTF_8)) }
        assertEquals("Hello there", received.joinToString("") { it.text })
        assertTrue("watched-object glyph must reach the wrist on EVERY chunk of a cloud reply", received.all { it.watchedObject })
        assertEquals(listOf(false, false, true), received.map { it.terminal })
        assertEquals(listOf(null, null, Usage.engineReported(5, 3)), received.map { it.usage })
    }

    @Test
    fun askChunk_onDeviceReplyCarriesNoWatchedObjectMark() {
        val back = decodedAs<AskChunk>(WatchBridgeCodec.encode(AskChunk.token("r", "x", "local-model-a", watchedObject = false)))
        assertFalse(back.watchedObject)
        assertEquals("local-model-a", back.engineId)
    }

    @Test
    fun usage_zeroAndUnknownStayDistinct() {
        val realZero = decodedAs<AskChunk>(WatchBridgeCodec.encode(AskChunk.terminal("r", "", "e", false, Usage.engineReported(0, 0))))
        assertEquals(Usage(0, 0, estimated = false), realZero.usage)

        val unknown = decodedAs<AskChunk>(WatchBridgeCodec.encode(AskChunk.terminal("r", "", "e", false, Usage.unknown())))
        assertNull(unknown.usage!!.inputTokens)
        assertNull(unknown.usage!!.outputTokens)
        assertTrue(unknown.usage!!.estimated)
    }

    @Test
    fun taskSnapshot_truncatedListKeepsTheTruePendingCount() {
        val snap = TaskSnapshot(listOf(TaskEntry("t1", "Buy milk", WatchTaskState.TODO)), pendingCount = 17)
        val back = decodedAs<TaskSnapshot>(WatchBridgeCodec.encode(snap))
        assertEquals(1, back.tasks.size)
        assertEquals(17, back.pendingCount)
    }

    @Test
    fun taskToggle_requestAndBothResults_roundTripWithTheirOwnShape() {
        assertEquals(TaskToggleRequest("t", true), decodedAs<TaskToggleRequest>(WatchBridgeCodec.encode(TaskToggleRequest("t", true))))
        val applied = decodedAs<TaskToggleResult>(WatchBridgeCodec.encode(TaskToggleResult.Applied("t", false)))
        assertEquals(TaskToggleResult.Applied("t", false), applied)
        val rejected = decodedAs<TaskToggleResult>(WatchBridgeCodec.encode(TaskToggleResult.Rejected("t", "gone")))
        assertEquals(TaskToggleResult.Rejected("t", "gone"), rejected)
    }

    @Test
    fun loopRunSnapshot_spendAbsentIsNotZero() {
        val none = decodedAs<LoopRunSnapshot>(WatchBridgeCodec.encode(LoopRunSnapshot("r", "L", "running", null, spend = null)))
        assertNull("no ledger figure must stay null, not become a real zero", none.spend)
        val zero = decodedAs<LoopRunSnapshot>(WatchBridgeCodec.encode(LoopRunSnapshot("r", "L", "running", null, Spend(0, "USD", false))))
        assertEquals(Spend(0, "USD", false), zero.spend)
    }

    @Test
    fun largePrompt_roundTrips() {
        val big = "x".repeat(1_000_000)
        assertRoundTrips(AskRequest("r", big))
    }
}
