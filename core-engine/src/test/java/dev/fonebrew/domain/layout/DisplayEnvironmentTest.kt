package dev.fonebrew.domain.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayEnvironmentTest {
    private class FakeWindow(var snap: WindowSnapshot) : WindowSource { override fun read() = snap }
    private class FakeNotifier {
        val listeners = ArrayList<() -> Unit>()
        fun fire() = listeners.toList().forEach { it() }
        fun listen(cb: () -> Unit): AutoCloseable { listeners += cb; return AutoCloseable { listeners -= cb } }
    }
    private class FakeDisplays(var list: List<DisplayDescriptor>) : DisplayListSource {
        val n = FakeNotifier()
        override fun read() = list
        override fun listen(onChange: () -> Unit) = n.listen(onChange)
    }
    private class FakeInputs(var presence: InputPresence) : InputPresenceSource {
        val n = FakeNotifier()
        override fun read() = presence
        override fun listen(onChange: () -> Unit) = n.listen(onChange)
    }

    private val phoneDisplay = DisplayDescriptor(0, "Built-in", true, false, 1440, 3216)
    private val monitor = DisplayDescriptor(2, "Odyssey G9", false, true, 5120, 1440)

    private val portrait = WindowSnapshot(1440, 3216, 420, 0, 0)
    private val landscape = WindowSnapshot(3216, 1440, 420, 0, 90)

    private fun env(w: FakeWindow, d: FakeDisplays, i: FakeInputs) = DisplayEnvironment(w, d, i)

    @Test fun `handheld portrait is STANDALONE PHONE on the default display`() {
        val s = env(FakeWindow(portrait), FakeDisplays(listOf(phoneDisplay)), FakeInputs(InputPresence(false, false))).state.value
        assertEquals(ScreenClass.PHONE, s.screenClass)
        assertEquals(DisplayArrangement.STANDALONE, s.arrangement)
        assertTrue(s.isDefaultDisplay); assertFalse(s.isExternalDisplay)
        assertEquals(1440 / 2.625f, s.widthDp, 0.1f)
    }

    @Test fun `dock then undock on an extended display`() {
        val w = FakeWindow(portrait); val d = FakeDisplays(listOf(phoneDisplay)); val i = FakeInputs(InputPresence(false, false))
        val e = env(w, d, i); e.start()
        val seen = ArrayList<DisplayState>()
        // dock: monitor appears, app is moved onto it at density 1.0 (160), keyboard+mouse attach
        w.snap = WindowSnapshot(5120, 1440, 160, 2, 0)
        d.list = listOf(phoneDisplay, monitor); d.n.fire()
        i.presence = InputPresence(true, true); i.n.fire()
        seen += e.state.value
        assertEquals(ScreenClass.ULTRAWIDE, seen[0].screenClass)
        assertEquals(DisplayArrangement.EXTENDED, seen[0].arrangement)
        assertTrue(seen[0].isExternalDisplay); assertTrue(seen[0].isPresentation); assertFalse(seen[0].isDefaultDisplay)
        assertEquals(DisplayFingerprint(2, ScreenClass.ULTRAWIDE, DensityBucket.MDPI), seen[0].fingerprint)
        // undock
        w.snap = portrait
        d.list = listOf(phoneDisplay); d.n.fire()
        i.presence = InputPresence(false, false); i.n.fire()
        val after = e.state.value
        assertEquals(ScreenClass.PHONE, after.screenClass)
        assertEquals(DisplayArrangement.STANDALONE, after.arrangement)
        assertEquals(DisplayFingerprint(0, ScreenClass.PHONE, DensityBucket.XXHDPI), after.fingerprint)
        assertTrue("docked and undocked fingerprints differ", seen[0].fingerprint.storageKey != after.fingerprint.storageKey)
    }

    @Test fun `MIRRORED is the default display made desktop-sized with no second display listed`() {
        val w = FakeWindow(WindowSnapshot(5120, 1440, 160, 0, 0))
        val s = env(w, FakeDisplays(listOf(phoneDisplay.copy(widthPx = 5120, heightPx = 1440))), FakeInputs(InputPresence(true, true))).state.value
        assertEquals(DisplayArrangement.MIRRORED, s.arrangement)
        assertEquals(ScreenClass.ULTRAWIDE, s.screenClass) // desktop context via keyboard + pointer
        assertFalse(s.isExternalDisplay)
    }

    @Test fun `rotation changes size and class inputs but keeps the display`() {
        val w = FakeWindow(portrait); val e = env(w, FakeDisplays(listOf(phoneDisplay)), FakeInputs(InputPresence(false, false)))
        val p = e.state.value
        w.snap = landscape; e.refresh()
        val l = e.state.value
        assertEquals(90, l.window.rotationDegrees)
        assertEquals(p.widthDp, l.heightDp, 0.01f)
        assertEquals(ScreenClass.PHONE, l.screenClass) // 1225x549 dp: smallest < 600, not docked
        assertEquals(p.fingerprint, l.fingerprint)
    }

    @Test fun `hardware keyboard attach alone does not change a phone's class`() {
        val i = FakeInputs(InputPresence(false, false))
        val e = env(FakeWindow(portrait), FakeDisplays(listOf(phoneDisplay)), i); e.start()
        i.presence = InputPresence(true, false); i.n.fire()
        assertTrue(e.state.value.input.hasHardwareKeyboard)
        assertEquals(ScreenClass.PHONE, e.state.value.screenClass)
    }

    @Test fun `listeners are removed on stop and start is idempotent`() {
        val d = FakeDisplays(listOf(phoneDisplay)); val i = FakeInputs(InputPresence(false, false))
        val e = env(FakeWindow(portrait), d, i)
        e.start(); e.start()
        assertEquals(1, d.n.listeners.size); assertEquals(1, i.n.listeners.size)
        e.stop()
        assertEquals(0, d.n.listeners.size); assertEquals(0, i.n.listeners.size)
        d.list = listOf(phoneDisplay, monitor); d.n.fire()
        assertEquals(1, e.state.value.displays.size) // no longer updating
    }

    @Test fun `a window on a display the system does not list falls back to the id for default-ness`() {
        val e = env(FakeWindow(portrait.copy(displayId = 5)), FakeDisplays(listOf(phoneDisplay)), FakeInputs(InputPresence(false, false)))
        assertTrue(e.state.value.isExternalDisplay)
        assertEquals(null, e.state.value.appDisplay)
    }

    // ---- launch-on-display planning ------------------------------------------------------

    private fun stateWith(vararg d: DisplayDescriptor, on: Int = 0) =
        DisplayState(WindowSnapshot(1440, 3216, 420, on, 0), d.toList(), InputPresence(false, false))

    @Test fun `plan picks the presentation display and never the one the app is on`() {
        val p = DisplayLaunchPlanner.plan(stateWith(phoneDisplay, monitor), true) as LaunchPlan.Launch
        assertEquals(2, p.targetDisplayId)
        assertTrue(p.needsNewTaskFlags)
        assertTrue(p.note.contains("unverified"))
        val none = DisplayLaunchPlanner.plan(stateWith(phoneDisplay, monitor, on = 2).copy(window = WindowSnapshot(5120, 1440, 160, 2, 0)), true) as LaunchPlan.Launch
        assertEquals(0, none.targetDisplayId) // app is on the monitor; the only other is the handset
    }

    @Test fun `plan prefers presentation then size then lowest id`() {
        val bigger = DisplayDescriptor(7, "TV", false, false, 3840, 2160)
        val pres = DisplayDescriptor(3, "Pres", false, true, 800, 600)
        assertEquals(3, (DisplayLaunchPlanner.plan(stateWith(phoneDisplay, bigger, pres), true) as LaunchPlan.Launch).targetDisplayId)
        assertEquals(7, (DisplayLaunchPlanner.plan(stateWith(phoneDisplay, bigger, pres.copy(isPresentation = false)), true) as LaunchPlan.Launch).targetDisplayId)
        val tie = DisplayDescriptor(9, "Tie", false, false, 3840, 2160)
        assertEquals(7, (DisplayLaunchPlanner.plan(stateWith(phoneDisplay, tie, bigger), true) as LaunchPlan.Launch).targetDisplayId)
    }

    @Test fun `plan refuses with no second display or an explicit no`() {
        assertTrue(DisplayLaunchPlanner.plan(stateWith(phoneDisplay), true) is LaunchPlan.None)
        assertTrue(DisplayLaunchPlanner.plan(stateWith(phoneDisplay, monitor), false) is LaunchPlan.None)
        val unknown = DisplayLaunchPlanner.plan(stateWith(phoneDisplay, monitor), null) as LaunchPlan.Launch
        assertTrue(unknown.note.contains("unknown"))
    }

    @Test fun `zero-sized displays are ignored`() {
        val ghost = DisplayDescriptor(4, "Ghost", false, true, 0, 0)
        assertTrue(DisplayLaunchPlanner.plan(stateWith(phoneDisplay, ghost), true) is LaunchPlan.None)
    }
}
