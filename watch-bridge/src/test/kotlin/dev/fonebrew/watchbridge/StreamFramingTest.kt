package dev.fonebrew.watchbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Length-prefixed framing over an arbitrary-chunked byte stream. */
class StreamFramingTest {

    private val reply = listOf<WatchMessage>(
        AskChunk.token("r1", "Hel", "local-a", watchedObject = false),
        AskChunk.token("r1", "lo 🙂", "local-a", watchedObject = false),
        AskChunk.terminal("r1", "", "local-a", watchedObject = false, usage = Usage.estimate(3, 4)),
    )

    private fun stream(messages: List<WatchMessage>): ByteArray =
        messages.map { StreamFraming.encodeFrame(it) }.reduce { a, b -> a + b }

    private fun decoded(results: List<DecodeResult>): List<WatchMessage> =
        results.map { (it as DecodeResult.Decoded).message }

    @Test
    fun frame_isBigEndianLengthThenTheExactCodecBytes() {
        val message = AskError("r1", "boom")
        val frame = StreamFraming.encodeFrame(message)
        val payload = WatchBridgeCodec.encodeToBytes(message)
        assertEquals(StreamFraming.HEADER_BYTES + payload.size, frame.size)
        val len = ((frame[0].toInt() and 0xFF) shl 24) or ((frame[1].toInt() and 0xFF) shl 16) or
            ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
        assertEquals(payload.size, len)
        assertTrue(payload.contentEquals(frame.copyOfRange(4, frame.size)))
    }

    @Test
    fun wholeStreamInOneFeed_decodesEveryFrameInOrder() {
        val decoder = FrameDecoder()
        assertEquals(reply, decoded(decoder.feed(stream(reply))))
        assertEquals(0, decoder.bufferedBytes)
        assertFalse(decoder.isBroken)
    }

    @Test
    fun oneByteAtATime_stillDecodesEveryFrame() {
        val bytes = stream(reply)
        val decoder = FrameDecoder()
        val out = ArrayList<DecodeResult>()
        for (b in bytes) out += decoder.feed(byteArrayOf(b))
        assertEquals(reply, decoded(out))
        assertEquals(0, decoder.bufferedBytes)
    }

    @Test
    fun everySplitPoint_decodesTheSameFrames() {
        val bytes = stream(reply)
        for (cut in 0..bytes.size) {
            val decoder = FrameDecoder()
            val out = decoder.feed(bytes, 0, cut) + decoder.feed(bytes, cut, bytes.size - cut)
            assertEquals("split at $cut", reply, decoded(out))
        }
    }

    @Test
    fun randomChunking_decodesTheSameFrames() {
        val bytes = stream(reply)
        val rnd = Random(42)
        repeat(200) {
            val decoder = FrameDecoder()
            val out = ArrayList<DecodeResult>()
            var at = 0
            while (at < bytes.size) {
                val take = (1 + rnd.nextInt(17)).coerceAtMost(bytes.size - at)
                out += decoder.feed(bytes, at, take)
                at += take
            }
            assertEquals(reply, decoded(out))
        }
    }

    @Test
    fun truncatedTail_isVisibleAsBufferedBytes_andNeverYieldsAPartialMessage() {
        val bytes = stream(reply)
        val cutShort = bytes.copyOf(bytes.size - 5)
        val decoder = FrameDecoder()
        val out = decoder.feed(cutShort)
        assertEquals(reply.dropLast(1), decoded(out)) // the terminal frame never arrived whole
        assertTrue("a cut frame must be visible to the reader", decoder.bufferedBytes > 0)
    }

    @Test
    fun malformedBody_isThatFramesProblemOnly_laterFramesStillDecode() {
        val badPayload = """{"not":"an envelope"}""".toByteArray(Charsets.UTF_8)
        val badFrame = byteArrayOf(0, 0, 0, badPayload.size.toByte()) + badPayload
        val bytes = StreamFraming.encodeFrame(reply[0]) + badFrame + StreamFraming.encodeFrame(reply[2])
        val decoder = FrameDecoder()
        val out = decoder.feed(bytes)
        assertEquals(3, out.size)
        assertEquals(DecodeResult.Decoded(reply[0]), out[0])
        assertTrue(out[1] is DecodeResult.Malformed)
        assertEquals(DecodeResult.Decoded(reply[2]), out[2])
        assertFalse("boundaries were intact, the stream is not broken", decoder.isBroken)
    }

    @Test
    fun incompatibleVersionFrame_isReportedAsIncompatible_notDropped() {
        val newer = env("AskChunk", "{}", v = "2").toByteArray(Charsets.UTF_8)
        val frame = byteArrayOf(0, 0, 0, newer.size.toByte()) + newer
        val out = FrameDecoder().feed(frame)
        assertEquals(1, out.size)
        assertTrue(out.single() is DecodeResult.Incompatible)
    }

    @Test
    fun zeroLength_breaksTheStream_andTheDecoderStaysBroken() {
        val decoder = FrameDecoder()
        val out = decoder.feed(byteArrayOf(0, 0, 0, 0) + StreamFraming.encodeFrame(reply[0]))
        assertEquals(1, out.size)
        assertTrue(out.single() is DecodeResult.Malformed)
        assertTrue(decoder.isBroken)
        assertTrue("a broken stream yields nothing more", decoder.feed(StreamFraming.encodeFrame(reply[0])).isEmpty())
        assertEquals(0, decoder.bufferedBytes)
    }

    @Test
    fun oversizeAndNegativeLengths_breakTheStream_beforeAnyAllocation() {
        for (header in listOf(
            byteArrayOf(0, 1, 0, 1), // 65537 > MAX
            byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            byteArrayOf(0x80.toByte(), 0, 0, 0), // sign bit: would be negative as an Int
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
        )) {
            val decoder = FrameDecoder()
            val out = decoder.feed(header)
            assertEquals(1, out.size)
            assertTrue(out.single() is DecodeResult.Malformed)
            assertTrue(decoder.isBroken)
        }
    }

    @Test
    fun maxLengthIsAccepted_asAHeader() {
        // 65536 is the largest legal declared length: the decoder waits for the body, it does not reject.
        val decoder = FrameDecoder()
        val out = decoder.feed(byteArrayOf(0, 1, 0, 0))
        assertTrue(out.isEmpty())
        assertFalse(decoder.isBroken)
        assertEquals(4, decoder.bufferedBytes)
    }

    @Test
    fun encodeFrame_refusesAnOversizedPayload_asASenderBug() {
        val huge = AskChunk.token("r", "x".repeat(StreamFraming.MAX_FRAME_BYTES), "e", false)
        assertRejected { StreamFraming.encodeFrame(huge) }
    }

    @Test
    fun badFeedRange_isACallerBug() {
        assertRejected { FrameDecoder().feed(ByteArray(2), 1, 5) }
        assertRejected { FrameDecoder().feed(ByteArray(2), -1, 1) }
    }

    @Test
    fun everySample_survivesFramingInASingleStream() {
        val framed = ALL_SAMPLES.map { StreamFraming.encodeFrame(it) }.reduce { a, b -> a + b }
        val decoder = FrameDecoder()
        assertEquals(ALL_SAMPLES, decoded(decoder.feed(framed)))
        assertEquals(0, decoder.bufferedBytes)
    }
}
