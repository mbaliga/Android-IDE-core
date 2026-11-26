package dev.fonebrew.domain.workspace.grep

import dev.fonebrew.domain.workspace.ignore.IgnoreMatcher
import dev.fonebrew.domain.workspace.ignore.RootIgnore
import dev.fonebrew.domain.workspace.roots.Workspace
import dev.fonebrew.domain.workspace.roots.WorkspaceRoot
import dev.fonebrew.domain.workspace.tree.FileWalker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Literal and regular-expression search across a [Workspace]'s files, as a cold [Flow] of
 * [SearchEvent]s that ends with exactly one [SearchEvent.Done] unless the collector cancels (then
 * it simply stops: cancellation is checked between files, between lines and, for regexes, every
 * 1024 character reads inside the engine). Runs on [Dispatchers.IO].
 *
 * Honest bounds: ignore rules + the root's excludes apply; symlinks are skipped; a file with a NUL
 * in it, or over [SearchRequest.maxFileBytes], is reported as [SearchEvent.Skipped] rather than
 * silently omitted; lines over [SearchRequest.maxLineChars] are not regex-searched (counted);
 * matching is line by line (no multi-line regex: a named limit). A regex that blows its budget on
 * a line ends the search with [SearchOutcome.Timeout] naming the file and line, so a pathological
 * pattern is a visible, typed result and never a hang. Replace is PREVIEW ONLY: `replacedLine`
 * is computed in memory; no file is written by this class.
 */
class SearchInFiles(
    private val workspace: Workspace,
    private val matcherFor: (WorkspaceRoot) -> IgnoreMatcher = { RootIgnore.matcherFor(it) },
) {
    private class Compiled(val pattern: Pattern?, val literal: String?, val ignoreCase: Boolean, val wholeWord: Boolean)

    fun search(req: SearchRequest): Flow<SearchEvent> = flow {
        val job = currentCoroutineContext()[Job]
        val tick: () -> Unit = { job?.ensureActive() }
        val compiled = try {
            compile(req)
        } catch (e: PatternSyntaxException) {
            emit(SearchEvent.Done(SearchOutcome.InvalidPattern(e.description ?: "invalid pattern"), emptyStats())); return@flow
        }
        if (req.regex && !req.allowRiskyRegex) {
            RegexLint.risk(req.pattern)?.let { emit(SearchEvent.Done(SearchOutcome.RiskyPattern(it), emptyStats())); return@flow }
        }
        if (req.pattern.isEmpty()) { emit(SearchEvent.Done(SearchOutcome.InvalidPattern("empty pattern"), emptyStats())); return@flow }
        val globs = GlobFilter(req.includeGlobs, req.excludeGlobs)
        var scanned = 0; var withMatches = 0; var total = 0
        var bin = 0; var big = 0; var unread = 0; var longLines = 0
        var outcome: SearchOutcome = SearchOutcome.Completed

        roots@ for (root in workspace.roots()) {
            if (req.rootIds != null && root.id !in req.rootIds) continue
            val provider = workspace.provider(root.id) ?: continue
            val walker = FileWalker(provider.rootPath, matcherFor(root), maxFiles = req.maxFiles)
            for (f in walker.files()) {
                tick()
                if (!globs.accepts(f.rel)) continue
                scanned++
                if (f.sizeBytes > req.maxFileBytes) { big++; emit(SearchEvent.Skipped(root.id, f.rel, SkipReason.TOO_LARGE)); continue }
                val room = req.maxMatches - total
                val r = try {
                    searchFile(f.path, compiled, req, minOf(req.maxMatchesPerFile, room), tick)
                } catch (e: LineBudgetExceeded) {
                    outcome = SearchOutcome.Timeout(root.id, f.rel, e.line); break@roots
                } catch (e: IOException) {
                    unread++; emit(SearchEvent.Skipped(root.id, f.rel, SkipReason.UNREADABLE)); continue
                } catch (e: IllegalArgumentException) {
                    outcome = SearchOutcome.InvalidPattern("bad replacement: ${e.message}"); break@roots
                } catch (e: IndexOutOfBoundsException) {
                    outcome = SearchOutcome.InvalidPattern("bad replacement: ${e.message}"); break@roots
                }
                longLines += r.longLines
                if (r.binary) { bin++; emit(SearchEvent.Skipped(root.id, f.rel, SkipReason.BINARY)); continue }
                if (r.matches.isNotEmpty()) {
                    withMatches++; total += r.matches.size
                    emit(SearchEvent.FileHit(root.id, f.rel, r.matches, r.more))
                    if (total >= req.maxMatches) { outcome = SearchOutcome.ResultLimit(req.maxMatches); break@roots }
                }
            }
            if (walker.truncated) { if (outcome == SearchOutcome.Completed) outcome = SearchOutcome.FileLimit }
        }
        emit(SearchEvent.Done(outcome, SearchStats(scanned, withMatches, total, bin, big, unread, longLines)))
    }.flowOn(Dispatchers.IO)

    private class LineBudgetExceeded(val line: Int) : RuntimeException(null, null, false, false)

    private class FileResult(val matches: List<SearchMatch>, val more: Boolean, val binary: Boolean, val longLines: Int)

    private fun emptyStats() = SearchStats(0, 0, 0, 0, 0, 0, 0)

    private fun compile(req: SearchRequest): Compiled {
        if (!req.regex) return Compiled(null, req.pattern, !req.caseSensitive, req.wholeWord)
        var flags = 0
        if (!req.caseSensitive) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        val src = if (req.wholeWord) "(?<![\\p{L}\\p{N}_])(?:${req.pattern})(?![\\p{L}\\p{N}_])" else req.pattern
        return Compiled(Pattern.compile(src, flags), null, false, false)
    }

    private fun searchFile(path: java.nio.file.Path, c: Compiled, req: SearchRequest, limit: Int, tick: () -> Unit): FileResult {
        if (limit <= 0) return FileResult(emptyList(), true, false, 0)
        // NUL sniff first: cheap, and spares decoding a binary
        Files.newInputStream(path).use { ins ->
            val head = ByteArray(8192)
            val n = ins.read(head)
            for (i in 0 until maxOf(n, 0)) if (head[i] == 0.toByte()) return FileResult(emptyList(), false, true, 0)
        }
        val lines = ArrayList<String>()
        var more = false
        BufferedReader(InputStreamReader(Files.newInputStream(path), Charsets.UTF_8)).use { r ->
            while (true) {
                val l = r.readLine() ?: break
                if (l.indexOf('\u0000') >= 0) return FileResult(emptyList(), false, true, 0)
                lines.add(l)
                if (lines.size and 0xFFF == 0) tick()
            }
        }
        val out = ArrayList<SearchMatch>()
        var longLines = 0
        for ((idx, line) in lines.withIndex()) {
            if (idx and 0xFF == 0) tick()
            val ranges: List<IntRange>
            if (c.pattern != null) {
                if (line.length > req.maxLineChars) { longLines++; continue }
                val seq = BudgetCharSequence(line, req.regexBudgetPerLine, tick)
                ranges = try { regexRanges(c.pattern.matcher(seq)) } catch (_: RegexBudgetExceeded) { throw LineBudgetExceeded(idx + 1) }
            } else {
                ranges = literalRanges(line, c)
            }
            if (ranges.isEmpty()) continue
            val replaced = req.replacement?.let { rep ->
                if (c.pattern != null) {
                    try { replaceRegex(c.pattern, line, rep, req) } catch (_: RegexBudgetExceeded) { throw LineBudgetExceeded(idx + 1) }
                } else replaceRanges(line, ranges, rep)
            }
            val before = if (req.contextLines > 0) lines.subList(maxOf(0, idx - req.contextLines), idx).toList() else emptyList()
            val after = if (req.contextLines > 0) lines.subList(idx + 1, minOf(lines.size, idx + 1 + req.contextLines)).toList() else emptyList()
            for (rg in ranges) {
                if (out.size >= limit) { more = true; break }
                out.add(SearchMatch(idx + 1, rg.first, rg.last + 1, line, before, after, replaced))
            }
            if (more) break
        }
        return FileResult(out, more, false, longLines)
    }

    private fun regexRanges(m: Matcher): List<IntRange> {
        val out = ArrayList<IntRange>()
        while (m.find()) if (m.end() > m.start()) out.add(m.start() until m.end())
        return out
    }

    private fun literalRanges(line: String, c: Compiled): List<IntRange> {
        val needle = c.literal!!
        val out = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val i = line.indexOf(needle, from, ignoreCase = c.ignoreCase)
            if (i < 0) break
            val end = i + needle.length
            val ok = !c.wholeWord || ((i == 0 || !isWord(line[i - 1])) && (end >= line.length || !isWord(line[end])))
            if (ok) { out.add(i until end); from = end } else from = i + 1
        }
        return out
    }

    private fun isWord(ch: Char) = ch.isLetterOrDigit() || ch == '_'

    private fun replaceRanges(line: String, ranges: List<IntRange>, rep: String): String {
        val sb = StringBuilder()
        var at = 0
        for (r in ranges) { sb.append(line, at, r.first).append(rep); at = r.last + 1 }
        return sb.append(line, at, line.length).toString()
    }

    private fun replaceRegex(p: Pattern, line: String, rep: String, req: SearchRequest): String {
        val m = p.matcher(BudgetCharSequence(line, req.regexBudgetPerLine))
        val sb = StringBuffer()   // the StringBuffer overloads exist on every Android level we support
        while (m.find()) {
            if (m.end() == m.start()) continue
            m.appendReplacement(sb, rep)   // IllegalArgumentException for a bad $ group is handled by the caller
        }
        m.appendTail(sb)
        return sb.toString()
    }
}
