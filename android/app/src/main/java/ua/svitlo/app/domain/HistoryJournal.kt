package ua.svitlo.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object HistoryJournal {
    fun days(incidents: List<HistoryIncident>): Map<LocalDate,List<HistoryIncident>> = incidents
        .groupBy { Instant.ofEpochMilli(it.start).atZone(KYIV).toLocalDate() }
        .toSortedMap(reverseOrder()).mapValues { (_,events) -> events.sortedByDescending { it.start } }
    fun label(date: LocalDate, now: Instant): String = when (date) {
        now.atZone(KYIV).toLocalDate() -> "Сьогодні"
        now.atZone(KYIV).toLocalDate().minusDays(1) -> "Вчора"
        else -> date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    }
}
