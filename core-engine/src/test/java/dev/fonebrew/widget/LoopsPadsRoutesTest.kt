package dev.fonebrew.widget

import dev.fonebrew.data.watch.WristSnapshots
import dev.fonebrew.watchbridge.LoopRunSnapshot
import dev.fonebrew.watchbridge.Spend
import dev.fonebrew.watchbridge.StateGlyphKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopRunsTest {

    private fun snap(key: String, name: String = "Nightly triage", step: String? = "Draft", spend: Spend? = null, id: String = key) =
        LoopRunSnapshot(runId = id, loopName = name, stateGlyphKey = key, stepName = step, spend = spend)

    @Test
    fun everyKnownStopReason_mapsToItsOwnState_andAnUnknownKeyKeepsItsWord() {
        val rows = LoopRuns.build(
            listOf(
                snap(StateGlyphKeys.RUNNING), snap(StateGlyphKeys.COMPLETE), snap(StateGlyphKeys.STOPPED),
                snap(StateGlyphKeys.FAILED), snap(WristSnapshots.ENDED_GLYPH_KEY),
            ),
        )
        assertEquals(
            listOf(LoopRunState.RUNNING, LoopRunState.COMPLETE, LoopRunState.STOPPED, LoopRunState.FAILED, LoopRunState.OTHER),
            rows.map { it.state },
        )
        assertEquals(WristSnapshots.ENDED_GLYPH_KEY, rows.last().stateKey)
    }

    @Test
    fun anUnsavedLoop_hasNoName_soTheWidgetSaysSoInItsOwnWords() {
        assertNull(LoopRuns.build(listOf(snap(StateGlyphKeys.COMPLETE, name = WristSnapshots.UNSAVED_LOOP_NAME))).single().loopName)
        assertEquals("Nightly triage", LoopRuns.build(listOf(snap(StateGlyphKeys.COMPLETE))).single().loopName)
    }

    @Test
    fun rowsAreCapped_andStepNamesAreOneLine() {
        val many = (1..9).map { snap(StateGlyphKeys.COMPLETE, id = "r$it", step = "Step\n$it") }
        val rows = LoopRuns.build(many)
        assertEquals(LoopRuns.MAX_ROWS, rows.size)
        assertEquals("Step 1", rows.first().step)
        assertNull(LoopRuns.build(listOf(snap(StateGlyphKeys.RUNNING, step = null))).single().step)
    }

    @Test
    fun spend_isTheLedgersMinorUnitsAsADecimal_withEstimatesFlagged() {
        assertEquals(SpendText("USD 0.42", estimated = false), LoopRuns.spend(Spend(42, "USD", estimated = false)))
        assertEquals(SpendText("INR 12.40", estimated = true), LoopRuns.spend(Spend(1240, "INR", estimated = true)))
        assertEquals("a zero-decimal currency", SpendText("JPY 300", estimated = false), LoopRuns.spend(Spend(300, "JPY", estimated = false)))
        assertEquals("a real zero stays a zero", "USD 0.00", LoopRuns.spend(Spend(0, "USD", estimated = false)).amount)
        val unknown = LoopRuns.spend(Spend(77, "ZZZ", estimated = false))
        assertTrue(unknown.minorUnits)
        assertEquals("77 ZZZ", unknown.amount)
    }

    @Test
    fun aRunWithNoLedgerRows_hasNoSpend_notAZero() {
        assertNull(LoopRuns.build(listOf(snap(StateGlyphKeys.RUNNING, spend = null))).single().spend)
    }
}

class QuickStartAndSizesTest {

    @Test
    fun padsPerSize_matchTheLayouts_oneThreeFourSix() {
        assertEquals(listOf(PadAction.NEW_CHAT), QuickStart.pads(WidgetSize.SMALL))
        assertEquals(3, QuickStart.pads(WidgetSize.WIDE).size)
        assertEquals(4, QuickStart.pads(WidgetSize.MEDIUM).size)
        assertEquals(PadAction.entries.toList(), QuickStart.pads(WidgetSize.LARGE))
        for (size in WidgetSize.entries) {
            val pads = QuickStart.pads(size)
            assertEquals("New chat is always the first (primary) pad", PadAction.NEW_CHAT, pads.first())
            assertEquals("no pad twice", pads.size, pads.toSet().size)
        }
    }

    @Test
    fun everyWidget_hasAtLeastThreeLayouts_fromTwoByOneUp_inGrowingSizes() {
        assertTrue("at least five widgets", WidgetKind.entries.size >= 5)
        for (kind in WidgetKind.entries) {
            val sizes = kind.sizes
            assertTrue("$kind has >= 3 layouts", sizes.size >= 3)
            assertEquals("$kind: one layout per size class", sizes.size, sizes.map { it.size }.toSet().size)
            assertEquals("$kind: distinct breakpoints", sizes.size, sizes.map { it.widthDp to it.heightDp }.toSet().size)
            val smallest = sizes.minBy { it.widthDp * it.heightDp }
            assertTrue("$kind: the smallest layout fits 2x1 (110x40 dp)", smallest.widthDp <= 110f && smallest.heightDp <= 40f)
            val areas = sizes.map { it.widthDp * it.heightDp }
            assertEquals("$kind: listed small to large", areas.sorted(), areas)
        }
    }

    @Test
    fun padBreakpoints_giveEveryPadA48dpTarget_andRoomForItsLabel() {
        // Chrome around the pads: 4 dp widget padding each side, 4 dp gap on each side of every pad.
        val padding = 8f
        val gap = 8f
        // Rows x columns of each pads layout (widget_pads_wide / _medium / _large.xml).
        val grid = mapOf(WidgetSize.WIDE to (1 to 3), WidgetSize.MEDIUM to (2 to 2), WidgetSize.LARGE to (2 to 3))
        for (br in WidgetSizes.PADS.filter { it.size != WidgetSize.SMALL }) {
            val (rows, cols) = grid.getValue(br.size)
            assertEquals("${br.size}: one pad per cell", rows * cols, QuickStart.pads(br.size).size)
            val padHeight = (br.heightDp - padding) / rows - gap
            val padWidth = (br.widthDp - padding) / cols - gap
            assertTrue("${br.size}: pads are >= 48 dp tall at the breakpoint (got $padHeight)", padHeight >= 48f)
            // "New chat" at 12-14 sp is about 53-62 dp wide, plus 4 dp padding each side.
            assertTrue("${br.size}: pads are wide enough for a label (got $padWidth)", padWidth >= 64f)
        }
    }

    @Test
    fun templates_areFromTheStrategysSet() {
        val allowed = setOf("stat", "card", "list", "next", "progress", "choice", "pads", "media", "image")
        assertTrue(WidgetKind.entries.all { it.template in allowed })
    }

    @Test
    fun oneLine_flattensAndCaps() {
        assertEquals("a b c", oneLine("  a\n\tb   c ", 10))
        assertEquals("abcd…", oneLine("abcdefghij", 5))
        assertEquals("", oneLine("\n\n", 5))
    }
}

class WidgetRoutesTest {

    @Test
    fun everyDestination_roundTripsThroughItsExtra() {
        for (d in WidgetDestination.entries) assertEquals(WidgetRoute(d), WidgetRoutes.parse(d.name, null))
    }

    @Test
    fun unknownOrMissingDestinations_areIgnored() {
        assertNull(WidgetRoutes.parse(null, null))
        assertNull(WidgetRoutes.parse("", null))
        assertNull(WidgetRoutes.parse("DELETE_EVERYTHING", null))
        assertNull(WidgetRoutes.parse("tasks", null))
    }

    @Test
    fun aTaskId_isKeptOnlyForTasks_andOnlyWhenWellFormed() {
        val uuid = "3f2b6c1e-0d4a-4b8e-9a51-2c7d9e0f1a23"
        assertEquals(WidgetRoute(WidgetDestination.TASKS, uuid), WidgetRoutes.parse("TASKS", uuid))
        assertEquals(WidgetRoute(WidgetDestination.TASKS), WidgetRoutes.parse("TASKS", "../../etc"))
        assertEquals(WidgetRoute(WidgetDestination.TASKS), WidgetRoutes.parse("TASKS", "x".repeat(65)))
        assertEquals(WidgetRoute(WidgetDestination.LOOPS), WidgetRoutes.parse("LOOPS", uuid))
    }

    @Test
    fun requestCodes_areUniquePerWidgetAndSlot() {
        val slots = listOf(WidgetRoutes.SLOT_MAIN, WidgetRoutes.SLOT_SMALL, WidgetRoutes.SLOT_ACTION) + PadAction.entries.map(WidgetRoutes::padSlot)
        assertEquals("slots don't collide", slots.size, slots.toSet().size)
        assertTrue(slots.all { it in 0 until WidgetRoutes.SLOTS_PER_WIDGET })
        val codes = (1..50).flatMap { id -> slots.map { WidgetRoutes.requestCode(id, it) } }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aSlotOutOfRange_isRejected() {
        WidgetRoutes.requestCode(1, WidgetRoutes.SLOTS_PER_WIDGET)
    }

    @Test
    fun theInbox_handsARouteOverOnce() {
        val inbox = WidgetRouteInbox()
        assertNull(inbox.consume())
        inbox.offer(WidgetRoute(WidgetDestination.MODELS))
        assertEquals(WidgetRoute(WidgetDestination.MODELS), inbox.pending.value)
        assertEquals(WidgetRoute(WidgetDestination.MODELS), inbox.consume())
        assertNull(inbox.pending.value)
        assertFalse(inbox.consume() != null)
    }
}
