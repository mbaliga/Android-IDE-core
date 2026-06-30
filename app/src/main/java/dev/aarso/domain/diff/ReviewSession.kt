package dev.aarso.domain.diff

/**
 * Review-first editing (docs/build-plan.md, Sprint 6): the primary edit path on a phone is
 * "the agent proposes, you read the material, you apply." A [ReviewSession] takes one file's
 * proposed change, splits it into minimal **hunks**, and lets you approve or reject each one
 * independently — then applies *only* the approved hunks to produce the resulting text. A
 * rejected hunk leaves that region exactly as it was; nothing is ever applied silently.
 *
 * Hunks are computed at zero context so each is an exact, separately-decidable change region
 * (the display layer can re-render with surrounding context — that is a UI concern). Pure
 * Kotlin over [LineDiff]; JVM-tested.
 */

enum class Decision { PENDING, APPROVED, REJECTED }

class ReviewSession(
    val path: String,
    val oldText: String,
    val newText: String,
) {
    /** The minimal, separately-decidable change regions. */
    val hunks: List<LineDiff.Hunk> = LineDiff.diff(oldText, newText, context = 0)

    private val decisions = MutableList(hunks.size) { Decision.PENDING }

    fun decision(index: Int): Decision = decisions[index]

    fun decide(index: Int, decision: Decision) { decisions[index] = decision }

    fun approveAll() { for (i in decisions.indices) decisions[i] = Decision.APPROVED }
    fun rejectAll() { for (i in decisions.indices) decisions[i] = Decision.REJECTED }

    /** True when no hunk is still pending (every region has an explicit decision). */
    val settled: Boolean get() = decisions.none { it == Decision.PENDING }

    val anyApproved: Boolean get() = decisions.any { it == Decision.APPROVED }

    /** Indices of approved hunks, in order. */
    fun approvedIndices(): List<Int> = decisions.indices.filter { decisions[it] == Decision.APPROVED }

    /**
     * The resulting text after applying only the approved hunks. Pending hunks are treated as
     * not-applied (rejected) so this is always well-defined; gate on [settled] in the UI if you
     * want every hunk decided first.
     */
    fun applied(): String {
        val oldLines = splitLines(oldText)
        val out = ArrayList<String>(oldLines.size)
        // Track position in BOTH original coordinates. Hunks at context=0 only anchor on the
        // side they touch (an insertion has no old line, a deletion no new line), so we anchor
        // each hunk by whichever side is present and count the unchanged gap before it. Position
        // tracking is independent of the approve/reject decision (which only affects emission).
        var oldPos = 0
        var newPos = 0

        for ((i, h) in hunks.withIndex()) {
            val gap = if (h.oldCount > 0) (h.oldStart - 1) - oldPos else (h.newStart - 1) - newPos
            repeat(gap.coerceAtLeast(0)) {
                if (oldPos < oldLines.size) out.add(oldLines[oldPos])
                oldPos++; newPos++
            }
            if (decisions[i] == Decision.APPROVED) {
                h.lines.forEach { line -> if (line is LineDiff.Line.Add) out.add(line.text) }
            } else {
                repeat(h.oldCount) { if (oldPos + it < oldLines.size) out.add(oldLines[oldPos + it]) }
            }
            oldPos += h.oldCount
            newPos += h.newCount
        }
        while (oldPos < oldLines.size) { out.add(oldLines[oldPos]); oldPos++ }
        return out.joinToString("\n")
    }

    /** The change reduced to only the approved hunks (for committing a partial apply). */
    fun approvedChange(): FileChange = FileChange(path, oldText, applied())

    // Mirror LineDiff's line handling: drop a single trailing newline, split on '\n'.
    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val t = if (text.endsWith("\n")) text.dropLast(1) else text
        return t.split("\n")
    }
}

/**
 * Aggregates per-file [ReviewSession]s over a whole [ChangeSet], so a multi-file proposal is
 * reviewed hunk-by-hunk and reduced to a [ChangeSet] of only what was approved.
 */
class ChangeSetReview(changeSet: ChangeSet) {
    val sessions: List<ReviewSession> =
        changeSet.effective.map { ReviewSession(it.path, it.oldText, it.newText) }

    val settled: Boolean get() = sessions.all { it.settled }
    val anyApproved: Boolean get() = sessions.any { it.anyApproved }

    fun approveAll() = sessions.forEach { it.approveAll() }
    fun rejectAll() = sessions.forEach { it.rejectAll() }

    /** A change set containing only the approved hunks of each file (no-op files dropped). */
    fun approvedChangeSet(): ChangeSet =
        ChangeSet(sessions.map { it.approvedChange() }.filterNot { it.isNoop })
}
