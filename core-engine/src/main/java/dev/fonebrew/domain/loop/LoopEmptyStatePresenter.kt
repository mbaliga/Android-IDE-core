package dev.fonebrew.domain.loop

/**
 * Whether [dev.fonebrew.ui.loops.LoopRoom]'s first-run empty-state card should show
 * (asoc-reachability audit item 4, 2026-09-15): no saved loops AND the canvas draft itself
 * carries no nodes. Both conditions, not either alone — a user with saved loops but an
 * emptied-out draft (every node deleted mid-edit) isn't a first-run user, and a canvas that
 * still has the seeded starter pattern on it isn't empty either; the card is honest onboarding,
 * not a nag that reappears whenever the draft happens to be momentarily bare.
 *
 * [savedLoopCount] is caller-filtered: [dev.fonebrew.ui.loops] reserves one LoopStore id
 * (`AUTOSAVE_LOOP_ID`, LoopRoom.kt) for its own debounced draft autosave — that slot is UI-
 * private bookkeeping, never a loop the user asked for, so it must not count toward "has the
 * user saved anything." Pure decision, no Compose/Context — LoopRoom is the one call site
 * (owner-verified: no device/emulator in this build).
 */
object LoopEmptyStatePresenter {
    fun shouldShow(savedLoopCount: Int, canvasNodeCount: Int): Boolean =
        savedLoopCount <= 0 && canvasNodeCount <= 0
}
