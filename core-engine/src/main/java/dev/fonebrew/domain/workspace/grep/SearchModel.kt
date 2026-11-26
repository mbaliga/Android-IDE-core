package dev.fonebrew.domain.workspace.grep

/** What to look for and how far to go. All limits are explicit so a search is bounded by construction. */
data class SearchRequest(
    val pattern: String,
    val regex: Boolean = false,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    /** gitignore-style globs over root-relative paths: no `/` = match the name at any depth, `/` = anchored. Empty = everything. */
    val includeGlobs: List<String> = emptyList(),
    val excludeGlobs: List<String> = emptyList(),
    val contextLines: Int = 0,
    /** Files larger than this are skipped and reported, never half-read. */
    val maxFileBytes: Long = 2L * 1024 * 1024,
    val maxMatches: Int = 5_000,
    val maxMatchesPerFile: Int = 500,
    val maxFiles: Int = 200_000,
    /** Regex only: character reads allowed per line before the search aborts with [SearchOutcome.Timeout]. */
    val regexBudgetPerLine: Long = 2_000_000L,
    /** Lines longer than this are not regex-searched (minified bundles); counted in [SearchStats.longLinesSkipped]. */
    val maxLineChars: Int = 20_000,
    /**
     * Patterns the [RegexLint] screen flags (nested unbounded repeats, repeated alternations) are
     * refused with [SearchOutcome.RiskyPattern] unless this is set; the per-line budget still applies.
     */
    val allowRiskyRegex: Boolean = false,
    /** Non-null = also compute a replace PREVIEW per matched line. Nothing is ever written. */
    val replacement: String? = null,
    /** Restrict to these root ids; null = every root in the workspace. */
    val rootIds: Set<String>? = null,
)

data class SearchMatch(
    /** 1-based. */
    val line: Int,
    /** 0-based column of the match start within [lineText], and the exclusive end. */
    val startCol: Int,
    val endCol: Int,
    val lineText: String,
    val before: List<String>,
    val after: List<String>,
    /** The whole line with every match on it replaced; only when the request carried a replacement. */
    val replacedLine: String?,
)

enum class SkipReason { BINARY, TOO_LARGE, UNREADABLE }

sealed interface SearchOutcome {
    object Completed : SearchOutcome
    /** Stopped at [SearchRequest.maxMatches]; more exist. */
    data class ResultLimit(val maxMatches: Int) : SearchOutcome
    /** The walk hit [SearchRequest.maxFiles] or a directory cap; files beyond were not examined. */
    object FileLimit : SearchOutcome
    /** A regex exceeded its per-line budget on this file and line: the pattern is too expensive, the search stopped. */
    data class Timeout(val rootId: String, val rel: String, val line: Int) : SearchOutcome
    data class InvalidPattern(val message: String) : SearchOutcome
    /** The regex has a shape that can backtrack catastrophically; nothing was searched. Re-run with `allowRiskyRegex`. */
    data class RiskyPattern(val reason: String) : SearchOutcome
}

data class SearchStats(
    val filesScanned: Int,
    val filesWithMatches: Int,
    val matches: Int,
    val skippedBinary: Int,
    val skippedTooLarge: Int,
    val skippedUnreadable: Int,
    val longLinesSkipped: Int,
)

sealed interface SearchEvent {
    data class FileHit(val rootId: String, val rel: String, val matches: List<SearchMatch>, val moreInFile: Boolean) : SearchEvent
    data class Skipped(val rootId: String, val rel: String, val reason: SkipReason) : SearchEvent
    /** Always the last event of a search that was not cancelled. */
    data class Done(val outcome: SearchOutcome, val stats: SearchStats) : SearchEvent
}
