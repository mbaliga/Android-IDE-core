package dev.fonebrew.domain.editor.diff

import dev.fonebrew.domain.diff.Decision
import dev.fonebrew.domain.diff.ReviewSession

// ---- context folding -------------------------------------------------------------------------------

/** How much of each fold has been revealed from its top (continuing the previous hunk) and bottom (leading into the next). */
class FoldState private constructor(private val top: Map<String, Int>, private val bottom: Map<String, Int>) {
    fun revealedTop(id: String) = top[id] ?: 0
    fun revealedBottom(id: String) = bottom[id] ?: 0

    fun expand(id: String, step: Int, direction: Direction): FoldState {
        require(step > 0)
        return when (direction) {
            Direction.DOWN -> FoldState(top + (id to revealedTop(id) + step), bottom)
            Direction.UP -> FoldState(top, bottom + (id to revealedBottom(id) + step))
            Direction.ALL -> FoldState(top + (id to Int.MAX_VALUE / 2), bottom)
        }
    }

    enum class Direction { UP, DOWN, ALL }

    companion object { val EMPTY = FoldState(emptyMap(), emptyMap()) }
}

/** An entry of a folded view: a visible row (by index) or a collapsed run of unchanged rows. */
sealed interface FoldItem {
    data class Show(val row: Int) : FoldItem

    /** Rows [first]..[last] (inclusive) are hidden; [hiddenCount] is `last - first + 1`. */
    data class Fold(val id: String, val first: Int, val last: Int) : FoldItem {
        val hiddenCount: Int get() = last - first + 1
    }
}

object FoldPlan {
    /** A fold hiding fewer rows than this is just shown (a fold control for one line saves nothing). */
    const val MIN_FOLD = 2

    /**
     * [changed] flags each row; rows farther than [context] from any changed row fold. [idFor] gives a
     * fold its stable id from its first row; [state] says how much has been expanded.
     */
    fun plan(changed: List<Boolean>, context: Int, state: FoldState, idFor: (Int) -> String): List<FoldItem> {
        val n = changed.size
        val visible = BooleanArray(n)
        var lastChange = -1_000_000
        for (i in 0 until n) { if (changed[i]) lastChange = i; if (i - lastChange <= context) visible[i] = true }
        var nextChange = Int.MAX_VALUE / 2
        for (i in n - 1 downTo 0) { if (changed[i]) nextChange = i; if (nextChange - i <= context) visible[i] = true }
        val out = ArrayList<FoldItem>()
        var i = 0
        while (i < n) {
            if (visible[i]) { out += FoldItem.Show(i); i++; continue }
            var j = i
            while (j < n && !visible[j]) j++
            val a = i; val b = j - 1
            val count = b - a + 1
            val id = idFor(a)
            val t = state.revealedTop(id).coerceAtMost(count)
            val u = state.revealedBottom(id).coerceAtMost(count)
            if (count < MIN_FOLD || t + u >= count) { for (r in a..b) out += FoldItem.Show(r) }
            else {
                for (r in a until a + t) out += FoldItem.Show(r)
                out += FoldItem.Fold(id, a + t, b - u)
                for (r in b - u + 1..b) out += FoldItem.Show(r)
            }
            i = j
        }
        return out
    }
}

fun DiffModel.foldedUnified(context: Int = 3, state: FoldState = FoldState.EMPTY): List<FoldItem> =
    FoldPlan.plan(unified.map { it.hunkId != null }, context, state) { r -> "f" + (unified[r].oldNo ?: unified[r].newNo) }

fun DiffModel.foldedSide(context: Int = 3, state: FoldState = FoldState.EMPTY): List<FoldItem> =
    FoldPlan.plan(side.map { it.hunkId != null }, context, state) { r -> "f" + (side[r].left?.lineNo ?: side[r].right?.lineNo) }

// ---- hunk navigation ---------------------------------------------------------------------------------

data class NavResult(val hunk: DiffHunk, val wrapped: Boolean)

object HunkNavigator {
    /** First hunk starting after unified row [fromRow]; wraps to the first when [wrap]. Null if there are none. */
    fun next(model: DiffModel, fromRow: Int, wrap: Boolean = true): NavResult? {
        model.hunks.firstOrNull { it.firstRow > fromRow }?.let { return NavResult(it, false) }
        return if (wrap) model.hunks.firstOrNull()?.let { NavResult(it, true) } else null
    }

    /** Last hunk that ends before row [fromRow] (so from inside a hunk it goes to the one before it). */
    fun previous(model: DiffModel, fromRow: Int, wrap: Boolean = true): NavResult? {
        model.hunks.lastOrNull { it.lastRow < fromRow }?.let { return NavResult(it, false) }
        return if (wrap) model.hunks.lastOrNull()?.let { NavResult(it, true) } else null
    }
}

// ---- per-hunk intents ---------------------------------------------------------------------------------

/**
 * What the user asked to do with one hunk. STAGE and DISCARD map onto [Decision.APPROVED] and
 * [Decision.REJECTED] of a [ReviewSession] (a proposal not yet applied: discarding loses nothing).
 * REVERT is the same REJECTED outcome applied to text the user already has, so it is [destructive]
 * and a surface must confirm it.
 */
enum class HunkAction(val destructive: Boolean, val label: String) {
    STAGE(false, "Stage hunk"), DISCARD(false, "Discard hunk"), REVERT(true, "Revert hunk"),
}

data class HunkIntent(val hunkId: String, val action: HunkAction)

object HunkIntents {
    fun decisionOf(a: HunkAction): Decision = if (a == HunkAction.STAGE) Decision.APPROVED else Decision.REJECTED

    /** Push the intents into [session] (hunk i of the model is hunk i of the session). Returns ids that matched no hunk. */
    fun applyTo(session: ReviewSession, model: DiffModel, intents: List<HunkIntent>): List<String> {
        require(model.matches(session)) { "model and session describe different changes" }
        val unknown = ArrayList<String>()
        for (it in intents) {
            val idx = model.hunkIndex(it.hunkId)
            if (idx < 0) unknown += it.hunkId else session.decide(idx, decisionOf(it.action))
        }
        return unknown
    }

    /** The text after the intents, with the final newline tracked (see [DiffModel.apply]). */
    fun textAfter(model: DiffModel, intents: List<HunkIntent>): String =
        model.apply(intents.associate { it.hunkId to decisionOf(it.action) })
}

// ---- three-column display (ours / base / theirs) ---------------------------------------------------------

/**
 * The shape a conflict model must be adaptable to for display. This lane defines it and renders it;
 * it computes no merge. A Stable region is identical in all three columns; a Conflict carries each
 * side's lines (base is null for a two-way conflict).
 */
sealed interface ThreeWayRegion {
    data class Stable(val lines: List<String>) : ThreeWayRegion
    data class Conflict(val id: String, val ours: List<String>, val base: List<String>?, val theirs: List<String>) : ThreeWayRegion
}

fun interface ThreeWaySource { fun regions(): List<ThreeWayRegion> }

data class LineCell(val lineNo: Int, val text: String)

data class ThreeColumnRow(
    val ours: LineCell?, val base: LineCell?, val theirs: LineCell?,
    val conflictId: String?,
) {
    val conflicted: Boolean get() = conflictId != null
    /** Glyph + label carry the state; a UI may add hue on top, never instead. */
    val marker: String get() = if (conflicted) "!" else " "
    val stateLabel: String get() = if (conflicted) "conflict" else "unchanged"
}

object ThreeWayDisplay {
    val COLUMN_LABELS = Triple("Ours", "Base", "Theirs")

    fun rows(source: ThreeWaySource): List<ThreeColumnRow> {
        val out = ArrayList<ThreeColumnRow>()
        var o = 0; var b = 0; var t = 0
        for (r in source.regions()) when (r) {
            is ThreeWayRegion.Stable -> for (l in r.lines) {
                o++; b++; t++
                out += ThreeColumnRow(LineCell(o, l), LineCell(b, l), LineCell(t, l), null)
            }
            is ThreeWayRegion.Conflict -> {
                val bl = r.base
                val h = maxOf(r.ours.size, bl?.size ?: 0, r.theirs.size)
                for (k in 0 until h) {
                    out += ThreeColumnRow(
                        r.ours.getOrNull(k)?.let { LineCell(++o, it) },
                        bl?.getOrNull(k)?.let { LineCell(++b, it) },
                        r.theirs.getOrNull(k)?.let { LineCell(++t, it) },
                        r.id,
                    )
                }
            }
        }
        return out
    }
}
