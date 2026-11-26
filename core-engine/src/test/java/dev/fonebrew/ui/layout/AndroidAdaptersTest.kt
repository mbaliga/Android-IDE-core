package dev.fonebrew.ui.layout

import android.view.InputDevice
import android.view.KeyEvent
import android.view.Surface
import dev.fonebrew.domain.layout.KeyChord
import dev.fonebrew.domain.layout.KeyNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only the int-level rules; nothing here touches a real display, input device or key event. */
class AndroidAdaptersTest {
    @Test fun `key codes map to canonical names the chord parser accepts`() {
        val codes = listOf(
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F12,
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_SLASH,
            KeyEvent.KEYCODE_BACKSLASH, KeyEvent.KEYCODE_SEMICOLON, KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_MINUS,
            KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_GRAVE, KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_RIGHT_BRACKET,
        )
        for (c in codes) {
            val n = AndroidKeyMapping.keyName(c)
            assertTrue("code $c", n != null && KeyNames.canonical(n) == n)
        }
        assertEquals("P", AndroidKeyMapping.keyName(KeyEvent.KEYCODE_P))
        assertEquals("F5", AndroidKeyMapping.keyName(KeyEvent.KEYCODE_F5))
        assertNull(AndroidKeyMapping.keyName(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(AndroidKeyMapping.keyName(KeyEvent.KEYCODE_CTRL_LEFT))
    }

    @Test fun `meta state maps to modifiers`() {
        val c = AndroidKeyMapping.toChord(KeyEvent.KEYCODE_P, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)!!
        assertEquals("Ctrl+Shift+P", c.print())
        assertEquals(KeyChord.parse("Alt+PageDown"), AndroidKeyMapping.toChord(KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.META_ALT_ON))
        assertEquals("Meta+K", AndroidKeyMapping.toChord(KeyEvent.KEYCODE_K, KeyEvent.META_META_ON)!!.print())
        assertNull(AndroidKeyMapping.toChord(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.META_CTRL_ON))
    }

    @Test fun `input classifier ignores virtual devices and the touchscreen`() {
        val touch = InputDeviceInfo(InputDevice.SOURCE_TOUCHSCREEN, InputDevice.KEYBOARD_TYPE_NONE, false)
        val virtualKb = InputDeviceInfo(InputDevice.SOURCE_KEYBOARD, InputDevice.KEYBOARD_TYPE_ALPHABETIC, true)
        val mediaKeys = InputDeviceInfo(InputDevice.SOURCE_KEYBOARD, InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC, false)
        val kb = InputDeviceInfo(InputDevice.SOURCE_KEYBOARD, InputDevice.KEYBOARD_TYPE_ALPHABETIC, false)
        val mouse = InputDeviceInfo(InputDevice.SOURCE_MOUSE, InputDevice.KEYBOARD_TYPE_NONE, false)
        val pad = InputDeviceInfo(InputDevice.SOURCE_TOUCHPAD, InputDevice.KEYBOARD_TYPE_NONE, false)

        val none = InputClassifier.classify(listOf(touch, virtualKb, mediaKeys))
        assertFalse(none.hasHardwareKeyboard); assertFalse(none.hasPointer)
        val kbOnly = InputClassifier.classify(listOf(touch, kb))
        assertTrue(kbOnly.hasHardwareKeyboard); assertFalse(kbOnly.hasPointer)
        assertTrue(InputClassifier.classify(listOf(mouse)).hasPointer)
        assertTrue(InputClassifier.classify(listOf(pad)).hasPointer)
        assertFalse(InputClassifier.classify(listOf(mouse.copy(isVirtual = true))).hasPointer)
        val both = InputClassifier.classify(listOf(kb, mouse))
        assertTrue(both.hasHardwareKeyboard && both.hasPointer)
        assertFalse(InputClassifier.classify(emptyList()).hasPointer)
    }

    @Test fun `rotation constants map to degrees`() {
        assertEquals(0, ActivityWindowSource.rotationToDegrees(Surface.ROTATION_0))
        assertEquals(90, ActivityWindowSource.rotationToDegrees(Surface.ROTATION_90))
        assertEquals(180, ActivityWindowSource.rotationToDegrees(Surface.ROTATION_180))
        assertEquals(270, ActivityWindowSource.rotationToDegrees(Surface.ROTATION_270))
        assertEquals(0, ActivityWindowSource.rotationToDegrees(42))
    }
}
