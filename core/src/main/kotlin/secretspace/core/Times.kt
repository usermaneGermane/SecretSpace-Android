package secretspace.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** Stored UTC timestamps as the PC shows them (history_ui.local_time and short_when). */
object Times {
    private val LONG = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH)
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

    /** "2026-11-26T20:20:38Z" (or with a fraction) in ``zone``; null when it is not one. */
    fun parse(stamp: String?, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime? {
        val text = stamp?.trim().orEmpty()
        if (!text.endsWith("Z")) return null
        return try {
            Instant.parse(text).atZone(zone)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** "26 Nov 2026, 21:20": an entry's last change, a version's date. */
    fun long(stamp: String?, zone: ZoneId = ZoneId.systemDefault()): String =
        parse(stamp, zone)?.format(LONG) ?: "unknown time"

    /** Short enough for a list row: the time for today, the day and month this year, else the month and year. */
    fun short(stamp: String?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val moment = parse(stamp, zone) ?: return ""
        val today = now.atZone(zone)
        return when {
            moment.toLocalDate() == today.toLocalDate() -> moment.format(TIME)
            moment.year == today.year -> moment.format(DAY)
            else -> moment.format(MONTH)
        }
    }
}
