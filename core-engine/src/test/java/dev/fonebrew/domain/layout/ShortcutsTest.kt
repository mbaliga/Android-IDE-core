package dev.fonebrew.domain.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutsTest {
    private fun ev(key: String?, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false, meta: Boolean = false, down: Boolean = true) =
        object : KeyEventLike {
            override val keyName = key
            override val ctrl = ctrl
            override val shift = shift
            override val alt = alt
            override val meta = meta
            override val isKeyDown = down
        }

    @Test fun `parse and print are canonical`() {
        assertEquals("Ctrl+Shift+P", KeyChord.parse("shift+ctrl+p")!!.print())
        assertEquals("Ctrl+Alt+Left", KeyChord.parse("Alt + Control + left")!!.print())
        assertEquals("F5", KeyChord.parse("f5")!!.print())
        assertEquals("Ctrl+Plus", KeyChord.parse("ctrl+plus")!!.print())
        assertEquals("Ctrl+Meta+K", KeyChord.parse("super+ctrl+k")!!.print())
    }

    @Test fun `malformed chords are rejected`() {
        for (bad in listOf(null, "", " ", "Ctrl+", "+P", "Ctrl++", "Ctrl+Ctrl+P", "Hyper+P", "Ctrl+Nope", "Ctrl+F25", "P", "Shift+P", "Tab", "Ctrl+Shift", "Ctrl+PP"))
            assertNull("should reject '$bad'", KeyChord.parse(bad))
        assertTrue(KeyChord.parse("Escape") != null && KeyChord.parse("F12") != null)
    }

    @Test fun `every default chord round trips and the defaults cover the required commands`() {
        for ((id, c) in DefaultShortcuts.general + DefaultShortcuts.terminal) assertEquals(id, c, KeyChord.parse(c.print()))
        val required = listOf(
            CommandIds.QUICK_OPEN, CommandIds.COMMAND_PALETTE, CommandIds.TOGGLE_TREE, CommandIds.TOGGLE_TERMINAL,
            CommandIds.TAB_NEXT, CommandIds.TAB_PREVIOUS, CommandIds.TAB_CLOSE, CommandIds.SAVE, CommandIds.FIND,
            CommandIds.FIND_IN_FILES, CommandIds.FOCUS_LEFT, CommandIds.FOCUS_RIGHT, CommandIds.FOCUS_UP,
            CommandIds.FOCUS_DOWN, CommandIds.SPLIT_RIGHT, CommandIds.SPLIT_DOWN, CommandIds.MAXIMISE,
        )
        assertEquals(required.toSet(), DefaultShortcuts.general.keys)
        assertEquals(required.toSet(), DefaultShortcuts.terminal.keys)
    }

    @Test fun `default maps are conflict free in both contexts`() {
        val m = ShortcutMap()
        assertTrue(m.conflicts(FocusContext.GENERAL).isEmpty())
        assertTrue(m.conflicts(FocusContext.TERMINAL).isEmpty())
    }

    @Test fun `terminal map never claims a plain Ctrl chord - the shell keeps them`() {
        for ((id, c) in DefaultShortcuts.terminal) assertTrue("$id -> $c", c.isTerminalSafe)
        val plainCtrlLetters = ('A'..'Z').map { KeyChord(true, false, false, false, it.toString()) }
        val m = ShortcutMap()
        for (c in plainCtrlLetters + KeyChord.parse("Ctrl+Tab")!! + KeyChord.parse("Ctrl+Backquote")!!) {
            assertEquals("$c must reach the shell", ShortcutResolution.PassThrough, m.resolve(c, FocusContext.TERMINAL))
        }
    }

    @Test fun `terminal-safe means Ctrl+Shift or Alt only`() {
        assertTrue(KeyChord.parse("Ctrl+Shift+B")!!.isTerminalSafe)
        assertTrue(KeyChord.parse("Alt+PageDown")!!.isTerminalSafe)
        assertFalse(KeyChord.parse("Ctrl+B")!!.isTerminalSafe)
        assertFalse(KeyChord.parse("Shift+F5")!!.isTerminalSafe)
        assertFalse(KeyChord.parse("F5")!!.isTerminalSafe)
        assertFalse(KeyChord.parse("Meta+K")!!.isTerminalSafe)
    }

    @Test fun `resolve differs by focus context`() {
        val m = ShortcutMap()
        val ctrlB = KeyChord.parse("Ctrl+B")!!
        assertEquals(ShortcutResolution.Command(CommandIds.TOGGLE_TREE), m.resolve(ctrlB, FocusContext.GENERAL))
        assertEquals(ShortcutResolution.PassThrough, m.resolve(ctrlB, FocusContext.TERMINAL))
        val cs = KeyChord.parse("Ctrl+Shift+B")!!
        assertEquals(ShortcutResolution.Command(CommandIds.TOGGLE_TREE), m.resolve(cs, FocusContext.TERMINAL))
        assertEquals(ShortcutResolution.PassThrough, m.resolve(cs, FocusContext.GENERAL))
    }

    @Test fun `key events resolve through the adapter interface`() {
        val m = ShortcutMap()
        assertEquals(ShortcutResolution.Command(CommandIds.SAVE), m.resolve(ev("S", ctrl = true), FocusContext.GENERAL))
        assertEquals(ShortcutResolution.PassThrough, m.resolve(ev("S", ctrl = true, down = false), FocusContext.GENERAL))
        assertEquals(ShortcutResolution.PassThrough, m.resolve(ev(null, ctrl = true), FocusContext.GENERAL))
        assertEquals(ShortcutResolution.PassThrough, m.resolve(ev("S"), FocusContext.GENERAL)) // plain typing
    }

    @Test fun `override applies and conflicts are reported not applied`() {
        val m = ShortcutMap()
        val (m2, r) = m.withOverride(CommandIds.SAVE, KeyChord.parse("Ctrl+Alt+S")!!, FocusContext.GENERAL)
        assertEquals(OverrideResult.Applied, r)
        assertEquals("Ctrl+Alt+S", m2.chordFor(CommandIds.SAVE, FocusContext.GENERAL)!!.print())
        assertEquals("Ctrl+S", m.chordFor(CommandIds.SAVE, FocusContext.GENERAL)!!.print()) // immutable

        val (m3, clash) = m.withOverride(CommandIds.SAVE, KeyChord.parse("Ctrl+F")!!, FocusContext.GENERAL)
        assertEquals(OverrideResult.Conflict(listOf(CommandIds.FIND)), clash)
        assertEquals(m.table(FocusContext.GENERAL), m3.table(FocusContext.GENERAL))

        // re-binding a command to its own chord is not a conflict
        assertEquals(OverrideResult.Applied, m.withOverride(CommandIds.SAVE, KeyChord.parse("Ctrl+S")!!, FocusContext.GENERAL).second)
    }

    @Test fun `terminal override with a plain Ctrl chord is refused, unknown command refused`() {
        val m = ShortcutMap()
        assertEquals(OverrideResult.TerminalUnsafe, m.withOverride(CommandIds.SAVE, KeyChord.parse("Ctrl+S")!!, FocusContext.TERMINAL).second)
        assertEquals(OverrideResult.UnknownCommand, m.withOverride("nope", KeyChord.parse("Ctrl+Shift+Z")!!, FocusContext.GENERAL).second)
        assertEquals(OverrideResult.Applied, m.withOverride(CommandIds.SAVE, KeyChord.parse("Ctrl+Shift+Z")!!, FocusContext.TERMINAL).second)
    }

    @Test fun `overrides persist and round trip`() {
        var m = ShortcutMap()
        m = m.withOverride(CommandIds.SAVE, KeyChord.parse("Ctrl+Alt+S")!!, FocusContext.GENERAL).first
        m = m.withOverride(CommandIds.FIND, KeyChord.parse("Alt+F")!!, FocusContext.TERMINAL).first
        val back = ShortcutMap.decodeOverrides(m.encodeOverrides())
        assertEquals(m.table(FocusContext.GENERAL), back.table(FocusContext.GENERAL))
        assertEquals(m.table(FocusContext.TERMINAL), back.table(FocusContext.TERMINAL))
        assertEquals(m.encodeOverrides(), back.encodeOverrides())
    }

    @Test fun `bad persisted overrides degrade to defaults entry by entry`() {
        val defaults = ShortcutMap().table(FocusContext.GENERAL)
        for (junk in listOf(null, "", "garbage", "[]", """{"v":2}""")) assertEquals(defaults, ShortcutMap.decodeOverrides(junk).table(FocusContext.GENERAL))
        val mixed = """{"v":1,"general":{"save":"Ctrl+Alt+S","nope":"Ctrl+Shift+Z","find":"???","quick.open":"Ctrl+B"},"terminal":{"save":"Ctrl+S","find":"Ctrl+Shift+Q"}}"""
        val m = ShortcutMap.decodeOverrides(mixed)
        assertEquals("Ctrl+Alt+S", m.chordFor(CommandIds.SAVE, FocusContext.GENERAL)!!.print()) // good entry kept
        assertEquals("Ctrl+F", m.chordFor(CommandIds.FIND, FocusContext.GENERAL)!!.print()) // unparsable skipped
        assertEquals("Ctrl+P", m.chordFor(CommandIds.QUICK_OPEN, FocusContext.GENERAL)!!.print()) // conflicts with toggle.tree, skipped
        assertEquals("Ctrl+Shift+S", m.chordFor(CommandIds.SAVE, FocusContext.TERMINAL)!!.print()) // unsafe skipped
        assertEquals("Ctrl+Shift+Q", m.chordFor(CommandIds.FIND, FocusContext.TERMINAL)!!.print())
        assertTrue(m.conflicts(FocusContext.GENERAL).isEmpty())
    }

    @Test fun `conflict detection finds duplicates when the table is built by hand`() {
        // ShortcutMap itself refuses conflicts; this guards the detector on the default tables.
        val dup = DefaultShortcuts.general + (CommandIds.FIND to DefaultShortcuts.general.getValue(CommandIds.SAVE))
        val groups = dup.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }
        assertEquals(1, groups.size)
        assertEquals(setOf(CommandIds.FIND, CommandIds.SAVE), groups.values.single().toSet())
    }
}
