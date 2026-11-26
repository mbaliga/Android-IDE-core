package dev.fonebrew.domain.gitcore.textmerge

/** How one conflict region is resolved. */
sealed class Choice {
    object Ours : Choice()
    object Theirs : Choice()
    /** The common ancestor's lines; only available when the region knows its base. */
    object Base : Choice()
    /** Both sides, in the given order. */
    class Both(val oursFirst: Boolean = true) : Choice()
    /** Hand-written text, as raw bytes (see [RawText]); build it with [ofText] for ordinary text. */
    class Custom(val raw: String) : Choice() {
        companion object { fun ofText(text: String) = Custom(RawText.fromUtf8Text(text)) }
    }
}

enum class ResolutionErrorKind { UNKNOWN_REGION, BASE_UNAVAILABLE }

class ResolutionException(val kind: ResolutionErrorKind, message: String) : RuntimeException(message)

/** Final text after applying choices; [unresolved] lists region indices that still carry markers. */
class Resolved(val raw: String, val unresolved: List<Int>) {
    val isComplete get() = unresolved.isEmpty()
    fun bytes(): ByteArray = RawText.toBytes(raw)
}

/** A marker-looking line left in a text: 1-based [line] and the marker char (`<`, `|`, `=`, `>`). */
data class LeftoverMarker(val line: Int, val marker: Char)

object Resolver {
    /**
     * Applies [choices] (region index to choice) over [segments]. A region with no choice stays as
     * conflict markers (rendered with [style]/[labels]) and is reported in [Resolved.unresolved],
     * so a half-resolved file round-trips through [ConflictMarkers.parse]. A choice for an index
     * that is not a region, or BASE where the base is unknown, is a typed [ResolutionException].
     */
    fun apply(
        segments: List<Segment>,
        choices: Map<Int, Choice>,
        style: MarkerStyle = MarkerStyle.MERGE,
        markerSize: Int = 7,
        oursLabel: String = "ours", baseLabel: String = "base", theirsLabel: String = "theirs",
    ): Resolved {
        val known = segments.filterIsInstance<Segment.Conflict>().map { it.region.index }.toSet()
        for (k in choices.keys) if (k !in known) throw ResolutionException(ResolutionErrorKind.UNKNOWN_REGION, "no conflict region $k")
        val chunks = ArrayList<Pair<String, Boolean>>()   // text, region used CRLF markers
        val unresolved = ArrayList<Int>()
        for (seg in segments) when (seg) {
            is Segment.Clean -> chunks.add(seg.lines.joinToString("") to false)
            is Segment.Conflict -> {
                val r = seg.region
                val c = choices[r.index]
                if (c == null) {
                    unresolved.add(r.index)
                    val sb = StringBuilder()
                    MarkerRenderer.renderRegion(sb, r, style, markerSize, oursLabel, baseLabel, theirsLabel)
                    chunks.add(sb.toString() to r.crlf)
                } else chunks.add(pick(r, c) to r.crlf)
            }
        }
        // a chunk that lacks its final newline gets one only when more text follows it
        val sb = StringBuilder()
        for ((i, ch) in chunks.withIndex()) {
            sb.append(ch.first)
            if (ch.first.isNotEmpty() && !ch.first.endsWith("\n") && chunks.drop(i + 1).any { it.first.isNotEmpty() }) {
                sb.append(if (ch.second || ch.first.endsWith("\r")) "\r\n" else "\n")
            }
        }
        return Resolved(sb.toString(), unresolved)
    }

    private fun joinLines(a: List<String>) = a.joinToString("")

    private fun pick(r: ConflictRegion, c: Choice): String = when (c) {
        Choice.Ours -> joinLines(r.ours)
        Choice.Theirs -> joinLines(r.theirs)
        Choice.Base -> joinLines(r.base ?: throw ResolutionException(ResolutionErrorKind.BASE_UNAVAILABLE, "region ${r.index} has no base to take"))
        is Choice.Both -> {
            val (x, y) = if (c.oursFirst) r.ours to r.theirs else r.theirs to r.ours
            val first = joinLines(x)
            val sep = if (first.isNotEmpty() && !first.endsWith("\n") && y.isNotEmpty()) (if (r.crlf) "\r\n" else "\n") else ""
            first + sep + joinLines(y)
        }
        is Choice.Custom -> c.raw
    }

    /** Every marker line still present in [raw]; empty means the text is free of conflict markers. */
    fun leftoverMarkers(raw: String, markerSize: Int = 7): List<LeftoverMarker> =
        ConflictMarkers.findMarkers(raw, markerSize).map { LeftoverMarker(it.first, it.second) }
}
