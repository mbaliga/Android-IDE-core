package dev.fonebrew.data.watch

import dev.fonebrew.watchbridge.AskChunk
import dev.fonebrew.watchbridge.DecodeResult
import dev.fonebrew.watchbridge.StreamFraming
import dev.fonebrew.watchbridge.Usage
import dev.fonebrew.watchbridge.WatchBridgeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTextTest {

    @Test
    fun wellFormedText_isReturnedAsTheSameInstance() {
        val s = "plain 🙂 text 日本"
        assertSame(s, s.toWireSafe())
    }

    @Test
    fun loneSurrogates_becomeReplacementChars_pairsSurvive() {
        assertEquals("a�b", "a\uD83Db".toWireSafe())
        assertEquals("a�b", "a\uDE42b".toWireSafe())
        assertEquals("�", "\uD83D".toWireSafe())
        assertEquals("x🙂y�", "x🙂y\uD83D".toWireSafe())
        assertEquals("🙂�🙂", "🙂\uDE42🙂".toWireSafe())
    }

    @Test
    fun truncation_cutsWithVisibleEllipsis_andNeverSplitsAPair() {
        assertEquals("short", "short".toWireTruncated(10))
        assertEquals("abcd…", "abcdefghij".toWireTruncated(5))
        // The 4th and 5th chars are an emoji pair: cutting at 5 total (4 + ellipsis) would strand the high half.
        val s = "abc🙂def"
        val out = s.toWireTruncated(5)
        assertEquals("abc…", out)
        assertTrue(out.length <= 5)
    }

    @Test
    fun truncation_ofIllFormedText_isStillWireSafe() {
        val out = "ab\uD83Dcdefgh".toWireTruncated(6)
        assertTrue(out.none { it.isSurrogate() })
        assertTrue(out.endsWith("…"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncation_needsRoomForTheEllipsis() {
        "abc".toWireTruncated(1)
    }
}

class ReplyChunkerTest {

    private fun chunker(max: Int = ReplyChunker.DEFAULT_MAX_CHUNK_CHARS, watched: Boolean = false) =
        ReplyChunker("req-1", "tiny", watched, max)

    @Test
    fun tokens_passThroughVerbatim_withNoTrimAndNoEmptyChunks() {
        val c = chunker()
        assertEquals(listOf(" Hel"), c.onToken(" Hel").map { it.text })
        assertEquals(emptyList<AskChunk>(), c.onToken(""))
        assertEquals(listOf("lo \n"), c.onToken("lo \n").map { it.text })
    }

    @Test
    fun everyChunk_carriesRequestIdEngineIdAndWatchedFlag_withNoUsageUntilTerminal() {
        val c = chunker(watched = true)
        val chunks = c.onToken("a") + c.onToken("b") + c.finish(Usage.engineReported(3, 4))
        assertEquals(3, chunks.size)
        for (chunk in chunks) {
            assertEquals("req-1", chunk.requestId)
            assertEquals("tiny", chunk.engineId)
            assertTrue("watchedObject must be on every chunk, not only the last", chunk.watchedObject)
        }
        assertEquals(listOf(false, false, true), chunks.map { it.terminal })
        assertEquals(listOf(null, null, Usage.engineReported(3, 4)), chunks.map { it.usage })
    }

    @Test
    fun onDeviceChunks_neverClaimWatched() {
        val c = chunker(watched = false)
        assertTrue((c.onToken("x") + c.finish(Usage.unknown())).none { it.watchedObject })
    }

    @Test
    fun terminalChunk_isAlwaysTheLast_andCarriesUsageExactlyAsGiven() {
        val c = chunker()
        for (usage in listOf(Usage.engineReported(1, 2), Usage.estimate(5, null), Usage.estimate(null, null), Usage.unknown())) {
            val t = ReplyChunker("r", "e", false).finish(usage)
            assertTrue(t.terminal)
            assertEquals(usage, t.usage)
            assertEquals("", t.text)
        }
        c.finish(Usage.unknown())
    }

    @Test
    fun anEmojiSplitAcrossTwoTokens_isJoinedNotCorrupted() {
        val c = chunker()
        assertEquals(emptyList<AskChunk>(), c.onToken("\uD83D"))                  // held back
        assertEquals(listOf("🙂!"), c.onToken("\uDE42!").map { it.text }) // joined with its partner
    }

    @Test
    fun aTokenEndingInAHighSurrogate_emitsTheRestNowAndHoldsOnlyThatHalf() {
        val c = chunker()
        assertEquals(listOf("hi "), c.onToken("hi \uD83D").map { it.text })
        assertEquals(listOf("🙂"), c.onToken("\uDE42").map { it.text })
    }

    @Test
    fun aHeldSurrogateThatNeverPairs_becomesAReplacementChar_inTheTerminalChunk() {
        val c = chunker()
        assertEquals(emptyList<AskChunk>(), c.onToken("\uD83D"))
        val terminal = c.finish(Usage.unknown())
        assertEquals("�", terminal.text)
    }

    @Test
    fun aStrayLowSurrogate_orAHighFollowedByText_isReplaced_notThrown() {
        assertEquals(listOf("a�b"), chunker().onToken("a\uDE42b").map { it.text })
        val c = chunker()
        c.onToken("\uD83D")
        assertEquals(listOf("�z"), c.onToken("z").map { it.text })
    }

    @Test
    fun oversizedToken_isSplit_neverMidPair_andReassemblesExactly() {
        val big = "ab" + "🙂".repeat(10) + "cd"
        val c = chunker(max = 5)
        val parts = c.onToken(big).map { it.text }
        assertTrue(parts.all { it.length <= 5 })
        assertEquals(big, parts.joinToString(""))
        for (p in parts) assertTrue("a part split a pair: $p", p.none { it.isSurrogate() } || p == p.toWireSafe())
    }

    @Test
    fun everyProducedChunk_framesAndDecodesBackIntact() {
        val c = chunker(max = 7)
        val chunks = c.onToken("hello wide world 🙂") + c.finish(Usage.estimate(2, 3))
        for (chunk in chunks) {
            val frame = StreamFraming.encodeFrame(chunk)
            assertTrue(frame.size <= StreamFraming.HEADER_BYTES + StreamFraming.MAX_FRAME_BYTES)
            assertEquals(DecodeResult.Decoded(chunk), WatchBridgeCodec.decode(frame.copyOfRange(StreamFraming.HEADER_BYTES, frame.size)))
        }
    }

    @Test
    fun concatenatedChunks_equalTheStreamedText() {
        val c = chunker(max = 3)
        val streamed = listOf("The ", "quick \uD83E", "\uDD8A jumps", "", " over")
        val out = StringBuilder()
        for (t in streamed) c.onToken(t).forEach { out.append(it.text) }
        out.append(c.finish(Usage.unknown()).text)
        assertEquals(streamed.joinToString(""), out.toString())
    }

    @Test
    fun usingTheChunkerAfterFinish_isACallerBug() {
        val c = chunker()
        c.finish(Usage.unknown())
        try { c.onToken("x"); org.junit.Assert.fail("expected IllegalStateException") } catch (e: IllegalStateException) { assertNotNull(e.message) }
        try { c.finish(Usage.unknown()); org.junit.Assert.fail("expected IllegalStateException") } catch (e: IllegalStateException) { assertNotNull(e.message) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun aMaxChunkTooSmallToHoldAPair_isRejected() {
        ReplyChunker("r", "e", false, maxChunkChars = 1)
    }

    @Test
    fun noUsageOnNonTerminalChunks_byConstruction() {
        val c = chunker()
        assertNull(c.onToken("a").single().usage)
    }
}
