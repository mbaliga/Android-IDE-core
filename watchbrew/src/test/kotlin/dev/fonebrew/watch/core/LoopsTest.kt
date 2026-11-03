package dev.fonebrew.watch.core

import dev.fonebrew.watchbridge.Spend
import dev.fonebrew.watchbridge.StateGlyphKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopsTest {
    private fun reduce(s: LoopsState, vararg events: LoopEvent) = events.fold(s) { acc, e -> LoopsReducer.reduce(acc, e) }

    @Test
    fun aRunUpdate_replacesThatRun_andMovesItToTheFront() {
        val s = reduce(
            LoopsState(),
            LoopEvent.RunReceived(Fx.run("r1", step = "fetch"), 1_000),
            LoopEvent.RunReceived(Fx.run("r2"), 2_000),
            LoopEvent.RunReceived(Fx.run("r1", glyph = "complete", step = "done"), 3_000),
        )
        assertEquals(listOf("r1", "r2"), s.runs.map { it.snapshot.runId })
        assertEquals("complete", s.runs.first().snapshot.stateGlyphKey)
        assertEquals(3_000L, s.runs.first().receivedAtMs)
    }

    @Test
    fun theListIsBounded_oldestEvicted() {
        val events = (1..LoopsState.MAX_RUNS + 5).map { LoopEvent.RunReceived(Fx.run("r$it"), it.toLong()) }
        val s = reduce(LoopsState(), *events.toTypedArray())
        assertEquals(LoopsState.MAX_RUNS, s.runs.size)
        assertEquals("r${LoopsState.MAX_RUNS + 5}", s.runs.first().snapshot.runId)
        assertTrue(s.runs.none { it.snapshot.runId == "r1" })
    }

    // ---- state shapes ----

    @Test
    fun everyV1State_hasItsOwnShape_andWord() {
        val pairs = StateGlyphKeys.V1.map { LoopsPresenter.glyphFor(it) }
        assertEquals(StateGlyphKeys.V1.size, pairs.map { it.first }.toSet().size)
        assertEquals(listOf("running", "complete", "stopped", "failed"), pairs.map { it.second })
    }

    @Test
    fun anUnknownKeyFromANewerPhone_getsTheGenericShape_andIsLabelledByItsOwnKey() {
        val (glyph, label) = LoopsPresenter.glyphFor("paused_for_review")
        assertEquals(Glyph.LOOP_UNKNOWN, glyph)
        assertEquals("paused_for_review", label)
        assertTrue(Glyph.LOOP_UNKNOWN !in StateGlyphKeys.V1.map { LoopsPresenter.glyphFor(it).first })
    }

    // ---- rows ----

    @Test
    fun aRow_carriesStateStepSpendAndAge_asWords() {
        val entry = LoopEntry(Fx.run("r1", name = "Nightly digest", step = "summarise", spend = Spend(125, "USD", estimated = false)), 1_000)
        val row = LoopsPresenter.row(entry, nowMs = 1_000 + 3 * 60_000)
        assertEquals("Nightly digest", row.name)
        assertEquals("running", row.stateLabel)
        assertEquals("step: summarise", row.stepLine)
        assertEquals("spend: USD 1.25", row.spendLine)
        assertEquals("as of 3 min ago", row.ageLabel)
        assertTrue(row.spoken.contains("Nightly digest") && row.spoken.contains("running"))
    }

    @Test
    fun noStepYet_isSaidSo() {
        assertEquals("no step yet", LoopsPresenter.row(LoopEntry(Fx.run("r"), 0), 0).stepLine)
    }

    // ---- spend: real counts only, estimated stays estimated ----

    @Test
    fun missingSpend_isNotRecorded_neverZero() {
        assertEquals("spend: not recorded", SpendLabel.line(null))
    }

    @Test
    fun aRealZero_isShownAsAZero_onDeviceRunsReallyCostNothing() {
        assertEquals("spend: USD 0.00", SpendLabel.line(Spend(0, "USD", estimated = false)))
    }

    @Test
    fun estimatedSpend_isMarkedWithTildeAndTheWord() {
        assertEquals("spend: ~USD 0.42 (estimated)", SpendLabel.line(Spend(42, "USD", estimated = true)))
    }

    @Test
    fun minorUnitExponentFollowsTheCurrency_noFloats() {
        assertEquals("spend: JPY 500", SpendLabel.line(Spend(500, "JPY", false)))
        assertEquals("spend: KWD 1.234", SpendLabel.line(Spend(1234, "KWD", false)))
        assertEquals("spend: INR 12.05", SpendLabel.line(Spend(1205, "INR", false)))
        assertEquals("spend: USD 92233720368547758.07", SpendLabel.line(Spend(Long.MAX_VALUE, "USD", false)))
    }

    @Test
    fun aCurrencyThisJvmDoesNotKnow_isShownInRawMinorUnits_labelledAsSuch_notGuessed() {
        assertEquals("spend: 777 ZZZ (minor units)", SpendLabel.line(Spend(777, "ZZZ", false)))
        assertEquals("spend: ~777 ZZZ (minor units) (estimated)", SpendLabel.line(Spend(777, "ZZZ", true)))
    }
}
