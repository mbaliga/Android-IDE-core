package dev.fonebrew.ui.regular

import dev.fonebrew.ui.regular.RegularSearchShortcut.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegularSearchShortcutTest {

    private fun decide(
        down: Boolean = true,
        ctrl: Boolean = true,
        k: Boolean = true,
        repeat: Int = 0,
        focused: Boolean = false,
    ) = RegularSearchShortcut.decide(down, ctrl, k, repeat, focused)

    @Test fun `ctrl k toggles search`() = assertEquals(Action.ToggleSearch, decide())

    @Test fun `key up, no ctrl, or another key do nothing`() {
        assertEquals(Action.None, decide(down = false))
        assertEquals(Action.None, decide(ctrl = false))
        assertEquals(Action.None, decide(k = false))
    }

    @Test fun `key repeat is ignored`() {
        assertEquals(Action.None, decide(repeat = 1))
        assertEquals(Action.None, decide(repeat = 7))
    }

    @Test fun `a focused text field keeps the keystroke`() {
        assertEquals(Action.None, decide(focused = true))
    }

    @Test fun `only a toggle is consumed`() {
        assertTrue(RegularSearchShortcut.consumes(Action.ToggleSearch))
        assertFalse(RegularSearchShortcut.consumes(Action.None))
    }
}
