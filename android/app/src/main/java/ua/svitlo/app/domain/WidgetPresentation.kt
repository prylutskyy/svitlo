package ua.svitlo.app.domain

import java.time.Instant
import java.time.format.DateTimeFormatter

/** Short visual language; the full source status remains available to accessibility and details. */
object WidgetPresentation {
    private val clock = DateTimeFormatter.ofPattern("HH:mm")
    private fun nextAt(row: ChartRow,chart: NotificationChart) = (listOfNotNull(row.nextOutageAt)+row.spans.filter {
        it.start > chart.now && it.state in OUTAGE_STATES
    }.map { it.start }).filter { it >= chart.now }.minOrNull()
    fun focus(chart: NotificationChart): ChartRow? {
        fun priority(row: ChartRow) = when {
            row.current == PowerState.EMERGENCY_OUTAGE -> 0
            row.current == PowerState.CONFIRMED_OUTAGE && row.status.startsWith("Відключення ·") -> 1
            row.current == PowerState.CONFIRMED_OUTAGE -> 2
            row.current == PowerState.POSSIBLE_OUTAGE -> 3
            row.current == PowerState.SCHEDULED_OUTAGE -> 3
            row.emergency || row.status.startsWith("Відключення ·") || row.status.startsWith("Аварійне") -> 4
            nextAt(row,chart) != null -> 5
            else -> 6
        }
        return chart.rows.minWithOrNull(compareBy<ChartRow> { priority(it) }.thenBy {
            if (priority(it) == 5) nextAt(it,chart) else Instant.MIN
        })
    }
    private fun whenLabel(at: Instant,now: Instant): String {
        val time = at.atZone(KYIV)
        val today = now.atZone(KYIV).toLocalDate()
        return when (time.toLocalDate()) {
            today -> "Далі · ${time.format(clock)}"
            today.plusDays(1) -> "Завтра · ${time.format(clock)}"
            else -> time.format(DateTimeFormatter.ofPattern("dd.MM · HH:mm"))
        }
    }
    fun headline(chart: NotificationChart,compact: Boolean = false): String {
        val rows = chart.rows
        val full = when {
            rows.any { it.current == PowerState.EMERGENCY_OUTAGE } -> "Аварійне зараз"
            rows.any { it.current == PowerState.CONFIRMED_OUTAGE && it.status.startsWith("Відключення ·") } -> "Відключення зараз"
            rows.any { it.current == PowerState.CONFIRMED_OUTAGE } -> "За графіком зараз"
            rows.any { it.current == PowerState.POSSIBLE_OUTAGE } -> "За графіком зараз"
            rows.any { it.current == PowerState.SCHEDULED_OUTAGE } -> "За графіком зараз"
            rows.any { it.emergency || it.status.startsWith("Відключення ·") || it.status.startsWith("Аварійне") } -> "Стан уточнюється"
            else -> rows.mapNotNull { nextAt(it,chart) }.minOrNull()?.let { whenLabel(it,chart.now) } ?: "Графік адрес"
        }
        return if (!compact) full else when (full) {
            "Аварійне зараз" -> "Аварійне"
            "Відключення зараз" -> "Відключення"
            "За графіком зараз" -> "За графіком"
            "Стан уточнюється" -> "Уточнюємо"
            else -> full
        }
    }
    fun status(row: ChartRow,chart: NotificationChart): String {
        val full = WidgetVisibility.upcomingStatus(row,chart) ?: row.status
        return when {
            full.startsWith("Аварійне · ще ≈ ") -> "Ще ≈ "+full.removePrefix("Аварійне · ще ≈ ")
            full.startsWith("Відключення · ще ≈ ") -> "Ще ≈ "+full.removePrefix("Відключення · ще ≈ ")
            full.startsWith("Відключення о ") -> "З "+full.removePrefix("Відключення о ")
            full.startsWith("За графіком до ") -> "До "+full.removePrefix("За графіком до ")
            full.startsWith("Відключення завтра о ") -> "Завтра · "+full.removePrefix("Відключення завтра о ")
            full.startsWith("Відключення завтра · ") -> "Завтра · "+full.removePrefix("Відключення завтра · ")
            full.isNotEmpty() -> full
            row.emergency -> "Аварійне відключення"
            row.current == PowerState.UNKNOWN -> "Стан уточнюється"
            row.unpublishedFrom != null -> "На завтра ще немає графіка"
            else -> "Відключень не позначено"
        }
    }
}
