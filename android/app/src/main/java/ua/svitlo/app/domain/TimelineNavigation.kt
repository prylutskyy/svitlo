package ua.svitlo.app.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Inspect full source intervals, rather than reporting a viewport edge as an outage boundary. */
fun Schedule?.inspectionSpans(window: TimelineWindow, now: Instant): List<ChartSpan> {
    val dates = this?.days.orEmpty().mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
    val first = dates.minOrNull()?.atStartOfDay(KYIV)?.toInstant() ?: window.start
    val last = dates.maxOrNull()?.plusDays(1)?.atStartOfDay(KYIV)?.toInstant() ?: window.end
    val eventStarts=this?.reportedOutages.orEmpty().mapNotNull {it.startedAt}.minOrNull()?.let(Instant::ofEpochMilli) ?: window.start
    val eventEnds=this?.reportedOutages.orEmpty().mapNotNull {it.estimatedEnd}.maxOrNull()?.let(Instant::ofEpochMilli) ?: window.end
    val start = listOf(eventStarts,window.start,first,this?.currentOutage?.startedAt?.let(Instant::ofEpochMilli) ?: window.start).minOrNull()!!
    val end = listOf(eventEnds,window.end,last,this?.currentOutage?.estimatedEnd?.let(Instant::ofEpochMilli) ?: window.end).maxOrNull()!!
    return chartSpans(start, end, now)
}

object TimelineNavigation {
    const val HOURS = 12L
    fun coalesced(spans: List<ChartSpan>): List<ChartSpan> {
        val result = mutableListOf<ChartSpan>()
        spans.forEach { span ->
            val last = result.lastOrNull()
            if (last?.state == span.state && last.end == span.start) result[result.lastIndex] = last.copy(end = span.end)
            else result += span
        }
        return result
    }
    fun around(at: Instant) = TimelineWindow(at.minusSeconds(3600), at.plusSeconds(11 * 3600))
    fun next(spans: List<ChartSpan>, window: TimelineWindow) = spans.firstOrNull {
        it.state in OUTAGE_STATES && it.start >= window.end
    }
    fun continuing(spans: List<ChartSpan>, window: TimelineWindow) = spans.firstOrNull {
        it.state in OUTAGE_STATES && it.start < window.end && it.end > window.end
    }
    fun ticks(window: TimelineWindow): List<Instant> {
        val start = window.start.atZone(KYIV).withMinute(0).withSecond(0).withNano(0)
        val hours = Duration.between(window.start,window.end).toHours()
        val step = if (hours > 24) 12 else if (hours > 12) 6 else 3
        return (0L..(hours+1).coerceAtMost(336)).map { start.plusHours(it).toInstant() }
            .filter { it >= window.start && it <= window.end && it.atZone(KYIV).hour % step == 0 }
    }
    fun at(spans: List<ChartSpan>, instant: Instant) = spans.firstOrNull { instant >= it.start && instant < it.end }
    fun day(at: Instant, now: Instant): String = when (at.atZone(KYIV).toLocalDate()) {
        now.atZone(KYIV).toLocalDate() -> "Сьогодні"
        now.atZone(KYIV).toLocalDate().plusDays(1) -> "Завтра"
        else -> at.atZone(KYIV).format(DateTimeFormatter.ofPattern("dd.MM"))
    }
    fun range(span: ChartSpan, now: Instant): String {
        val clock = DateTimeFormatter.ofPattern("HH:mm")
        val a = span.start.atZone(KYIV); val b = span.end.atZone(KYIV)
        return "${day(span.start,now)} ${a.format(clock)} — " +
            (if (a.toLocalDate() == b.toLocalDate()) "" else "${day(span.end,now).lowercase()} ") + b.format(clock)
    }
}
