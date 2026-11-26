package dev.fonebrew.domain.editor.lex

import dev.fonebrew.domain.editor.buffer.Rope
import dev.fonebrew.domain.editor.edit.Transaction

/** Read access to a document by line (a [Rope] adapter is provided). */
interface LineSource {
    val lineCount: Int
    fun lineText(line: Int): String
}

class RopeLineSource(private val rope: Rope) : LineSource {
    override val lineCount: Int get() = rope.lineCount
    override fun lineText(line: Int): String = rope.lineText(line)
}

/**
 * Per-line tokens with the lexer state carried between lines. After an edit, [update] re-lexes from the
 * first changed line and stops at the first line (past the edited block) whose start state equals the
 * one stored before, so typing in the middle of a file re-lexes a handful of lines, not the file.
 *
 * Over-long lines are bounded: only the first [MAX_LINE] chars are lexed; the remainder is one token
 * in the category active at the cut and the cut state is carried (stated limit).
 */
class IncrementalLexer(val lexer: LineLexer) {
    companion object { const val MAX_LINE = 4096 }

    private val tokens = ArrayList<List<Token>>()
    private val startStates = ArrayList<LexState>()
    private val endStates = ArrayList<LexState>()

    val lineCount: Int get() = tokens.size
    fun tokens(line: Int): List<Token> = tokens[line]
    fun startState(line: Int): LexState = startStates[line]
    fun endState(line: Int): LexState = endStates[line]

    /** Lex the whole source from scratch. */
    fun reset(source: LineSource) {
        tokens.clear(); startStates.clear(); endStates.clear()
        var st = LexState.INITIAL
        for (i in 0 until source.lineCount) {
            startStates.add(st)
            val (t, e) = lexOne(source.lineText(i), st)
            tokens.add(t); endStates.add(e)
            st = e
        }
    }

    /** The lines `[first, last)` that were re-lexed by an update. */
    data class Changed(val first: Int, val last: Int)

    /**
     * [source] is the document AFTER the edit; lines `[firstLine, firstLine + oldCount)` of the old
     * document became `[firstLine, firstLine + newCount)`.
     */
    fun update(source: LineSource, firstLine: Int, oldCount: Int, newCount: Int): Changed {
        repeat(minOf(oldCount, tokens.size - firstLine)) {
            tokens.removeAt(firstLine); startStates.removeAt(firstLine); endStates.removeAt(firstLine)
        }
        repeat(newCount) {
            tokens.add(firstLine, emptyList()); startStates.add(firstLine, LexState.INITIAL); endStates.add(firstLine, LexState.INITIAL)
        }
        // the lists must match the document: pad/trim defensively rather than trust the caller
        while (tokens.size < source.lineCount) { tokens.add(emptyList()); startStates.add(LexState.INITIAL); endStates.add(LexState.INITIAL) }
        while (tokens.size > source.lineCount) { tokens.removeAt(tokens.size - 1); startStates.removeAt(startStates.size - 1); endStates.removeAt(endStates.size - 1) }
        var i = firstLine
        var cur = if (i == 0) LexState.INITIAL else endStates[i - 1]
        val inserted = firstLine + newCount
        while (i < tokens.size) {
            if (i >= inserted && startStates[i] == cur) break
            startStates[i] = cur
            val (t, e) = lexOne(source.lineText(i), cur)
            tokens[i] = t; endStates[i] = e
            cur = e
            i++
        }
        return Changed(firstLine, i)
    }

    /** Convenience: derive the changed line span from a [Transaction] applied between [before] and [after]. */
    fun update(before: Rope, after: Rope, tx: Transaction): Changed {
        if (tx.edits.isEmpty()) return Changed(0, 0)
        val lo = tx.edits.minOf { it.start }
        val hi = tx.edits.maxOf { it.end }
        val first = before.lineOfOffset(lo)
        val oldLast = before.lineOfOffset(hi)
        val oldCount = oldLast - first + 1
        val newCount = oldCount + (after.lineCount - before.lineCount)
        return update(RopeLineSource(after), first, oldCount, newCount)
    }

    private fun lexOne(line: String, st: LexState): Pair<List<Token>, LexState> {
        if (line.length <= MAX_LINE) {
            val sink = TokenSink(line.length)
            val e = lexer.lexLine(line, st, sink)
            return sink.build() to e
        }
        var cut = MAX_LINE
        if (Character.isHighSurrogate(line[cut - 1])) cut--
        val head = line.substring(0, cut)
        val sink = TokenSink(line.length)
        val mid = lexer.lexLine(head, st, sink)
        sink.add(cut, line.length, lexer.categoryAt(mid))
        return sink.build() to mid
    }
}
