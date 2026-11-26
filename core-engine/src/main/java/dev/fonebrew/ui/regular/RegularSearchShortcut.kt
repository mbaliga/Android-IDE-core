package dev.fonebrew.ui.regular

/**
 * The hardware-keyboard Ctrl+K decision for [RegularShell], as a pure function so it is
 * JVM-tested. SpatialRoot binds the same chord (toggle the global search overlay); Regular must
 * not steal it from a text field, and a held key must not flicker the overlay open and shut.
 */
object RegularSearchShortcut {

    /**
     * @param isKeyDown a key-down event (not up).
     * @param ctrl Ctrl is held.
     * @param isK the key is K.
     * @param repeatCount 0 for the first press, > 0 for auto-repeat.
     * @param descendantHasFocus a node BELOW the shell root holds focus (in practice a text
     *  field). When true the keystroke belongs to that node.
     */
    fun decide(
        isKeyDown: Boolean,
        ctrl: Boolean,
        isK: Boolean,
        repeatCount: Int,
        descendantHasFocus: Boolean,
    ): Action = when {
        !isKeyDown || !ctrl || !isK -> Action.None
        repeatCount > 0 -> Action.None
        descendantHasFocus -> Action.None
        else -> Action.ToggleSearch
    }

    enum class Action { None, ToggleSearch }

    /** Whether a key event was consumed: only a handled toggle is; everything else passes on. */
    fun consumes(action: Action): Boolean = action == Action.ToggleSearch
}
