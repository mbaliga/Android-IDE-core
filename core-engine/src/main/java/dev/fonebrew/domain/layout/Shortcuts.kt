package dev.fonebrew.domain.layout

import org.json.JSONObject

/** Android-free view of a key event; `ui/layout/AndroidKeyMapping` builds [KeyChord]s from real ones. */
interface KeyEventLike {
    /** Canonical key name (see [KeyNames]) or null when the key is not one we name. */
    val keyName: String?
    val ctrl: Boolean
    val shift: Boolean
    val alt: Boolean
    val meta: Boolean
    val isKeyDown: Boolean
}

/** Key names accepted in a chord: A-Z, 0-9, F1-F24 and the named keys below. */
object KeyNames {
    val named: Set<String> = setOf(
        "Tab", "Enter", "Escape", "Space", "Backspace", "Delete", "Left", "Right", "Up", "Down",
        "Home", "End", "PageUp", "PageDown", "Comma", "Period", "Slash", "Backslash", "Semicolon",
        "Quote", "Minus", "Equals", "Plus", "Backquote", "LeftBracket", "RightBracket",
    )

    fun canonical(raw: String): String? {
        val t = raw.trim()
        if (t.length == 1 && t[0].isLetterOrDigit() && t[0].code < 128) return t.uppercase()
        if (Regex("[Ff](\\d{1,2})").matches(t)) {
            val n = t.substring(1).toInt()
            if (n in 1..24) return "F$n"
        }
        return named.firstOrNull { it.equals(t, ignoreCase = true) }
    }

    fun isFunctionKey(k: String) = k.length >= 2 && k[0] == 'F' && k[1].isDigit()
}

/** One key combination. Printed in the fixed order Ctrl+Alt+Shift+Meta+Key, so equal chords print equal. */
data class KeyChord(val ctrl: Boolean, val alt: Boolean, val shift: Boolean, val meta: Boolean, val key: String) {
    /**
     * The terminal-focus policy: while a terminal has focus only Ctrl+Shift or Alt chords may be
     * taken by the app; everything else (every plain Ctrl chord above all) belongs to the shell.
     */
    val isTerminalSafe: Boolean get() = alt || (ctrl && shift)

    fun print(): String = buildString {
        if (ctrl) append("Ctrl+"); if (alt) append("Alt+"); if (shift) append("Shift+"); if (meta) append("Meta+")
        append(key)
    }

    override fun toString(): String = print()

    companion object {
        /** Parses "Ctrl+Shift+P"; case-insensitive, modifier order free, null when malformed. */
        fun parse(text: String?): KeyChord? {
            if (text.isNullOrBlank()) return null
            // A trailing "+" would be ambiguous; the plus key is spelled "Plus".
            val parts = text.split('+').map { it.trim() }
            if (parts.any { it.isEmpty() }) return null
            val key = KeyNames.canonical(parts.last()) ?: return null
            var ctrl = false; var alt = false; var shift = false; var meta = false
            for (m in parts.dropLast(1)) {
                when (m.lowercase()) {
                    "ctrl", "control" -> { if (ctrl) return null; ctrl = true }
                    "alt", "option" -> { if (alt) return null; alt = true }
                    "shift" -> { if (shift) return null; shift = true }
                    "meta", "super", "cmd" -> { if (meta) return null; meta = true }
                    else -> return null
                }
            }
            // A bare letter/digit/arrow is typing, not a shortcut; only F-keys and Escape may stand alone.
            if (!ctrl && !alt && !meta && !(KeyNames.isFunctionKey(key) || key == "Escape")) return null
            return KeyChord(ctrl, alt, shift, meta, key)
        }

        fun of(e: KeyEventLike): KeyChord? {
            if (!e.isKeyDown) return null
            val k = e.keyName ?: return null
            return KeyChord(e.ctrl, e.alt, e.shift, e.meta, k)
        }
    }
}

/** Where keyboard focus is, as far as shortcuts care. */
enum class FocusContext { GENERAL, TERMINAL }

object CommandIds {
    const val QUICK_OPEN = "quick.open"
    const val COMMAND_PALETTE = "command.palette"
    const val TOGGLE_TREE = "toggle.tree"
    const val TOGGLE_TERMINAL = "toggle.terminal"
    const val TAB_NEXT = "tab.next"
    const val TAB_PREVIOUS = "tab.previous"
    const val TAB_CLOSE = "tab.close"
    const val SAVE = "save"
    const val FIND = "find"
    const val FIND_IN_FILES = "find.in.files"
    const val FOCUS_LEFT = "focus.left"
    const val FOCUS_RIGHT = "focus.right"
    const val FOCUS_UP = "focus.up"
    const val FOCUS_DOWN = "focus.down"
    const val SPLIT_RIGHT = "split.right"
    const val SPLIT_DOWN = "split.down"
    const val MAXIMISE = "maximise"
}

/** The command a chord resolved to, or "not ours - hand it to whoever has focus". */
sealed interface ShortcutResolution {
    data class Command(val id: String) : ShortcutResolution
    data object PassThrough : ShortcutResolution
}

sealed interface OverrideResult {
    data object Applied : OverrideResult
    /** [conflicts] already own the chord in this context; nothing changed. */
    data class Conflict(val conflicts: List<String>) : OverrideResult
    data object TerminalUnsafe : OverrideResult
    data object UnknownCommand : OverrideResult
}

/**
 * Default map: every command has a [general] chord and a [terminal] chord used while a terminal
 * pane has focus. Where the general chord is a plain Ctrl chord the terminal one is a Ctrl+Shift
 * or Alt chord. All chords are PROPOSED defaults, to be adjusted with the owner.
 */
object DefaultShortcuts {
    private fun c(s: String) = KeyChord.parse(s)!!

    val general: Map<String, KeyChord> = linkedMapOf(
        CommandIds.QUICK_OPEN to c("Ctrl+P"),
        CommandIds.COMMAND_PALETTE to c("Ctrl+Shift+P"),
        CommandIds.TOGGLE_TREE to c("Ctrl+B"),
        CommandIds.TOGGLE_TERMINAL to c("Ctrl+Backquote"),
        CommandIds.TAB_NEXT to c("Ctrl+Tab"),
        CommandIds.TAB_PREVIOUS to c("Ctrl+Shift+Tab"),
        CommandIds.TAB_CLOSE to c("Ctrl+W"),
        CommandIds.SAVE to c("Ctrl+S"),
        CommandIds.FIND to c("Ctrl+F"),
        CommandIds.FIND_IN_FILES to c("Ctrl+Shift+F"),
        CommandIds.FOCUS_LEFT to c("Ctrl+Alt+Left"),
        CommandIds.FOCUS_RIGHT to c("Ctrl+Alt+Right"),
        CommandIds.FOCUS_UP to c("Ctrl+Alt+Up"),
        CommandIds.FOCUS_DOWN to c("Ctrl+Alt+Down"),
        CommandIds.SPLIT_RIGHT to c("Ctrl+Backslash"),
        CommandIds.SPLIT_DOWN to c("Ctrl+Alt+Backslash"),
        CommandIds.MAXIMISE to c("Ctrl+Shift+M"),
    )

    val terminal: Map<String, KeyChord> = linkedMapOf(
        CommandIds.QUICK_OPEN to c("Ctrl+Shift+O"),
        CommandIds.COMMAND_PALETTE to c("Ctrl+Shift+P"),
        CommandIds.TOGGLE_TREE to c("Ctrl+Shift+B"),
        CommandIds.TOGGLE_TERMINAL to c("Ctrl+Shift+Backquote"),
        CommandIds.TAB_NEXT to c("Alt+PageDown"),
        CommandIds.TAB_PREVIOUS to c("Alt+PageUp"),
        CommandIds.TAB_CLOSE to c("Ctrl+Shift+W"),
        CommandIds.SAVE to c("Ctrl+Shift+S"),
        CommandIds.FIND to c("Ctrl+Alt+F"),
        CommandIds.FIND_IN_FILES to c("Ctrl+Shift+F"),
        CommandIds.FOCUS_LEFT to c("Ctrl+Alt+Left"),
        CommandIds.FOCUS_RIGHT to c("Ctrl+Alt+Right"),
        CommandIds.FOCUS_UP to c("Ctrl+Alt+Up"),
        CommandIds.FOCUS_DOWN to c("Ctrl+Alt+Down"),
        CommandIds.SPLIT_RIGHT to c("Alt+Backslash"),
        CommandIds.SPLIT_DOWN to c("Ctrl+Alt+Backslash"),
        CommandIds.MAXIMISE to c("Ctrl+Shift+M"),
    )
}

/**
 * Effective bindings: [DefaultShortcuts] plus persisted user overrides. Immutable; every change
 * returns a new instance. An override may not take a chord another command already owns in the
 * same context (reported, not applied), and in the terminal context may not be a chord the shell
 * needs ([KeyChord.isTerminalSafe]).
 */
class ShortcutMap private constructor(
    private val generalOverrides: Map<String, KeyChord>,
    private val terminalOverrides: Map<String, KeyChord>,
) {
    constructor() : this(emptyMap(), emptyMap())

    fun chordFor(command: String, ctx: FocusContext): KeyChord? = table(ctx)[command]

    fun table(ctx: FocusContext): Map<String, KeyChord> = when (ctx) {
        FocusContext.GENERAL -> DefaultShortcuts.general + generalOverrides
        FocusContext.TERMINAL -> DefaultShortcuts.terminal + terminalOverrides
    }

    /** Who owns each chord that more than one command claims (empty when conflict-free). */
    fun conflicts(ctx: FocusContext): Map<KeyChord, List<String>> =
        table(ctx).entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }

    fun resolve(chord: KeyChord, ctx: FocusContext): ShortcutResolution {
        if (ctx == FocusContext.TERMINAL && !chord.isTerminalSafe) return ShortcutResolution.PassThrough
        val owners = table(ctx).filterValues { it == chord }.keys
        return owners.firstOrNull()?.let { ShortcutResolution.Command(it) } ?: ShortcutResolution.PassThrough
    }

    fun resolve(e: KeyEventLike, ctx: FocusContext): ShortcutResolution =
        KeyChord.of(e)?.let { resolve(it, ctx) } ?: ShortcutResolution.PassThrough

    fun withOverride(command: String, chord: KeyChord, ctx: FocusContext): Pair<ShortcutMap, OverrideResult> {
        if (command !in DefaultShortcuts.general) return this to OverrideResult.UnknownCommand
        if (ctx == FocusContext.TERMINAL && !chord.isTerminalSafe) return this to OverrideResult.TerminalUnsafe
        val clash = table(ctx).filter { it.value == chord && it.key != command }.keys.toList()
        if (clash.isNotEmpty()) return this to OverrideResult.Conflict(clash)
        return when (ctx) {
            FocusContext.GENERAL -> ShortcutMap(generalOverrides + (command to chord), terminalOverrides)
            FocusContext.TERMINAL -> ShortcutMap(generalOverrides, terminalOverrides + (command to chord))
        } to OverrideResult.Applied
    }

    fun withoutOverride(command: String, ctx: FocusContext): ShortcutMap = when (ctx) {
        FocusContext.GENERAL -> ShortcutMap(generalOverrides - command, terminalOverrides)
        FocusContext.TERMINAL -> ShortcutMap(generalOverrides, terminalOverrides - command)
    }

    fun encodeOverrides(): String = JSONObject().apply {
        put("v", 1)
        put("general", JSONObject().also { o -> generalOverrides.forEach { (k, v) -> o.put(k, v.print()) } })
        put("terminal", JSONObject().also { o -> terminalOverrides.forEach { (k, v) -> o.put(k, v.print()) } })
    }.toString()

    companion object {
        /**
         * Tolerant: bad JSON gives the defaults; a bad single entry (unknown command, unparsable
         * chord, terminal-unsafe chord, or one that conflicts with an earlier entry) is skipped.
         */
        fun decodeOverrides(json: String?): ShortcutMap {
            if (json.isNullOrBlank() || json.length > 64 * 1024) return ShortcutMap()
            return try {
                val o = JSONObject(json)
                if (o.optInt("v", -1) != 1) return ShortcutMap()
                var m = ShortcutMap()
                for ((ctx, name) in listOf(FocusContext.GENERAL to "general", FocusContext.TERMINAL to "terminal")) {
                    val section = o.optJSONObject(name) ?: continue
                    val keys = section.keys().asSequence().toList().sorted()
                    for (k in keys) {
                        val chord = KeyChord.parse(section.optString(k, null)) ?: continue
                        m = m.withOverride(k, chord, ctx).first
                    }
                }
                m
            } catch (e: Exception) {
                ShortcutMap()
            }
        }
    }
}
