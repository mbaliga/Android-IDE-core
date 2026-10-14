package dev.fonebrew.watch.core

/**
 * "How old is this?" in words, for every place the wrist shows data the phone sent *earlier* (the
 * task list, a loop glance, the last reply). Age is the honest companion to a cached value: the
 * watch never presents something as live that it merely remembers.
 *
 * Pure and clock-free — callers pass `nowMs`. Coarse on purpose (a wrist glance, not a log line).
 */
object RelativeTime {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /**
     * `just now` (under a minute, or a timestamp in the future because the two clocks disagree —
     * we do not pretend to know better), then `N min ago`, `N h ago`, `N d ago`.
     */
    fun ago(thenMs: Long, nowMs: Long): String {
        val age = nowMs - thenMs
        return when {
            age < MINUTE -> "just now"
            age < HOUR -> "${age / MINUTE} min ago"
            age < DAY -> "${age / HOUR} h ago"
            else -> "${age / DAY} d ago"
        }
    }

    /** Whole seconds between two instants, floored at 0 (for "waiting 12 s" counters). */
    fun elapsedSeconds(sinceMs: Long, nowMs: Long): Long = ((nowMs - sinceMs) / 1_000L).coerceAtLeast(0L)
}
