package ua.svitlo.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** A single decision, not a miniature dashboard. All times come from the full source. */
data class WidgetFocusCard(val row: ChartRow, val label: String, val time: String,
                           val caption: String, val emergency: Boolean, val otherEvents: Int,
                           val stateLabel: String = "") {
    companion object {
        fun forWidget(state: AppState, chart: NotificationChart, hideWhenQuiet: Boolean): WidgetFocusCard? {
            create(state,chart)?.let { return it }
            if (hideWhenQuiet) return null
            val row = chart.rows.firstOrNull() ?: return null
            val schedule = state.schedule(row.address.id)
            val calm = schedule != null && !schedule.stale(chart.now) && row.current == PowerState.NO_SCHEDULED_OUTAGE
            return if (calm) WidgetFocusCard(row,"","✓","Відключень немає",false,0,"За графіком")
                else WidgetFocusCard(row,"Стан уточнюється","—",row.status.ifEmpty { "Оновлюємо дані" },false,0,"Немає свіжих даних")
        }
        private val clock = DateTimeFormatter.ofPattern("HH:mm")
        private val outages = OUTAGE_STATES
        private fun day(at: Instant, now: Instant): String = when (at.atZone(KYIV).toLocalDate()) {
            now.atZone(KYIV).toLocalDate() -> "Сьогодні"
            now.atZone(KYIV).toLocalDate().plusDays(1) -> "Завтра"
            else -> at.atZone(KYIV).format(DateTimeFormatter.ofPattern("dd.MM"))
        }
        private fun time(at: Instant) = at.atZone(KYIV).format(clock)

        fun create(state: AppState, chart: NotificationChart): WidgetFocusCard? {
            val relevant = WidgetVisibility.rows(chart, false)
            val row = WidgetPresentation.focus(chart.copy(rows = relevant)) ?: return null
            val schedule = state.schedule(row.address.id)
            val incident = schedule?.currentOutage
            fun card(label: String, time: String, caption: String, emergency: Boolean = false) =
                WidgetFocusCard(row, label, time, caption, emergency, relevant.size - 1, when {
                    label == "Стан уточнюється" -> "Стан уточнюється"
                    row.current == PowerState.EMERGENCY_OUTAGE -> "Аварійне зараз"
                    row.current == PowerState.CONFIRMED_OUTAGE && incident != null -> if (incident.kindLabel == "Повідомлене відключення") "Відключення зараз" else "${incident.kindLabel} зараз"
                    row.current == PowerState.CONFIRMED_OUTAGE -> "За графіком зараз"
                    row.current == PowerState.POSSIBLE_OUTAGE -> "За графіком зараз"
                    row.current == PowerState.SCHEDULED_OUTAGE -> "За графіком зараз"
                    else -> "Наступна подія"
                })
            if (incident != null) {
                if (schedule.stale(chart.now) || incident.estimatePassed(chart.now))
                    return card("Стан уточнюється", "—", if (schedule.stale(chart.now)) "Повідомлення застаріло" else "Час відновлення уточнюється")
                val start = incident.startedAt?.let(Instant::ofEpochMilli)
                if (start != null && start > chart.now)
                    return card("Повідомлене відключення", time(start), "${day(start, chart.now)} · початок")
                val end = incident.estimatedEnd?.let(Instant::ofEpochMilli)
                val began = start?.let { "Почалося ${day(it, chart.now).lowercase()} о ${time(it)}" }
                return if (end != null) card("Відновлення ${day(end, chart.now).lowercase()}, орієнтовно", time(end),
                    began ?: "Час відновлення може змінитися", incident.emergency)
                    else card("Час відновлення невідомий", "—", began ?: "Очікуємо повідомлення оператора", incident.emergency)
            }
            // Never infer a restoration from the clipped 12-hour chart edge.
            val last = schedule?.days.orEmpty().mapNotNull { runCatching {
                LocalDate.parse(it.date).plusDays(1).atStartOfDay(KYIV).toInstant()
            }.getOrNull() }.maxOrNull() ?: chart.end
            val spans = schedule.chartSpans(chart.now, maxOf(chart.end, minOf(last, chart.now.plusSeconds(7 * 86400))), chart.now)
            if (row.current in outages) {
                val end = spans.firstOrNull { it.start > chart.now && it.state != row.current }
                val label = "За графіком до"
                return if (end?.state == PowerState.NO_SCHEDULED_OUTAGE)
                    card(label, time(end.start), "${day(end.start, chart.now)} · за графіком")
                else if (end?.state in outages) card(label, time(requireNotNull(end).start),
                    "${day(end.start, chart.now)} · далі за графіком")
                else card("Відключення за графіком", "Зараз", "Час завершення не вказаний")
            }
            val next = spans.firstOrNull { it.start >= chart.now && it.state in outages }
            if (next != null) {
                val after = spans.firstOrNull { it.start >= next.end && it.state != next.state }
                val until = if (after?.state == PowerState.NO_SCHEDULED_OUTAGE) {
                    val endDay = if (after.start.atZone(KYIV).toLocalDate() == next.start.atZone(KYIV).toLocalDate()) "" else " ${day(after.start, chart.now).lowercase()}"
                    " · до ${time(after.start)}$endDay"
                } else " · початок"
                val fullRange = next.state == PowerState.SCHEDULED_OUTAGE && after?.state == PowerState.NO_SCHEDULED_OUTAGE &&
                    next.start.atZone(KYIV).toLocalDate() == after.start.atZone(KYIV).toLocalDate()
                return card(if (next.state == PowerState.SCHEDULED_OUTAGE) "За графіком" else "Відключення за графіком",
                    if (fullRange) "${time(next.start)}–${time(requireNotNull(after).start)}" else time(next.start),
                    if (fullRange) day(next.start, chart.now) else day(next.start, chart.now) + until)
            }
            return card("Стан уточнюється", "—", "Час відновлення невідомий")
        }
    }
}
