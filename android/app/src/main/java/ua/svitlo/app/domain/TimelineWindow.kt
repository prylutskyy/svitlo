package ua.svitlo.app.domain

import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Calendar days in Kyiv, including 23/25 hour days. No network work is needed to advance the window. */
data class TimelineWindow(val start: Instant, val end: Instant) {
    fun label(at: Instant): String {
        val local = at.atZone(KYIV)
        return if (at == end && local.toLocalTime() == java.time.LocalTime.MIDNIGHT &&
            local.toLocalDate() == start.atZone(KYIV).toLocalDate().plusDays(1)) "24:00"
        else local.format(DateTimeFormatter.ofPattern(if (end > start.atZone(KYIV).toLocalDate().plusDays(1).atStartOfDay(KYIV).toInstant() ||
            local.toLocalDate() != start.atZone(KYIV).toLocalDate()) "dd.MM HH:mm" else "HH:mm"))
    }
    fun ticks(): List<Instant> = if (end > start.atZone(KYIV).toLocalDate().plusDays(1).atStartOfDay(KYIV).toInstant())
        listOf(start, end) // Dates at both ends remain readable in narrow notifications and at large font sizes.
    else (0..2).map { start.plusMillis(Duration.between(start, end).toMillis() * it / 2) }
    fun midnights(): List<Instant> = generateSequence(start.atZone(KYIV).toLocalDate().plusDays(1)) { it.plusDays(1) }
        .map { it.atStartOfDay(KYIV).toInstant() }.takeWhile { it < end }.toList()
}

fun todayWindow(now: Instant) = TimelineWindow(
    now.atZone(KYIV).withMinute(0).withSecond(0).withNano(0).toInstant(),
    now.atZone(KYIV).toLocalDate().plusDays(1).atStartOfDay(KYIV).toInstant())

fun Schedule.hasTomorrow(now: Instant): Boolean {
    val tomorrow = now.atZone(KYIV).toLocalDate().plusDays(1)
    return days.any { it.date == tomorrow.toString() && it.slots.isNotEmpty() } ||
        currentOutage?.estimatedEnd?.let { Instant.ofEpochMilli(it) > tomorrow.atStartOfDay(KYIV).toInstant() } == true
}

fun remainingTime(end: Instant, now: Instant): String {
    val minutes = ((Duration.between(now, end).seconds + 59) / 60).coerceAtLeast(0)
    return listOfNotNull((minutes / 1440).takeIf { it > 0 }?.let { "$it д" },
        (minutes % 1440 / 60).takeIf { it > 0 }?.let { "$it год" },
        (minutes % 60).takeIf { it > 0 || minutes == 0L }?.let { "$it хв" }).joinToString(" ")
}

/** Fixed elapsed-time scale: one hour of context, eleven hours ahead, including DST changes. */
fun notificationWindow(now: Instant) = TimelineWindow(now.minusSeconds(3600), now.plusSeconds(11 * 3600))
