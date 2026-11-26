package dev.fonebrew.domain.editor.edit

enum class EditKind(val coalesces: Boolean) {
    TYPING(true), DELETE_BACKWARD(true), DELETE_FORWARD(true),
    PASTE(false), NEWLINE(false), LINE_OP(false), INDENT(false), COMMENT(false), REPLACE(false), OTHER(false),
}

/** One applied transaction and its exact inverse. */
class Step(val forward: Transaction, val inverse: Transaction)

/** An undo unit: one or more coalesced [steps] plus the selections and content versions on both sides. */
class HistoryEntry(
    val kind: EditKind,
    val steps: MutableList<Step>,
    val selectionBefore: SelectionSet,
    var selectionAfter: SelectionSet,
    val versionBefore: Long,
    var versionAfter: Long,
    var lastTimeMs: Long,
    var sealed: Boolean = false,
) {
    var chars: Int = steps.sumOf { it.forward.changedChars + it.inverse.changedChars }
}

/**
 * Bounded undo/redo, grouped by typing bursts.
 *
 * Coalescing: a new edit joins the top entry when both are the same coalescable [EditKind]
 * (typing, backspace, forward delete), the gap is <= [groupGapMs] by the caller's clock, the caret
 * has not moved since the previous edit (selection-before == the entry's selection-after), the
 * entry is not sealed (undo/redo/save/explicit [seal] seal it), and the inserted text has no newline.
 *
 * Bounds: at most [maxEntries] entries and about [maxChars] stored characters; the oldest entries
 * are dropped first and the newest is never dropped. After a drop, undo-all no longer reaches the
 * original text; [droppedEntries] says so. A new edit clears redo.
 */
class UndoHistory(
    val maxEntries: Int = 500,
    val maxChars: Int = 4_000_000,
    val groupGapMs: Long = 1000,
) {
    private val undoStack = ArrayList<HistoryEntry>()
    private val redoStack = ArrayList<HistoryEntry>()
    private var totalChars = 0L

    var droppedEntries: Int = 0
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoDepth: Int get() = undoStack.size
    val redoDepth: Int get() = redoStack.size

    fun record(
        kind: EditKind, step: Step, before: SelectionSet, after: SelectionSet,
        versionBefore: Long, versionAfter: Long, nowMs: Long, hasNewline: Boolean,
    ) {
        redoStack.clear()
        val top = undoStack.lastOrNull()
        if (top != null && kind.coalesces && !hasNewline && top.kind == kind && !top.sealed &&
            nowMs - top.lastTimeMs in 0..groupGapMs && top.selectionAfter == before && top.versionAfter == versionBefore
        ) {
            top.steps += step
            top.selectionAfter = after
            top.versionAfter = versionAfter
            top.lastTimeMs = nowMs
            val add = step.forward.changedChars + step.inverse.changedChars
            top.chars += add
            totalChars += add
        } else {
            val e = HistoryEntry(kind, mutableListOf(step), before, after, versionBefore, versionAfter, nowMs)
            undoStack += e
            totalChars += e.chars
        }
        while (undoStack.size > 1 && (undoStack.size > maxEntries || totalChars > maxChars)) {
            totalChars -= undoStack.removeAt(0).chars
            droppedEntries++
        }
    }

    /** End the current burst: the next edit starts a new undo unit. */
    fun seal() { undoStack.lastOrNull()?.sealed = true }

    fun popUndo(): HistoryEntry? {
        val e = undoStack.removeLastOrNull() ?: return null
        totalChars -= e.chars
        e.sealed = true
        return e
    }

    fun pushRedo(e: HistoryEntry) { redoStack += e }
    fun popRedo(): HistoryEntry? = redoStack.removeLastOrNull()

    /** Re-push after a redo, keeping it sealed so later typing cannot merge into it. */
    fun pushUndoAfterRedo(e: HistoryEntry) { e.sealed = true; undoStack += e; totalChars += e.chars }

    fun clear() { undoStack.clear(); redoStack.clear(); totalChars = 0 }
}
