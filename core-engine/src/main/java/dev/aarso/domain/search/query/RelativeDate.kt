package dev.aarso.domain.search.query

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Resolves `before:`/`after:`/`during:` date expressions (Doc §6.1: `date = iso_date |
 * relative`) — ISO dates, `today`/`yesterday`/`last-week`, and relative offsets (`-7d`).
 *
 * The DST/timezone-safety requirement (Doc §13 test 10) is why this goes through
 * `java.time.LocalDate`/[ZoneId] rather than raw millisecond arithmetic: `-7d` is computed as
 * "the calendar date 7 days before today, in this zone" via [LocalDate.minusDays], then
 * converted to an instant via [LocalDate.atStartOfDay] — both are wall-clock/calendar operations
 * that stay correct across a DST transition. Naively subtracting `7 * 86_400_000L` milliseconds
 * would land on the wrong wall-clock hour whenever a DST change falls inside that window.
 */
object RelativeDate {

    /** An inclusive-start, exclusive-end epoch-millis window. */
    data class Range(val startInclusiveMillis: Long, val endExclusiveMillis: Long) {
        operator fun contains(epochMillis: Long): Boolean = epochMillis in startInclusiveMillis until endExclusiveMillis
    }

    private val RELATIVE_OFFSET = Regex("^-(\\d+)([dwm])$")
    private val ISO_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    /** The whole calendar day/week the expression denotes (for `during:`), or `null` if [raw]
     *  isn't a recognized date expression. */
    fun resolveRange(raw: String, nowMillis: Long, zone: ZoneId): Range? {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return when {
            raw.equals("today", ignoreCase = true) -> dayRange(today, zone)
            raw.equals("yesterday", ignoreCase = true) -> dayRange(today.minusDays(1), zone)
            raw.equals("last-week", ignoreCase = true) -> {
                val startOfThisWeek = today.with(DayOfWeek.MONDAY)
                val startOfLastWeek = startOfThisWeek.minusWeeks(1)
                Range(
                    startOfLastWeek.atStartOfDay(zone).toInstant().toEpochMilli(),
                    startOfThisWeek.atStartOfDay(zone).toInstant().toEpochMilli(),
                )
            }
            ISO_DATE.matches(raw) -> parseIsoDate(raw)?.let { dayRange(it, zone) }
            else -> RELATIVE_OFFSET.matchEntire(raw)?.let { m ->
                val amount = m.groupValues[1].toLong()
                val point = when (m.groupValues[2]) {
                    "d" -> today.minusDays(amount)
                    "w" -> today.minusWeeks(amount)
                    "m" -> today.minusMonths(amount)
                    else -> return null
                }
                dayRange(point, zone)
            }
        }
    }

    /** A single threshold instant for `before:`/`after:` — the start of the referenced day. */
    fun resolvePoint(raw: String, nowMillis: Long, zone: ZoneId): Long? =
        resolveRange(raw, nowMillis, zone)?.startInclusiveMillis

    private fun parseIsoDate(raw: String): LocalDate? =
        try {
            LocalDate.parse(raw)
        } catch (_: DateTimeParseException) {
            null
        }

    private fun dayRange(date: LocalDate, zone: ZoneId): Range {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return Range(start, end)
    }
}
