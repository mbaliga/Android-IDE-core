package dev.fonebrew.domain.editor.edit

import dev.fonebrew.domain.editor.buffer.GraphemeBreaks
import dev.fonebrew.domain.editor.buffer.LineEndings
import dev.fonebrew.domain.editor.buffer.Rope
import dev.fonebrew.domain.editor.buffer.TextBuffer

enum class ChangeCause { EDIT, UNDO, REDO }

/** Told after every document change (the incremental lexer and the find session subscribe). */
fun interface TransactionListener {
    fun onChange(before: Rope, after: Rope, tx: Transaction, cause: ChangeCause)
}

sealed interface CommandResult {
    object Applied : CommandResult
    data class NoOp(val reason: String) : CommandResult
    data class Rejected(val error: TransactionError) : CommandResult
}

/**
 * The editing commands over a [TextBuffer], a [SelectionSet] and an [UndoHistory]. Every command
 * becomes one [Transaction] applied through a single path ([applyTransaction]), so undo, redo and
 * listeners see exactly what changed. Commands act on every selection (multi-cursor is designed in);
 * line moves act on the primary selection only. Offsets are UTF-16 units; deletion and caret moves
 * go by grapheme cluster, never into a surrogate pair or combining sequence.
 *
 * Callers pass the time ([nowMs]) so typing-burst grouping is deterministic under test.
 */
class Editor(
    val buffer: TextBuffer,
    val commentSyntax: CommentSyntax = CommentSyntax.NONE,
    indentOverride: IndentStyle? = null,
    val history: UndoHistory = UndoHistory(),
) {
    var selections: SelectionSet = SelectionSet.caret(0)
        private set

    val indentStyle: IndentStyle = indentOverride ?: detectIndent()

    /** What a freshly typed newline looks like (dominant style of the file when it was opened). */
    var newline: String = buffer.lineEndings().newline
        private set

    private val listeners = ArrayList<TransactionListener>()
    private var versionCounter = 0L

    /** Content version: bumps on edit, and undo/redo RESTORE the earlier value, so dirty is exact. */
    var version: Long = 0
        private set
    private var savedVersion = 0L
    private var goalColumn: Int? = null

    val isDirty: Boolean get() = version != savedVersion
    val text: String get() = buffer.toString()
    val rope: Rope get() = buffer.snapshot()

    fun addListener(l: TransactionListener) { listeners += l }
    fun removeListener(l: TransactionListener) { listeners -= l }

    /** Call after a successful save: the current content is the clean point; ends the typing burst. */
    fun markSaved() { savedVersion = version; history.seal() }

    fun refreshLineEndings() { newline = buffer.lineEndings().newline }

    private fun detectIndent(): IndentStyle {
        val r = buffer.snapshot()
        val n = minOf(r.lineCount, 2000)
        return IndentStyle.detect(sequence { for (i in 0 until n) yield(r.lineText(i)) })
    }

    // ---- selection --------------------------------------------------------------------------

    /** Clamp into the document and snap to grapheme boundaries so a caret never splits a cluster. */
    fun setSelection(set: SelectionSet) {
        val t = rope.asCharSequence()
        fun fix(o: Int) = GraphemeBreaks.floor(t, o.coerceIn(0, t.length))
        selections = set.map { Selection(fix(it.anchor), fix(it.head)) }
        goalColumn = null
        history.seal()
    }

    fun setCaret(offset: Int) = setSelection(SelectionSet.caret(offset))
    fun selectAll() = setSelection(SelectionSet.single(Selection(0, buffer.length)))

    fun selectWordAt(offset: Int) {
        val r = WordBreaks.wordRange(rope.asCharSequence(), offset) ?: return
        setSelection(SelectionSet.single(Selection(r.first, r.last + 1)))
    }

    fun selectLineAt(offset: Int) {
        val r = rope
        val l = r.lineOfOffset(offset.coerceIn(0, r.length))
        setSelection(SelectionSet.single(Selection(r.lineStart(l), r.lineEndWithTerminator(l))))
    }

    // ---- caret movement (no history entry) ---------------------------------------------------

    private fun move(extend: Boolean, keepGoal: Boolean = false, target: (CharSequence, Selection) -> Int) {
        val t = rope.asCharSequence()
        val saved = goalColumn
        selections = selections.map { s ->
            val h = GraphemeBreaks.floor(t, target(t, s).coerceIn(0, t.length))
            if (extend) Selection(s.anchor, h) else Selection.caret(h)
        }
        goalColumn = if (keepGoal) saved else null
        history.seal()
    }

    fun moveLeft(extend: Boolean = false) = move(extend) { t, s ->
        if (!extend && !s.isEmpty) s.start else GraphemeBreaks.previous(t, s.head)
    }

    fun moveRight(extend: Boolean = false) = move(extend) { t, s ->
        if (!extend && !s.isEmpty) s.end else GraphemeBreaks.next(t, s.head)
    }

    fun moveWordLeft(extend: Boolean = false) = move(extend) { t, s -> WordBreaks.previousWord(t, s.head) }
    fun moveWordRight(extend: Boolean = false) = move(extend) { t, s -> WordBreaks.nextWord(t, s.head) }

    fun moveLineEnd(extend: Boolean = false) = move(extend) { _, s -> rope.lineContentEnd(rope.lineOfOffset(s.head)) }

    /** Smart home: first press goes to the first non-blank, a second to column 0. */
    fun moveLineStart(extend: Boolean = false) = move(extend) { t, s ->
        val r = rope
        val l = r.lineOfOffset(s.head)
        val st = r.lineStart(l)
        var i = st
        val end = r.lineContentEnd(l)
        while (i < end && (t[i] == ' ' || t[i] == '\t')) i++
        if (s.head == i || i == end && s.head == st) st else i
    }

    fun moveVertical(delta: Int, extend: Boolean = false) {
        val r = rope
        val goal = goalColumn ?: (selections.primary.head - r.lineStart(r.lineOfOffset(selections.primary.head)))
        move(extend, keepGoal = true) { _, s ->
            val l = r.lineOfOffset(s.head)
            val nl = l + delta
            when {
                nl < 0 -> 0
                nl > r.lineBreaks -> r.length
                else -> minOf(r.lineStart(nl) + goal, r.lineContentEnd(nl))
            }
        }
        goalColumn = goal
    }

    fun moveDocStart(extend: Boolean = false) = move(extend) { _, _ -> 0 }
    fun moveDocEnd(extend: Boolean = false) = move(extend) { _, _ -> buffer.length }

    // ---- the single mutation path -------------------------------------------------------------

    /**
     * Apply [tx] as one undo step. [selectionAfter] defaults to every caret mapped through the edit.
     * Public so the find lane's replace-all lands as ONE step.
     */
    fun applyTransaction(
        tx: Transaction,
        kind: EditKind,
        selectionAfter: SelectionSet? = null,
        nowMs: Long = 0,
    ): CommandResult {
        if (buffer.readOnly) return CommandResult.Rejected(TransactionError.ReadOnly)
        if (tx.isNoop) return CommandResult.NoOp("nothing to change")
        tx.validate(buffer.length)?.let { return CommandResult.Rejected(it) }
        val before = buffer.snapshot()
        val (after, inverse) = tx.applyTo(before)
        val selBefore = selections
        val verBefore = version
        buffer.replaceRope(after)
        versionCounter++
        version = versionCounter
        selections = selectionAfter ?: selBefore.map { Selection(tx.map(it.anchor, Bias.AFTER), tx.map(it.head, Bias.AFTER)) }
        goalColumn = null
        val hasNl = tx.edits.any { e -> e.text.any { it == '\n' || it == '\r' } }
        history.record(kind, Step(tx, inverse), selBefore, selections, verBefore, version, nowMs, hasNl)
        listeners.forEach { it.onChange(before, after, tx, ChangeCause.EDIT) }
        return CommandResult.Applied
    }

    fun undo(): Boolean {
        val e = history.popUndo() ?: return false
        for (step in e.steps.asReversed()) swapIn(step.inverse, ChangeCause.UNDO)
        selections = e.selectionBefore
        version = e.versionBefore
        history.pushRedo(e)
        goalColumn = null
        return true
    }

    fun redo(): Boolean {
        val e = history.popRedo() ?: return false
        for (step in e.steps) swapIn(step.forward, ChangeCause.REDO)
        selections = e.selectionAfter
        version = e.versionAfter
        history.pushUndoAfterRedo(e)
        goalColumn = null
        return true
    }

    private fun swapIn(tx: Transaction, cause: ChangeCause) {
        val before = buffer.snapshot()
        val (after, _) = tx.applyTo(before)
        buffer.replaceRope(after)
        listeners.forEach { it.onChange(before, after, tx, cause) }
    }

    private fun perSelection(
        kind: EditKind, nowMs: Long,
        build: (Selection, MutableList<TextEdit>) -> ((Transaction) -> Selection),
    ): CommandResult {
        val edits = ArrayList<TextEdit>()
        val posts = selections.ranges.map { build(it, edits) }
        val tx = Transaction(edits)
        if (tx.isNoop) return CommandResult.NoOp("nothing to change")
        val after = SelectionSet.of(posts.map { it(tx) }, selections.primaryIndex)
        return applyTransaction(tx, kind, after, nowMs)
    }

    // ---- typing -------------------------------------------------------------------------------

    /** Insert [text] at every selection (replacing it). Newlines are rewritten to the file's style. */
    fun typeText(text: String, nowMs: Long = 0, kind: EditKind = EditKind.TYPING): CommandResult {
        val s = LineEndings.normalize(text, newline)
        if (s.isEmpty() && selections.ranges.all { it.isEmpty }) return CommandResult.NoOp("empty input")
        val k = if (s.contains('\n') || s.contains('\r')) (if (kind == EditKind.PASTE) EditKind.PASTE else EditKind.NEWLINE) else kind
        return perSelection(k, nowMs) { sel, edits ->
            edits += TextEdit(sel.start, sel.end, s)
            ({ tx: Transaction -> Selection.caret(tx.map(sel.end, Bias.AFTER)) })
        }
    }

    fun paste(text: String, nowMs: Long = 0): CommandResult = typeText(text, nowMs, EditKind.PASTE)

    private val closers = mapOf('(' to ')', '[' to ']', '{' to '}')
    private val closeSet = closers.values.toSet()
    private val quotes = setOf('"', '\'', '`')

    /**
     * One typed character (a code point as a String), with bracket/quote pairing: wrap a selection,
     * step over an existing closer, auto-close before whitespace or a closer.
     */
    fun typeChar(ch: String, nowMs: Long = 0): CommandResult {
        if (ch.length != 1) return typeText(ch, nowMs)   // surrogate pairs and strings: plain insert
        val c = ch[0]
        val isOpen = c in closers
        val isQuote = c in quotes
        val isClose = c in closeSet
        if (!isOpen && !isQuote && !isClose) return typeText(ch, nowMs)
        val r = rope
        val len = r.length
        val skipAll = selections.ranges.all { it.isEmpty && it.start < len && r[it.start] == c && (isClose || isQuote) }
        if (skipAll) {
            selections = selections.map { Selection.caret(it.start + 1) }
            goalColumn = null
            history.seal()
            return CommandResult.Applied
        }
        return perSelection(EditKind.TYPING, nowMs) { sel, edits ->
            val next = if (sel.end < len) r[sel.end] else null
            val prev = if (sel.start > 0) r[sel.start - 1] else null
            when {
                !sel.isEmpty && (isOpen || isQuote) -> {
                    val close = if (isOpen) closers.getValue(c) else c
                    edits += TextEdit(sel.start, sel.start, c.toString())
                    edits += TextEdit(sel.end, sel.end, close.toString())
                    val fwd = sel.head >= sel.anchor
                    ({ tx: Transaction ->
                        val a = tx.map(sel.start, Bias.AFTER)
                        val b = tx.map(sel.end, Bias.BEFORE)
                        if (fwd) Selection(a, b) else Selection(b, a)
                    })
                }
                sel.isEmpty && next == c && (isClose || isQuote) -> {
                    // mixed multi-cursor skip-over: replace the closer with itself so the caret steps past it
                    edits += TextEdit(sel.start, sel.start + 1, c.toString())
                    ({ tx: Transaction -> Selection.caret(tx.map(sel.start + 1, Bias.AFTER)) })
                }
                sel.isEmpty && isOpen && (next == null || next.isWhitespace() || next in closeSet || next in ",;:.") -> {
                    edits += TextEdit(sel.start, sel.start, "$c${closers.getValue(c)}")
                    ({ tx: Transaction -> Selection.caret(tx.map(sel.start, Bias.BEFORE) + 1) })
                }
                sel.isEmpty && isQuote && !(prev != null && (prev.isLetterOrDigit() || prev == '_' || prev == c)) &&
                    (next == null || next.isWhitespace() || next in closeSet || next in ",;:.") -> {
                    edits += TextEdit(sel.start, sel.start, "$c$c")
                    ({ tx: Transaction -> Selection.caret(tx.map(sel.start, Bias.BEFORE) + 1) })
                }
                else -> {
                    edits += TextEdit(sel.start, sel.end, c.toString())
                    ({ tx: Transaction -> Selection.caret(tx.map(sel.end, Bias.AFTER)) })
                }
            }
        }
    }

    /** Enter: keeps the line's indentation, adds one level after an opening bracket, and splits `{|}`. */
    fun insertNewline(nowMs: Long = 0): CommandResult {
        val r = rope
        val len = r.length
        val unit = indentStyle.unit
        return perSelection(EditKind.NEWLINE, nowMs) { sel, edits ->
            val ls = r.lineStart(r.lineOfOffset(sel.start))
            val prefix = r.substring(ls, sel.start)
            val indent = prefix.takeWhile { it == ' ' || it == '\t' }
            val prevCh = prefix.trimEnd().lastOrNull()
            val nextCh = if (sel.end < len) r[sel.end] else null
            val closeOfPrev = prevCh?.let { closers[it] }
            val opener = closeOfPrev != null
            val caretIn: Int
            val ins: String
            if (opener && nextCh == closeOfPrev) {
                val head = newline + indent + unit
                ins = head + newline + indent
                caretIn = head.length
            } else {
                ins = newline + indent + (if (opener) unit else "")
                caretIn = ins.length
            }
            edits += TextEdit(sel.start, sel.end, ins)
            ({ tx: Transaction -> Selection.caret(tx.map(sel.start, Bias.BEFORE) + caretIn) })
        }
    }

    // ---- deletion ------------------------------------------------------------------------------

    private fun deleteRanges(kind: EditKind, nowMs: Long, rangeFor: (Rope, CharSequence, Selection) -> IntRange?): CommandResult {
        val r = rope
        val t = r.asCharSequence()
        val per = selections.ranges.map { rangeFor(r, t, it) }
        val edits = Transaction.unionDeletes(per.filterNotNull())
        val tx = Transaction(edits)
        if (tx.isNoop) return CommandResult.NoOp("nothing to delete")
        val after = SelectionSet.of(
            selections.ranges.mapIndexed { i, s -> Selection.caret(tx.map(per[i]?.first ?: s.start, Bias.BEFORE)) },
            selections.primaryIndex,
        )
        return applyTransaction(tx, kind, after, nowMs)
    }

    fun backspace(nowMs: Long = 0): CommandResult = deleteRanges(EditKind.DELETE_BACKWARD, nowMs) { r, t, s ->
        val p = s.start
        when {
            !s.isEmpty -> s.start until s.end
            p == 0 -> null
            t[p - 1] in closers && p < t.length && t[p] == closers[t[p - 1]] -> (p - 1) until (p + 1)
            t[p - 1] in quotes && p < t.length && t[p] == t[p - 1] && (p < 2 || !t[p - 2].isLetterOrDigit()) -> (p - 1) until (p + 1)
            else -> {
                val ls = r.lineStart(r.lineOfOffset(p))
                val col = p - ls
                if (!indentStyle.useTabs && col > 0 && (ls until p).all { t[it] == ' ' }) {
                    val w = indentStyle.width
                    val n = if (col % w == 0) w else col % w
                    (p - n) until p
                } else GraphemeBreaks.previous(t, p) until p
            }
        }
    }

    fun deleteForward(nowMs: Long = 0): CommandResult = deleteRanges(EditKind.DELETE_FORWARD, nowMs) { _, t, s ->
        when {
            !s.isEmpty -> s.start until s.end
            s.start >= t.length -> null
            else -> s.start until GraphemeBreaks.next(t, s.start)
        }
    }

    fun deleteWordBackward(nowMs: Long = 0): CommandResult = deleteRanges(EditKind.DELETE_BACKWARD, nowMs) { _, t, s ->
        if (!s.isEmpty) s.start until s.end
        else WordBreaks.previousWord(t, s.start).let { if (it < s.start) it until s.start else null }
    }

    fun deleteWordForward(nowMs: Long = 0): CommandResult = deleteRanges(EditKind.DELETE_FORWARD, nowMs) { _, t, s ->
        if (!s.isEmpty) s.start until s.end
        else WordBreaks.nextWord(t, s.start).let { if (it > s.start) s.start until it else null }
    }

    /** Replace `[start, end)` outright (programmatic edit; one undo step). */
    fun replaceRange(start: Int, end: Int, text: String, nowMs: Long = 0): CommandResult =
        applyTransaction(
            Transaction(listOf(TextEdit(start, end, text))), EditKind.REPLACE,
            SelectionSet.caret(start + text.length), nowMs,
        )

    // ---- line-oriented commands ----------------------------------------------------------------

    /** Inclusive line ranges covered by the selections; a selection ending at column 0 excludes that line. */
    private fun lineBlocks(r: Rope, sels: List<Selection>): List<IntRange> {
        val raw = sels.map { s ->
            val first = r.lineOfOffset(s.start)
            var last = r.lineOfOffset(s.end)
            if (!s.isEmpty && last > first && s.end == r.lineStart(last)) last--
            first..last
        }.sortedBy { it.first }
        val out = ArrayList<IntRange>()
        for (b in raw) {
            val l = out.lastOrNull()
            if (l != null && b.first <= l.last + 1) out[out.size - 1] = l.first..maxOf(l.last, b.last) else out += b
        }
        return out
    }

    private fun mapSelectionsThrough(tx: Transaction): SelectionSet = selections.map { s ->
        if (s.isEmpty) Selection.caret(tx.map(s.head, Bias.AFTER))
        else if (s.head >= s.anchor) Selection(tx.map(s.anchor, Bias.BEFORE), tx.map(s.head, Bias.AFTER))
        else Selection(tx.map(s.anchor, Bias.AFTER), tx.map(s.head, Bias.BEFORE))
    }

    private fun lineEdits(kind: EditKind, nowMs: Long, build: (Rope, IntRange, MutableList<TextEdit>) -> Unit): CommandResult {
        val r = rope
        val edits = ArrayList<TextEdit>()
        for (b in lineBlocks(r, selections.ranges)) build(r, b, edits)
        val tx = Transaction(edits)
        if (tx.isNoop) return CommandResult.NoOp("nothing to change")
        return applyTransaction(tx, kind, mapSelectionsThrough(tx), nowMs)
    }

    /** Tab: insert one indent (to the next stop) at each caret, or indent every covered line for a multi-line selection. */
    fun indent(nowMs: Long = 0): CommandResult {
        val r = rope
        val multi = selections.ranges.any { r.lineOfOffset(it.start) != r.lineOfOffset(it.end) }
        if (multi) return indentLines(nowMs)
        return perSelection(EditKind.INDENT, nowMs) { sel, edits ->
            val col = sel.start - r.lineStart(r.lineOfOffset(sel.start))
            val ins = if (indentStyle.useTabs) "\t" else " ".repeat(indentStyle.width - col % indentStyle.width)
            edits += TextEdit(sel.start, sel.end, ins)
            ({ tx: Transaction -> Selection.caret(tx.map(sel.end, Bias.AFTER)) })
        }
    }

    fun indentLines(nowMs: Long = 0): CommandResult = lineEdits(EditKind.INDENT, nowMs) { r, b, edits ->
        for (l in b) if (r.lineContentEnd(l) > r.lineStart(l)) edits += TextEdit(r.lineStart(l), r.lineStart(l), indentStyle.unit)
    }

    fun outdent(nowMs: Long = 0): CommandResult = lineEdits(EditKind.INDENT, nowMs) { r, b, edits ->
        val w = indentStyle.width
        for (l in b) {
            val s = r.lineStart(l)
            val e = r.lineContentEnd(l)
            if (s == e) continue
            if (r[s] == '\t') edits += TextEdit(s, s + 1, "")
            else {
                var n = 0
                while (n < w && s + n < e && r[s + n] == ' ') n++
                if (n > 0) edits += TextEdit(s, s + n, "")
            }
        }
    }

    /** Toggle the language's line comment (or per-line block comment where it has no line form). */
    fun toggleLineComment(nowMs: Long = 0): CommandResult {
        if (!commentSyntax.supported) return CommandResult.NoOp("this language has no comment syntax")
        val line = commentSyntax.line
        return lineEdits(EditKind.COMMENT, nowMs) { r, b, edits ->
            val lines = b.filter { r.lineText(it).isNotBlank() }
            if (lines.isEmpty()) return@lineEdits
            if (line != null) {
                val allCommented = lines.all { r.lineText(it).trimStart().startsWith(line) }
                if (allCommented) {
                    for (l in lines) {
                        val s = r.lineStart(l)
                        val txt = r.lineText(l)
                        val ind = txt.length - txt.trimStart().length
                        val hasSpace = txt.length > ind + line.length && txt[ind + line.length] == ' '
                        edits += TextEdit(s + ind, s + ind + line.length + if (hasSpace) 1 else 0, "")
                    }
                } else {
                    val m = lines.minOf { l -> r.lineText(l).let { it.length - it.trimStart().length } }
                    for (l in lines) edits += TextEdit(r.lineStart(l) + m, r.lineStart(l) + m, "$line ")
                }
            } else {
                val open = commentSyntax.blockOpen!!
                val close = commentSyntax.blockClose!!
                val allCommented = lines.all { r.lineText(it).trim().let { t -> t.startsWith(open) && t.endsWith(close) && t.length >= open.length + close.length } }
                for (l in lines) {
                    val s = r.lineStart(l)
                    val txt = r.lineText(l)
                    val ind = txt.length - txt.trimStart().length
                    val endTrim = s + txt.trimEnd().length
                    if (allCommented) {
                        val t = txt.trim()
                        val hasOpenSp = t.length > open.length && t[open.length] == ' '
                        val hasCloseSp = t.length - close.length - 1 >= open.length && t[t.length - close.length - 1] == ' '
                        edits += TextEdit(s + ind, s + ind + open.length + if (hasOpenSp) 1 else 0, "")
                        edits += TextEdit(endTrim - close.length - if (hasCloseSp) 1 else 0, endTrim, "")
                    } else {
                        edits += TextEdit(s + ind, s + ind, "$open ")
                        edits += TextEdit(endTrim, endTrim, " $close")
                    }
                }
            }
        }
    }

    /** Swap the primary selection's lines with the line above/below, keeping each position's terminator. */
    fun moveLines(up: Boolean, nowMs: Long = 0): CommandResult {
        val r = rope
        val prim = selections.primary
        val block = lineBlocks(r, listOf(prim)).first()
        val a = block.first
        val b = block.last
        if (up && a == 0) return CommandResult.NoOp("already at the first line")
        if (!up && b >= r.lineBreaks) return CommandResult.NoOp("already at the last line")
        val lo = if (up) a - 1 else a
        val hi = if (up) b else b + 1
        val contents = (lo..hi).map { r.lineText(it) }
        val terms = (lo..hi).map { r.terminatorAt(it) }
        val moved = if (up) contents.subList(1, contents.size) + contents[0]
        else listOf(contents.last()) + contents.subList(0, contents.size - 1)
        val base = r.lineStart(lo)
        val newStarts = IntArray(moved.size)
        val sb = StringBuilder()
        for (k in moved.indices) { newStarts[k] = base + sb.length; sb.append(moved[k]).append(terms[k]) }
        val tx = Transaction(listOf(TextEdit(base, r.lineEndWithTerminator(hi), sb.toString())))
        fun mapOff(o: Int): Int {
            val l = r.lineOfOffset(o)
            if (l !in a..b) return tx.map(o, Bias.AFTER)
            val col = o - r.lineStart(l)
            val pos = (l - lo) + if (up) -1 else 1
            return minOf(newStarts[pos] + col, newStarts[pos] + moved[pos].length)
        }
        return applyTransaction(tx, EditKind.LINE_OP, SelectionSet.single(Selection(mapOff(prim.anchor), mapOff(prim.head))), nowMs)
    }

    /** Copy each covered block below itself; the selection moves onto the copy. */
    fun duplicateLines(nowMs: Long = 0): CommandResult {
        val r = rope
        val blocks = lineBlocks(r, selections.ranges)
        val edits = ArrayList<TextEdit>()
        val inserted = ArrayList<Int>()
        for (b in blocks) {
            val s = r.lineStart(b.first)
            if (b.last < r.lineBreaks) {
                val at = r.lineStart(b.last + 1)
                val txt = r.substring(s, at)
                edits += TextEdit(at, at, txt); inserted += txt.length
            } else {
                val txt = newline + r.substring(s, r.length)
                edits += TextEdit(r.length, r.length, txt); inserted += txt.length
            }
        }
        val tx = Transaction(edits)
        val after = selections.map { sel ->
            val bi = blocks.indexOfFirst { r.lineOfOffset(sel.start) in it }
            fun m(o: Int) = tx.map(o, Bias.BEFORE) + inserted[bi]
            Selection(m(sel.anchor), m(sel.head))
        }
        return applyTransaction(tx, EditKind.LINE_OP, after, nowMs)
    }

    fun deleteLines(nowMs: Long = 0): CommandResult {
        val r = rope
        val ranges = ArrayList<IntRange>()
        for (b in lineBlocks(r, selections.ranges)) {
            val lastLine = r.lineBreaks
            var s = r.lineStart(b.first)
            val e = r.lineEndWithTerminator(b.last)
            if (b.last == lastLine && b.first > 0) {
                if (b.first == b.last && r.lineStart(lastLine) == r.length) continue   // the empty line after a final newline
                s = r.lineContentEnd(b.first - 1)
            }
            if (e > s) ranges += s until e
        }
        val tx = Transaction(Transaction.unionDeletes(ranges))
        if (tx.isNoop) return CommandResult.NoOp("nothing to delete")
        val after = SelectionSet.of(selections.ranges.map { Selection.caret(tx.map(r.lineStart(r.lineOfOffset(it.start)), Bias.BEFORE)) }, selections.primaryIndex)
        return applyTransaction(tx, EditKind.LINE_OP, after, nowMs)
    }
}
