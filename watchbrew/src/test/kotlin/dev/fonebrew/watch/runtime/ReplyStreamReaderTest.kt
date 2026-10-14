package dev.fonebrew.watch.runtime

import dev.fonebrew.watch.core.Fx
import dev.fonebrew.watchbridge.AskChunk
import dev.fonebrew.watchbridge.DecodeResult
import dev.fonebrew.watchbridge.StreamFraming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class ReplyStreamReaderTest {
    private fun frames(vararg chunks: AskChunk): ByteArray = chunks.fold(ByteArray(0)) { acc, c -> acc + StreamFraming.encodeFrame(c) }

    @Test
    fun aWholeReply_isReadInOrder_andEndsClean() {
        val bytes = frames(Fx.token("a"), Fx.token("b"), Fx.terminal("c"))
        val results = mutableListOf<DecodeResult>()
        val end = ReplyStreamReader(ByteArrayInputStream(bytes)).readAll { results += it }
        assertEquals(StreamEnd.CLEAN, end)
        assertEquals(listOf("a", "b", "c"), results.map { ((it as DecodeResult.Decoded).message as AskChunk).text })
    }

    @Test
    fun theSameBytes_dribbledOneAtATime_decodeIdentically() {
        val bytes = frames(Fx.token("héllo "), Fx.terminal("wörld"))
        val results = mutableListOf<DecodeResult>()
        val end = ReplyStreamReader(ByteArrayInputStream(bytes), bufferSize = 1).readAll { results += it }
        assertEquals(StreamEnd.CLEAN, end)
        assertEquals(2, results.size)
    }

    @Test
    fun aStreamCutMidFrame_isTruncated_andYieldsOnlyTheCompleteFrames() {
        val bytes = frames(Fx.token("a"), Fx.token("b"))
        val cut = bytes.copyOf(bytes.size - 3)
        val results = mutableListOf<DecodeResult>()
        assertEquals(StreamEnd.TRUNCATED, ReplyStreamReader(ByteArrayInputStream(cut)).readAll { results += it })
        assertEquals(1, results.size)
    }

    @Test
    fun anEmptyStream_isClean_withNoFrames() {
        val results = mutableListOf<DecodeResult>()
        assertEquals(StreamEnd.CLEAN, ReplyStreamReader(ByteArrayInputStream(ByteArray(0))).readAll { results += it })
        assertTrue(results.isEmpty())
    }

    @Test
    fun anInvalidLength_isBroken_andStopsReading() {
        val bad = byteArrayOf(0, 0, 0, 0) + frames(Fx.token("never seen"))
        val results = mutableListOf<DecodeResult>()
        assertEquals(StreamEnd.BROKEN, ReplyStreamReader(ByteArrayInputStream(bad)).readAll { results += it })
        assertEquals(1, results.size)
        assertTrue(results.single() is DecodeResult.Malformed)
    }

    @Test
    fun aTransportFailure_isReported_notThrown() {
        val boom = object : InputStream() {
            override fun read(): Int = throw IOException("link dropped")
            override fun read(b: ByteArray): Int = throw IOException("link dropped")
        }
        assertEquals(StreamEnd.IO_FAILURE, ReplyStreamReader(boom).readAll { })
    }

    @Test
    fun aFrameThatDoesNotDecode_isMalformedForThatFrameOnly_laterFramesStillDecode() {
        val garbage = "not json at all".toByteArray()
        val header = byteArrayOf(0, 0, 0, garbage.size.toByte())
        val bytes = header + garbage + frames(Fx.terminal("ok"))
        val results = mutableListOf<DecodeResult>()
        assertEquals(StreamEnd.CLEAN, ReplyStreamReader(ByteArrayInputStream(bytes)).readAll { results += it })
        assertTrue(results[0] is DecodeResult.Malformed)
        assertTrue(results[1] is DecodeResult.Decoded)
    }
}
