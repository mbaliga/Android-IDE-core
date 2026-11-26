package dev.fonebrew.domain.gitcore.textmerge

/** Marker layout, named as git names them. */
enum class MarkerStyle {
    /** `<<<<<<<` ours `=======` theirs `>>>>>>>`; overlapping edits are split into the smallest conflicts. */
    MERGE,
    /** Adds the common ancestor between `|||||||` and `=======`; conflicts are not split. */
    DIFF3,
    /** Like DIFF3, but lines both sides share at the start/end of a conflict are moved out of it. */
    ZDIFF3,
}

data class TextMergeOptions(
    val style: MarkerStyle = MarkerStyle.MERGE,
    val markerSize: Int = 7,
    val oursLabel: String = "ours",
    val baseLabel: String = "base",
    val theirsLabel: String = "theirs",
    /** Any single input larger than this is reported UNMERGEABLE (TOO_LARGE), never diffed. */
    val maxInputBytes: Int = 8 * 1024 * 1024,
) {
    init { require(markerSize in 1..64) { "markerSize must be 1..64" } }
}

/** A half-open run of lines `[start, end)` in one input (0-based). */
data class LineSpan(val start: Int, val end: Int) {
    val size get() = end - start
}

/**
 * One conflict. Lines are raw (see [RawText]) and keep their terminators. [base] is null when the
 * ancestor is not known for this region (merge-style markers, or a region split out of a larger
 * one); a UI must then not offer "take base".
 */
class ConflictRegion(
    val index: Int,
    val ours: List<String>,
    val theirs: List<String>,
    val base: List<String>?,
    val oursSpan: LineSpan?,
    val theirsSpan: LineSpan?,
    val baseSpan: LineSpan?,
    /** Marker lines of this region end in CRLF (git's rule: the lines before the conflict in both sides do). */
    val crlf: Boolean = false,
) {
    fun oursText() = RawText.toUtf8Text(ours.joinToString(""))
    fun theirsText() = RawText.toUtf8Text(theirs.joinToString(""))
    fun baseText() = base?.let { RawText.toUtf8Text(it.joinToString("")) }

    internal fun withIndex(i: Int) = ConflictRegion(i, ours, theirs, base, oursSpan, theirsSpan, baseSpan, crlf)
}

sealed class Segment {
    class Clean(val lines: List<String>) : Segment()
    class Conflict(val region: ConflictRegion) : Segment()
}

enum class UnmergeableReason { BINARY, TOO_LARGE }

class Unmergeable(val reason: UnmergeableReason, val detail: String)

/**
 * The outcome of a three-way text merge: ordered [segments] (clean runs and conflict regions), or
 * an [unmergeable] verdict with no segments (binary or oversized input is never mangled).
 */
class TextMergeResult internal constructor(
    val segments: List<Segment>,
    val unmergeable: Unmergeable?,
    val options: TextMergeOptions,
) {
    val regions: List<ConflictRegion> get() = segments.filterIsInstance<Segment.Conflict>().map { it.region }
    val conflictCount: Int get() = regions.size
    val isUnmergeable: Boolean get() = unmergeable != null
    val isClean: Boolean get() = unmergeable == null && regions.isEmpty()

    /** The merged bytes, with conflict markers for every region. Throws when unmergeable. */
    fun render(): ByteArray = RawText.toBytes(renderRaw())

    fun renderRaw(): String {
        check(unmergeable == null) { "unmergeable input has no merged text" }
        return MarkerRenderer.render(segments, options.style, options.markerSize, options.oursLabel, options.baseLabel, options.theirsLabel)
    }
}

/** What went wrong reading conflict markers back out of a file. Always typed; the parser never guesses. */
enum class MarkerErrorKind {
    /** A start marker inside an open conflict. */
    NESTED_START,
    /** End of file with a conflict still open. */
    UNTERMINATED,
    /** `|||||||` or `>>>>>>>` with no open conflict. */
    STRAY_MARKER,
    /** Markers in an impossible order (second base, second separator, base after separator, end before separator). */
    OUT_OF_ORDER,
}

class MarkerParseError(val kind: MarkerErrorKind, val line: Int, message: String) : RuntimeException(message)

/** Labels and layout recovered from a marked-up file. */
class ParsedMarkers(
    val segments: List<Segment>,
    val markerSize: Int,
    val oursLabel: String?,
    val baseLabel: String?,
    val theirsLabel: String?,
) {
    val regions: List<ConflictRegion> get() = segments.filterIsInstance<Segment.Conflict>().map { it.region }
}
