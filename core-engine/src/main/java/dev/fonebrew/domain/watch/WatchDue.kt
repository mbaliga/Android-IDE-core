package dev.fonebrew.domain.watch

/** [text] is the relative label the Watch row's days-remaining chip shows; [overdue] drives
 *  the colorblind-safe encoding (§1.4/CORE_PHASES.md: never red — high-luminance violet + a
 *  filled-alert glyph + this label). [daysRemaining] lets the chip scale luminance toward
 *  the due date without inventing a second colour axis. */
data class WatchDueLabel(val text: String, val overdue: Boolean, val daysRemaining: Long)

object WatchDue {
    private const val HOUR_MS = 60 * 60 * 1000L
    private const val DAY_MS = 24 * HOUR_MS

    /** Relative due text for a row, given [dueAt] and the current time [now]. Both epoch-ms. */
    fun label(dueAt: Long, now: Long): WatchDueLabel {
        val diff = dueAt - now
        val days = diff / DAY_MS
        if (diff < 0) return WatchDueLabel("overdue", overdue = true, daysRemaining = days)
        if (diff < HOUR_MS) return WatchDueLabel("due soon", overdue = false, daysRemaining = 0)
        return if (days < 1) {
            WatchDueLabel("today", overdue = false, daysRemaining = 0)
        } else {
            WatchDueLabel("in ${days}d", overdue = false, daysRemaining = days)
        }
    }
}
