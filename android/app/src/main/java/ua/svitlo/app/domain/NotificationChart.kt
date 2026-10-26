package ua.svitlo.app.domain

import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

data class ChartSpan(val start: Instant, val end: Instant, val state: PowerState)
data class ChartRow(val address: SavedAddress, val status: String, val current: PowerState, val spans: List<ChartSpan>,
                    val emergency: Boolean = false, val continues: Boolean = false,
                    val unpublishedFrom: Instant? = null, val nextOutageAt: Instant? = null,
                    val nextOutageState: PowerState? = null)

/** Preserve minute-accurate incident boundaries in addition to the half-hour source cells. */
fun Schedule?.chartSpans(start: Instant, end: Instant, now: Instant): List<ChartSpan> {
    val bounds = sortedSetOf(start, end)
    this?.currentOutage?.let { incident ->
        listOfNotNull(incident.startedAt ?: fetchedAt, incident.estimatedEnd).forEach {
            val instant = Instant.ofEpochMilli(it)
            if (instant > start && instant < end) bounds += instant
        }
    }
    this?.days?.forEach { day ->
        val date = runCatching { java.time.LocalDate.parse(day.date) }.getOrNull() ?: return@forEach
        day.slots.flatMap { listOf(it.startMinute, it.endMinute) }.forEach { minute ->
            val instant = date.atStartOfDay().plusMinutes(minute.toLong()).atZone(KYIV).toInstant()
            if (instant > start && instant < end) bounds += instant
        }
    }
    val result = mutableListOf<ChartSpan>()
    bounds.toList().zipWithNext().forEach { (a, b) ->
        val value = this?.powerAt(a, now) ?: PowerState.UNKNOWN
        val last = result.lastOrNull()
        if (last?.state == value) result[result.lastIndex] = last.copy(end = b)
        else result += ChartSpan(a, b, value)
    }
    return result
}
data class NotificationChart(val start: Instant, val end: Instant, val now: Instant, val rows: List<ChartRow>, val total: Int, val names: List<String>,
                             val focus: ChartRow? = rows.firstOrNull()) {
    val hidden get() = total - rows.size
    val summary get() = names.joinToString(" · ")
    val description get() = rows.joinToString("\n") { "${it.address.title} · ${it.address.description}: ${it.status.ifEmpty { "У графіку відключень не позначено" }}" }

    companion object {
        private val clock = DateTimeFormatter.ofPattern("HH:mm")
        fun create(state: AppState, now: Instant, maxRows: Int = 3): NotificationChart {
            val window = notificationWindow(now)
            val start = window.start
            val end = window.end
            val rows = state.addresses.filter { it.selected }.map { address ->
                val schedule = state.schedule(address.id)
                val current = schedule?.powerAt(now, now) ?: PowerState.UNKNOWN
                val incident = schedule?.currentOutage
                val spans = PowerHistory.overlay(schedule.chartSpans(start, end, now),
                    state.historyTails.orEmpty().firstOrNull { it.addressId == address.id }?.intervals.orEmpty(),now)
                // Status may describe a long outage beyond the viewport without changing its scale.
                val lastDay = schedule?.days.orEmpty().mapNotNull { runCatching {
                    java.time.LocalDate.parse(it.date).plusDays(1).atStartOfDay(KYIV).toInstant()
                }.getOrNull() }.maxOrNull() ?: end
                val detailEnd = maxOf(end, minOf(lastDay, now.plusSeconds(7 * 86400)))
                val future = schedule.chartSpans(now, detailEnd, now)
                val change = future.firstOrNull { it.start.isAfter(now) && it.state != current }
                val nextOutage = future.firstOrNull { it.start > now && it.state in OUTAGE_STATES }
                val localChange = change?.start?.atZone(KYIV)
                val today = now.atZone(KYIV).toLocalDate()
                val day = localChange?.let {
                    when (it.toLocalDate()) {
                        today -> ""
                        today.plusDays(1) -> "завтра"
                        else -> it.format(DateTimeFormatter.ofPattern("dd.MM"))
                    }
                }
                val time = localChange?.format(clock)
                val begins = if (day.isNullOrEmpty()) "о $time" else "$day о $time"
                val ends = if (day.isNullOrEmpty()) time else "$time $day"
                val status = when {
                    schedule == null && state.attempt(address.id)?.error != null -> "Збій отримання"
                    schedule == null -> "Отримуємо графік…"
                    incident?.emergency == true && schedule.stale(now) -> "Аварійне · дані застаріли"
                    incident?.emergency == true && incident.estimatePassed(now) -> "Аварійне · час уточнюється"
                    incident?.emergency == true -> incident.estimatedEnd?.let { "Аварійне · ще ≈ ${remainingTime(Instant.ofEpochMilli(it), now)}" } ?: "Аварійне · термін невідомий"
                    incident != null -> when {
                        schedule.stale(now) -> "Відключення · дані застаріли"
                        incident.estimatePassed(now) -> "Відключення · час уточнюється"
                        incident.estimatedEnd != null -> "Відключення · ще ≈ ${remainingTime(Instant.ofEpochMilli(incident.estimatedEnd), now)}"
                        else -> "Відключення · термін невідомий"
                    }
                    schedule.today(now) == null -> "Немає графіка"
                    schedule.stale(now) -> "Дані застаріли"
                    current == PowerState.CONFIRMED_OUTAGE && change?.state == PowerState.NO_SCHEDULED_OUTAGE -> "За графіком до $ends"
                    current == PowerState.POSSIBLE_OUTAGE && change?.state == PowerState.NO_SCHEDULED_OUTAGE -> "За графіком до $ends"
                    current == PowerState.CONFIRMED_OUTAGE -> "Відключення за графіком"
                    current == PowerState.POSSIBLE_OUTAGE -> "Відключення за графіком"
                    current == PowerState.SCHEDULED_OUTAGE -> "За графіком"
                    current == PowerState.UNKNOWN -> "Невідомий стан"
                    change?.state == PowerState.CONFIRMED_OUTAGE -> "Відключення $begins"
                    change?.state == PowerState.POSSIBLE_OUTAGE -> "За графіком $begins"
                    change?.state == PowerState.SCHEDULED_OUTAGE -> "За графіком $begins"
                    else -> ""
                }
                val outageStates = OUTAGE_STATES
                val edge = schedule?.powerAt(end, now)
                val continues = edge in outageStates && spans.lastOrNull()?.state == edge
                val tomorrow = now.atZone(KYIV).toLocalDate().plusDays(1)
                val tomorrowStart = tomorrow.atStartOfDay(KYIV).toInstant()
                val unpublished = tomorrowStart.takeIf {
                    schedule != null && !schedule.stale(now) && incident == null && it < end &&
                        schedule.days.none { day -> day.date == tomorrow.toString() && day.slots.isNotEmpty() }
                }
                ChartRow(address, status, current, spans, incident?.emergency == true, continues, unpublished,nextOutage?.start,nextOutage?.state)
            }
            fun priority(row: ChartRow) = if (row.emergency) -1 else when (row.current) {
                PowerState.EMERGENCY_OUTAGE -> -1
                PowerState.CONFIRMED_OUTAGE -> 0
                PowerState.POSSIBLE_OUTAGE -> 1
                PowerState.SCHEDULED_OUTAGE -> 1
                PowerState.UNKNOWN -> 2
                PowerState.NO_SCHEDULED_OUTAGE -> 3
            }
            val focus = rows.firstOrNull { it.address.id == state.currentAddressId } ?: rows.minWithOrNull(
                compareBy<ChartRow> { priority(it) }.thenBy { row ->
                    row.spans.firstOrNull { it.start > now && it.state in OUTAGE_STATES }?.start ?: Instant.MAX
                })
            val visible = (if (rows.size > maxRows) rows.sortedWith(compareBy<ChartRow> { if (it == focus) -2 else priority(it) }) else rows).take(maxRows)
            return NotificationChart(start, end, now, visible, rows.size, rows.map { it.address.title }, focus)
        }
    }
}
