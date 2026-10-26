package ua.svitlo.app.domain

import java.time.format.DateTimeFormatter

/** Attention is earned by an outage, never by a fetch or a missing publication. */
object WidgetVisibility {
    private val outages = OUTAGE_STATES
    fun hasTimeline(row: ChartRow, chart: NotificationChart): Boolean =
        row.spans.any { it.state in outages && it.end > chart.now && it.start < chart.end }

    fun relevant(row: ChartRow, chart: NotificationChart): Boolean = row.emergency || row.current in outages ||
        hasTimeline(row,chart) || row.nextOutageAt?.let { it >= chart.now } == true ||
        // An unresolved, previously reported incident must not silently become "restored".
        row.status.startsWith("Відключення ·") || row.status.startsWith("Аварійне")

    fun rows(chart: NotificationChart, alwaysShow: Boolean): List<ChartRow> =
        chart.rows.filter { alwaysShow || relevant(it,chart) }

    fun upcomingStatus(row: ChartRow, chart: NotificationChart): String? {
        if (hasTimeline(row,chart) || row.current in outages || row.emergency ||
            row.status.startsWith("Відключення ·") || row.status.startsWith("Аварійне")) return null
        val at = row.nextOutageAt?.takeIf { it >= chart.now }?.atZone(KYIV) ?: return null
        val today = chart.now.atZone(KYIV).toLocalDate()
        val day = when (at.toLocalDate()) {
            today -> ""
            today.plusDays(1) -> "завтра "
            else -> at.format(DateTimeFormatter.ofPattern("dd.MM "))
        }
        val prefix = when (row.nextOutageState) { PowerState.POSSIBLE_OUTAGE, PowerState.SCHEDULED_OUTAGE -> "За графіком"; else -> "Відключення" }
        return "$prefix $day· ${at.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    }
}
