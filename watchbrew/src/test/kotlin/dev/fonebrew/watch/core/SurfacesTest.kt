package dev.fonebrew.watch.core

import dev.fonebrew.watchbridge.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfacesTest {

    // ---- complication ----

    @Test
    fun unknownCount_isDoubleDash_neverZero() {
        val c = ComplicationText.derive(null, null, 0)
        assertEquals("--", c.text)
        assertTrue(c.contentDescription.contains("not received"))
    }

    @Test
    fun aRealZero_isZero_andSaysNoPendingTasks() {
        val c = ComplicationText.derive(0, receivedAtMs = 1_000, nowMs = 2_000)
        assertEquals("0", c.text)
        assertEquals("No pending tasks", c.contentDescription)
    }

    @Test
    fun freshCount_isPlain_withSingularAndPlural() {
        assertEquals("1 pending task", ComplicationText.derive(1, 0, 1_000).contentDescription)
        val c = ComplicationText.derive(7, 0, 1_000)
        assertEquals("7", c.text)
        assertEquals("Tasks", c.title)
        assertEquals("7 pending tasks", c.contentDescription)
    }

    @Test
    fun overNinetyNine_readsNinetyNinePlus() {
        assertEquals("99", ComplicationText.derive(99, 0, 0).text)
        assertEquals("99+", ComplicationText.derive(100, 0, 0).text)
        assertEquals("99+", ComplicationText.derive(4_000, 0, 0).text)
    }

    @Test
    fun anOldCount_isMarkedWithAQuestionMark_andSpokenAsPossiblyOutOfDate() {
        val now = ComplicationText.STALE_AFTER_MS + 5_000
        val c = ComplicationText.derive(3, receivedAtMs = 0, nowMs = now)
        assertEquals("3?", c.text)
        assertTrue(c.contentDescription.contains("May be out of date"))
        assertTrue(c.contentDescription.contains("as of 1 h ago"))
        // exactly at the boundary is still fresh
        assertEquals("3", ComplicationText.derive(3, 0, ComplicationText.STALE_AFTER_MS).text)
    }

    @Test
    fun aCountWithNoKnownAge_isTreatedAsStale() {
        assertEquals("3?", ComplicationText.derive(3, null, 5).text)
    }

    @Test
    fun shortTextStaysShort() {
        for (n in listOf(0, 5, 99, 100)) assertTrue(ComplicationText.derive(n, null, 0).text.length <= 4)
    }

    // ---- tile ----

    private val reply = ReplyRecord("r", "The capital of France is Paris.", "local-engine", false, Usage.engineReported(1, 2), completedAtMs = 0)

    @Test
    fun tileWithNoReplyYet_saysSo() {
        val t = TilePresenter.present(null, Connection.Reachable(Fx.phone, null), 0)
        assertNull(t.snippet)
        assertNull(t.provenance)
        assertNull(t.linkNote)
        assertTrue(t.contentDescription.contains(TilePresenter.NO_REPLY))
    }

    @Test
    fun tileShowsSnippetEngineIdentityAndAge() {
        val t = TilePresenter.present(reply, Connection.Reachable(Fx.phone, null), 120_000)
        assertEquals("The capital of France is Paris.", t.snippet)
        assertEquals(Glyph.ON_DEVICE, t.provenance!!.glyph)
        assertEquals("2 min ago", t.age)
    }

    @Test
    fun aWatchedReplyOnTheTile_carriesTheWatchedGlyphAndWords() {
        val t = TilePresenter.present(reply.copy(engineId = "cloud-x", watchedObject = true), Connection.Checking, 0)
        assertEquals(Glyph.CLOUD_WATCHED, t.provenance!!.glyph)
        assertEquals("cloud · watched", t.provenance!!.label)
        assertTrue(t.contentDescription.contains("watched"))
    }

    @Test
    fun tileSaysSoInWordsWhenThePhoneIsUnreachable() {
        val t = TilePresenter.present(reply, Connection.Unreachable(UnreachableCause.NO_PHONE_CONNECTED), 0)
        assertEquals("Phone not reachable", t.linkNote)
    }

    @Test
    fun snippet_collapsesWhitespace_andMarksATruncationWithAnEllipsis() {
        assertEquals("a b c", TilePresenter.snippet("a\n\n  b\tc  "))
        val long = "word ".repeat(40)
        val s = TilePresenter.snippet(long)
        assertTrue(s.endsWith("…"))
        assertTrue(s.length <= TilePresenter.SNIPPET_CHARS)
        assertFalse("cut at a word boundary", s.removeSuffix("…").endsWith("wor"))
        assertEquals("(empty reply)", TilePresenter.snippet("   \n "))
    }

    @Test
    fun snippet_neverSplitsASurrogatePair() {
        val text = "x".repeat(TilePresenter.SNIPPET_CHARS - 2) + "😀😀😀"
        val s = TilePresenter.snippet(text)
        assertTrue(s.endsWith("…"))
        val body = s.removeSuffix("…")
        assertFalse("body ends in a lone high surrogate", body.isNotEmpty() && body.last().isHighSurrogate())
    }

    // ---- usage / provenance ----

    @Test
    fun usage_realCountsPrintPlain() {
        assertEquals("in 12 · out 40", UsageLabel.line(Usage.engineReported(12, 40)))
    }

    @Test
    fun usage_estimatesCarryTildeAndEst_andUnknownIsNeverZero() {
        assertEquals("est. in ~12 · out ~40", UsageLabel.line(Usage.estimate(12, 40)))
        assertEquals("est. in ~12 · out unknown", UsageLabel.line(Usage.estimate(12, null)))
        assertEquals("token usage unknown", UsageLabel.line(Usage.unknown()))
        assertEquals("in 0 · out 0", UsageLabel.line(Usage.engineReported(0, 0))) // a REAL zero is a zero
    }

    @Test
    fun provenance_cloudIsWatched_onDeviceIsNot_withDistinctShapesAndWords() {
        val cloud = ProvenancePresenter.of("cloud-engine-x", true)
        val local = ProvenancePresenter.of("local-engine", false)
        assertTrue(cloud.watched && !local.watched)
        assertEquals(Glyph.CLOUD_WATCHED, cloud.glyph)
        assertEquals(Glyph.ON_DEVICE, local.glyph)
        assertEquals("cloud · watched", cloud.label)
        assertEquals("on-device", local.label)
        assertTrue(cloud.spoken.contains("watched object"))
    }

    @Test
    fun aLongEngineId_isShortenedWithAVisibleEllipsis_aShortOneIsUntouched() {
        assertEquals("m", ProvenancePresenter.of("m", false).engine)
        val long = ProvenancePresenter.of("x".repeat(60), true).engine
        assertTrue(long.endsWith("…") && long.length == 24)
    }

    // ---- relative time ----

    @Test
    fun relativeTime_isCoarse_andDoesNotTrustAFutureTimestamp() {
        assertEquals("just now", RelativeTime.ago(1_000, 1_500))
        assertEquals("just now", RelativeTime.ago(10_000, 1_000)) // clocks disagree: no pretending
        assertEquals("1 min ago", RelativeTime.ago(0, 60_000))
        assertEquals("59 min ago", RelativeTime.ago(0, 59 * 60_000L))
        assertEquals("1 h ago", RelativeTime.ago(0, 60 * 60_000L))
        assertEquals("2 d ago", RelativeTime.ago(0, 49 * 60 * 60_000L))
        assertEquals(0, RelativeTime.elapsedSeconds(5_000, 1_000))
        assertEquals(12, RelativeTime.elapsedSeconds(0, 12_900))
    }

    // ---- prompt policy ----

    @Test
    fun prompt_isTrimmed_andBlankOrHugeOrBrokenIsRejectedInWords() {
        assertEquals(PromptPolicy.Result.Ok("hi there"), PromptPolicy.check("  hi there \n"))
        assertTrue(PromptPolicy.check(null) is PromptPolicy.Result.Rejected)
        assertTrue(PromptPolicy.check("   ") is PromptPolicy.Result.Rejected)
        val tooLong = PromptPolicy.check("a".repeat(PromptPolicy.MAX_CHARS + 1)) as PromptPolicy.Result.Rejected
        assertTrue(tooLong.reason.contains("Too long"))
        assertTrue(PromptPolicy.check("a".repeat(PromptPolicy.MAX_CHARS)) is PromptPolicy.Result.Ok)
        assertTrue(PromptPolicy.check("lone \uD83D surrogate") is PromptPolicy.Result.Rejected)
        assertTrue(PromptPolicy.check("emoji 😀 ok") is PromptPolicy.Result.Ok)
    }
}
