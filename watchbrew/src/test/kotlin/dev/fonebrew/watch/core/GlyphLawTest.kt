package dev.fonebrew.watch.core

import dev.fonebrew.watchbridge.StateGlyphKeys
import dev.fonebrew.watchbridge.WatchTaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colour-blindness rule as tests: the owner is red-green colour-blind, so every state is carried
 * by a shape and words, and no presenter string leans on a hue word to say what a state is.
 */
class GlyphLawTest {

    private val hueWords = Regex("""\b(red|green|amber|orange|yellow|blue|purple|violet|pink|colou?r)\b""", RegexOption.IGNORE_CASE)

    private fun allUserStrings(): List<String> {
        val strings = mutableListOf<String>()
        for (state in WatchTaskState.entries) strings += TasksPresenter.stateLabel(state)
        for (key in StateGlyphKeys.V1) strings += LoopsPresenter.glyphFor(key).second
        for (failure in listOf(
            AskFailure.PhoneRefused("x"), AskFailure.NoAnswer, AskFailure.Stalled, AskFailure.LinkLost,
            AskFailure.DamagedStream, AskFailure.SendFailed(LinkFailure.NO_NODE), AskFailure.SendFailed(LinkFailure.OTHER),
        )) AskFailurePresenter.present(failure).let { strings += it.headline; strings += it.detail }
        for (c in listOf(
            Connection.Checking, Connection.Reachable(Fx.phone, "1"), Connection.Unreachable(UnreachableCause.NO_PHONE_CONNECTED),
            Connection.Unreachable(UnreachableCause.LINK_ERROR), Connection.Unreachable(UnreachableCause.LINK_UNAVAILABLE),
        )) ConnectionPresenter.present(c).let { strings += it.headline; strings += it.detail }
        strings += ProvenancePresenter.of("m", true).label
        strings += ProvenancePresenter.of("m", false).label
        return strings
    }

    @Test
    fun noStateIsNamedByAHue() {
        for (s in allUserStrings()) assertFalse("'$s' leans on a hue word", hueWords.containsMatchIn(s))
    }

    @Test
    fun taskStates_haveFourDistinctShapesAndFourDistinctWords() {
        val glyphs = WatchTaskState.entries.map { TasksPresenter.glyph(it) }
        val words = WatchTaskState.entries.map { TasksPresenter.stateLabel(it) }
        assertEquals(4, glyphs.toSet().size)
        assertEquals(4, words.toSet().size)
    }

    @Test
    fun loopStates_haveDistinctShapes_andTheUnknownShapeIsItsOwn() {
        val glyphs = (StateGlyphKeys.V1 + "something_new").map { LoopsPresenter.glyphFor(it).first }
        assertEquals(glyphs.size, glyphs.toSet().size)
    }

    @Test
    fun provenance_watchedAndOnDevice_differByShapeAndWords_notHue() {
        val a = ProvenancePresenter.of("m", true)
        val b = ProvenancePresenter.of("m", false)
        assertTrue(a.glyph != b.glyph && a.label != b.label)
    }

    @Test
    fun everyGlyphIsUsedByAPresenter_orIsKnownToBeUIOnly() {
        // A glyph nobody chooses is dead vocabulary; keep the enum honest.
        val used = buildSet {
            WatchTaskState.entries.forEach { add(TasksPresenter.glyph(it)) }
            (StateGlyphKeys.V1 + "x").forEach { add(LoopsPresenter.glyphFor(it).first) }
            add(ProvenancePresenter.of("m", true).glyph)
            add(ProvenancePresenter.of("m", false).glyph)
            listOf(
                Connection.Checking, Connection.Reachable(Fx.phone, null), Connection.Unreachable(UnreachableCause.LINK_ERROR),
                Connection.Incompatible(dev.fonebrew.watchbridge.VersionMismatch(2, 1), null),
            ).forEach { add(ConnectionPresenter.present(it).glyph) }
            listOf(AskFailure.NoAnswer, AskFailure.PhoneRefused("x")).forEach { add(AskFailurePresenter.present(it).glyph) }
        }
        assertEquals(Glyph.entries.toSet(), used)
    }
}
