package dev.fonebrew.domain.tasks

/** [text] is the relative label the To-do row shows; [overdue] drives the colorblind-safe
 *  encoding (§1.4/CORE_PHASES.md: never red — high-luminance violet + a filled-alert glyph
 *  + this label, decided by the caller). */
data class TaskDueLabel(val text: String, val overdue: Boolean)

object TaskDue {
    private const val HOUR_MS = 60 * 60 * 1000L
    private const val DAY_MS = 24 * HOUR_MS

    /** Relative due text for a row, given [dueAt] and the current time [now]. Both epoch-ms. */
    fun label(dueAt: Long, now: Long): TaskDueLabel {
        val diff = dueAt - now
        if (diff < 0) return TaskDueLabel("overdue", overdue = true)
        if (diff < HOUR_MS) return TaskDueLabel("due soon", overdue = false)
        val days = diff / DAY_MS
        return if (days < 1) {
            TaskDueLabel("today", overdue = false)
        } else {
            TaskDueLabel("in ${days}d", overdue = false)
        }
    }
}
