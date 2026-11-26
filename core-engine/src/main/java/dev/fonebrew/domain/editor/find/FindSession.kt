package dev.fonebrew.domain.editor.find

import dev.fonebrew.domain.editor.edit.CommandResult
import dev.fonebrew.domain.editor.edit.EditKind
import dev.fonebrew.domain.editor.edit.Editor
import dev.fonebrew.domain.editor.edit.Selection
import dev.fonebrew.domain.editor.edit.SelectionSet
import dev.fonebrew.domain.editor.edit.TextEdit
import dev.fonebrew.domain.editor.edit.Transaction

sealed interface ReplaceOutcome {
    data class Replaced(val count: Int) : ReplaceOutcome
    data class Failed(val reason: String) : ReplaceOutcome
    data class Refused(val outcome: FindOutcome) : ReplaceOutcome

    /** More matches than the safety cap: nothing was changed (never a silent partial replace-all). */
    data class TooMany(val cap: Int) : ReplaceOutcome
    object NothingToReplace : ReplaceOutcome
}

/**
 * Find/replace bound to an [Editor]. Matching works on a per-version string snapshot; asking for the
 * matches of just a visible window ([matchesIn]) keeps highlighting cheap on a big file, and the cache
 * is dropped the moment the document changes. Replace-all is ONE transaction, so it is one undo step.
 */
class FindSession(private val editor: Editor) {
    var query: FindQuery? = null
        private set

    private var cachedRope: Any? = null
    private var cachedText: String = ""

    private fun text(): String {
        val r = editor.rope
        if (cachedRope !== r) { cachedText = r.toString(); cachedRope = r }
        return cachedText
    }

    fun setQuery(q: FindQuery?) { query = q }

    /** Matches wholly inside `[start, end)`, for highlighting the visible window. */
    fun matchesIn(start: Int, end: Int, max: Int = FindEngine.DEFAULT_MAX_MATCHES): FindOutcome {
        val q = query ?: return FindOutcome.EmptyPattern
        return FindEngine.findAll(text(), q, start, end, max)
    }

    /** Every match (bounded); `Found.truncated` says whether the count is a floor. */
    fun all(max: Int = FindEngine.DEFAULT_MAX_MATCHES): FindOutcome = matchesIn(0, editor.buffer.length, max)

    /** Next match at or after [from]; wraps to the start when [wrap]. */
    fun next(from: Int, wrap: Boolean = true): FindEngine.FindResult {
        val q = query ?: return FindEngine.FindResult.Failed(FindOutcome.EmptyPattern)
        val t = text()
        val r = FindEngine.findNext(t, q, from)
        if (r is FindEngine.FindResult.None && wrap && from > 0) return FindEngine.findNext(t, q, 0)
        return r
    }

    /** Previous match ending at or before [before]; wraps to the end when [wrap]. */
    fun previous(before: Int, wrap: Boolean = true): FindEngine.FindResult {
        val q = query ?: return FindEngine.FindResult.Failed(FindOutcome.EmptyPattern)
        val t = text()
        val r = FindEngine.findPrevious(t, q, before)
        if (r is FindEngine.FindResult.None && wrap && before < t.length) return FindEngine.findPrevious(t, q, t.length)
        return r
    }

    /** Select [m] in the editor (caret handles are the UI's job). */
    fun select(m: Match) = editor.setSelection(SelectionSet.single(Selection(m.start, m.end)))

    fun replaceOne(m: Match, replacement: String, nowMs: Long = 0): ReplaceOutcome {
        val q = query ?: return ReplaceOutcome.Refused(FindOutcome.EmptyPattern)
        val t = text()
        if (m.start < 0 || m.end > t.length || m.start > m.end) return ReplaceOutcome.Failed("match is outside the document")
        val exp = FindEngine.expandReplacement(t, q, m, replacement).getOrElse { return ReplaceOutcome.Failed(it.message ?: "bad replacement") }
        return when (val r = editor.applyTransaction(
            Transaction(listOf(TextEdit(m.start, m.end, exp))), EditKind.REPLACE,
            SelectionSet.caret(m.start + exp.length), nowMs,
        )) {
            is CommandResult.Applied -> ReplaceOutcome.Replaced(1)
            is CommandResult.NoOp -> ReplaceOutcome.NothingToReplace
            is CommandResult.Rejected -> ReplaceOutcome.Failed("edit rejected: ${r.error::class.simpleName}")
        }
    }

    fun replaceAll(replacement: String, cap: Int = 100_000, nowMs: Long = 0): ReplaceOutcome {
        val q = query ?: return ReplaceOutcome.Refused(FindOutcome.EmptyPattern)
        val t = text()
        val found = when (val o = FindEngine.findAll(t, q, maxMatches = cap)) {
            is FindOutcome.Found -> o
            else -> return ReplaceOutcome.Refused(o)
        }
        if (found.truncated) return ReplaceOutcome.TooMany(cap)
        if (found.matches.isEmpty()) return ReplaceOutcome.NothingToReplace
        val edits = ArrayList<TextEdit>(found.matches.size)
        for (m in found.matches) {
            val exp = FindEngine.expandReplacement(t, q, m, replacement).getOrElse { return ReplaceOutcome.Failed(it.message ?: "bad replacement") }
            if (exp != t.substring(m.start, m.end)) edits += TextEdit(m.start, m.end, exp)
        }
        if (edits.isEmpty()) return ReplaceOutcome.NothingToReplace
        return when (val r = editor.applyTransaction(Transaction(edits), EditKind.REPLACE, null, nowMs)) {
            is CommandResult.Applied -> ReplaceOutcome.Replaced(edits.size)
            is CommandResult.NoOp -> ReplaceOutcome.NothingToReplace
            is CommandResult.Rejected -> ReplaceOutcome.Failed("edit rejected: ${r.error::class.simpleName}")
        }
    }
}
