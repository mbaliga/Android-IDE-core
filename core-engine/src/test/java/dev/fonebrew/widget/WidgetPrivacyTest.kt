package dev.fonebrew.widget

import dev.fonebrew.data.WidgetPrivacyStore
import dev.fonebrew.data.entity.TaskEntity
import dev.fonebrew.domain.tasks.TaskSource
import dev.fonebrew.domain.tasks.TaskState
import dev.fonebrew.watchbridge.LoopRunSnapshot
import dev.fonebrew.watchbridge.Spend
import dev.fonebrew.watchbridge.StateGlyphKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The home screen is public: the user's own words stay off it until they turn them on, per widget. */
class WidgetPrivacyTest {

    private val now = 1_800_000_000_000L

    private fun task(id: String, title: String, source: TaskSource = TaskSource.MANUAL) = TaskEntity(
        id = id, title = title, state = TaskState.TODO, orderKey = 1.0, dueAt = now + 1000, source = source,
        createdAt = 1L, updatedAt = 1L,
    )

    private fun run(name: String = "Quarterly layoffs plan", step: String? = "Draft the memo") =
        LoopRunSnapshot(runId = "r", loopName = name, stateGlyphKey = StateGlyphKeys.COMPLETE, stepName = step, spend = Spend(42, "USD", estimated = false))

    @Test
    fun defaultIsOff_forBothSwitches() {
        assertFalse(WidgetPrivacy.OFF.showTaskTitles)
        assertFalse(WidgetPrivacy.OFF.showLoopNames)
        assertEquals(WidgetPrivacy.OFF, WidgetPrivacy())
    }

    @Test
    fun nextUp_withDefaultPrivacy_neverCarriesAUserTitle() {
        val m = NextUp.build(listOf(task("a", "Call the lawyer about the settlement")), now)
        val row = m.rows.single()
        assertNull(row.title)
        assertEquals(Withheld.PRIVACY, row.withheldBy)
        assertTrue(row.withheld)
        // The row still says something true: its id, state and due bucket are not private content.
        assertEquals(DueKind.SOON, row.due)
        assertEquals(1, m.openCount)
    }

    @Test
    fun nextUp_titlesAppearOnlyWhenTheSwitchIsOn() {
        val on = NextUp.build(listOf(task("a", "Renew the domain")), now, privacy = WidgetPrivacy(showTaskTitles = true))
        assertEquals("Renew the domain", on.rows.single().title)
        assertNull(on.rows.single().withheldBy)
        // The loop-name switch does not unlock task titles.
        val other = NextUp.build(listOf(task("a", "Renew the domain")), now, privacy = WidgetPrivacy(showLoopNames = true))
        assertNull(other.rows.single().title)
    }

    @Test
    fun aChatDerivedTitle_staysHiddenEvenWithTheSwitchOn_andSaysWhy() {
        val m = NextUp.build(listOf(task("c", "private chat words", TaskSource.CHAT)), now, privacy = WidgetPrivacy.ALL_SHOWN)
        assertNull(m.rows.single().title)
        assertEquals(Withheld.CHAT, m.rows.single().withheldBy)
    }

    @Test
    fun loopRuns_withDefaultPrivacy_carryNoLoopOrStepName_butKeepStateAndSpend() {
        val row = LoopRuns.build(listOf(run())).single()
        assertNull(row.loopName)
        assertNull(row.step)
        assertTrue(row.namesHidden)
        assertEquals(LoopRunState.COMPLETE, row.state)
        assertNotNull(row.spend)
    }

    @Test
    fun loopRuns_namesAppearOnlyWhenTheirSwitchIsOn() {
        val row = LoopRuns.build(listOf(run()), privacy = WidgetPrivacy(showLoopNames = true)).single()
        assertEquals("Quarterly layoffs plan", row.loopName)
        assertEquals("Draft the memo", row.step)
        assertFalse(row.namesHidden)
        assertNull(LoopRuns.build(listOf(run()), privacy = WidgetPrivacy(showTaskTitles = true)).single().loopName)
    }

    private class Mem(val map: MutableMap<String, Boolean> = HashMap()) {
        fun store() = WidgetPrivacyStore(read = { map[it] }, write = { k, v -> map[k] = v })
    }

    @Test
    fun store_startsOff_roundTrips_andPublishesChanges() {
        val mem = Mem()
        val store = mem.store()
        assertEquals(WidgetPrivacy.OFF, store.state.value)
        store.setShowTaskTitles(true)
        assertEquals(WidgetPrivacy(showTaskTitles = true), store.state.value)
        store.setShowLoopNames(true)
        assertEquals(WidgetPrivacy.ALL_SHOWN, mem.store().state.value)
        store.setShowTaskTitles(false)
        assertEquals(WidgetPrivacy(showLoopNames = true), mem.store().state.value)
    }

    @Test
    fun store_unreadableStorage_resolvesToOff() {
        val store = WidgetPrivacyStore(read = { throw IllegalStateException("prefs gone") }, write = { _, _ -> })
        assertEquals(WidgetPrivacy.OFF, store.state.value)
    }
}
