package dev.aarso.domain.library

/**
 * Bulk operations over the library (Conversations / image gallery / project lists): the
 * multi-select surface that lets a user star, move, export, archive or delete many items
 * at once. Pure domain (no Android, no store) — this file is the *selection model*, the
 * *action taxonomy*, the *honest result*, and the *destructive-undo window*. The UI binds
 * to these; the data layer performs the actual per-item work and reports back a result.
 *
 * Two legibility commitments encoded here, not left to the UI:
 *  - **"Select all" is scoped to the current view, never a surprise global select.** The
 *    user only ever selects what they can see — see [BulkSelection.selectAll].
 *  - **Results are honest about partial failure** — "48 exported, 2 failed", not a silent
 *    success — see [BulkResult.summary].
 */

/**
 * The set of currently-selected item ids. Immutable: every operation returns a new
 * [BulkSelection], so the UI state holder can hold one value and replace it. Ids are the
 * stable item handles (message-tree node ids, image ids, …); their meaning is the caller's.
 */
data class BulkSelection(val selected: Set<String> = emptySet()) {

    /** True when nothing is selected — the toolbar/affordance hides on empty. */
    val isEmpty: Boolean get() = selected.isEmpty()

    /** How many items are selected — for the "N selected" header. */
    val count: Int get() = selected.size

    /** Toggle one id: remove it if present, otherwise add it. */
    fun toggle(id: String): BulkSelection =
        if (id in selected) BulkSelection(selected - id)
        else BulkSelection(selected + id)

    /**
     * Select every id **in the current view**. This is deliberately scoped to
     * [idsInView] — the ids the user can actually see right now (the filtered/searched
     * page) — so "Select all" can never quietly select items off-screen or across the
     * whole library. The result is exactly the view's ids (any prior selection from other
     * views is replaced, keeping the affordance honest about its scope).
     */
    fun selectAll(idsInView: List<String>): BulkSelection = BulkSelection(idsInView.toSet())

    /** Clear the selection (leave multi-select mode / "Done"). */
    fun clear(): BulkSelection = BulkSelection(emptySet())
}

/**
 * The actions a bulk selection can be subjected to. [destructive] marks the ones that
 * need a confirm + an [UndoWindow] before they take irreversible effect — today only
 * [DELETE]. MOVE_TO_PROJECT, EXPORT, ARCHIVE and (un)starring are reversible/benign.
 */
enum class BulkAction(val destructive: Boolean) {
    STAR(false),
    UNSTAR(false),
    MOVE_TO_PROJECT(false),
    EXPORT(false),
    ARCHIVE(false),
    DELETE(true),
}

/**
 * The outcome of running a [BulkAction] over a selection, reported back from the data
 * layer. Honest by construction: it carries how many items were [attempted], how many
 * [succeeded], and the [failedIds] — so the UI can say "48 exported, 2 failed" and offer
 * the failures for retry, never a misleading blanket "done".
 */
data class BulkResult(
    val attempted: Int,
    val succeeded: Int,
    val failedIds: List<String> = emptyList(),
) {
    /** How many items failed. */
    val failed: Int get() = failedIds.size

    /** True when some — but not necessarily all — items failed. */
    val partialFailure: Boolean get() = failedIds.isNotEmpty()

    /**
     * A short human line for the snackbar/toast. Honest about partial failure:
     *  - all succeeded  → "48 done"
     *  - some failed    → "46 done, 2 failed"
     *  - nothing tried  → "Nothing to do"
     */
    fun summary(): String {
        if (attempted == 0) return "Nothing to do"
        return if (partialFailure) "$succeeded done, $failed failed" else "$succeeded done"
    }
}

/**
 * The undo window opened after a destructive [BulkAction] (a [DELETE]). The deletion is
 * staged; until [deadlineMillis] the user can undo it and the items return untouched.
 * Pure and deterministic — there is no clock here; the caller passes `nowMillis` in, so
 * this stays JVM-testable and free of side effects.
 */
data class UndoWindow(
    val action: BulkAction,
    val affectedIds: List<String>,
    /** Absolute wall-clock millis after which the action commits and undo is gone. */
    val deadlineMillis: Long,
) {
    /** True while the undo affordance should still be offered (now is before the deadline). */
    fun isOpen(nowMillis: Long): Boolean = nowMillis < deadlineMillis
}
