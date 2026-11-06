package ua.svitlo.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object HistoryJournal {
    fun events(report:HistoryReport):List<HistoryIncident> = report.incidents + report.intervals
        .filter {it.state==PowerState.SCHEDULED_OUTAGE && it.end>it.start}
        .map {HistoryIncident(-it.start,it.start,it.start,it.end,it.end.takeIf {end->end<report.end},false,"За опублікованим графіком",null,false)}
    fun days(incidents: List<HistoryIncident>): Map<LocalDate,List<HistoryIncident>> = incidents
        .groupBy { Instant.ofEpochMilli(it.start).atZone(KYIV).toLocalDate() }
        .toSortedMap(reverseOrder()).mapValues { (_,events) -> events.sortedByDescending { it.start } }
    fun label(date: LocalDate, now: Instant): String = when (date) {
        now.atZone(KYIV).toLocalDate() -> "Сьогодні"
        now.atZone(KYIV).toLocalDate().minusDays(1) -> "Вчора"
        else -> date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    }
}
