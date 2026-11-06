package ua.svitlo.app.ui

import ua.svitlo.app.domain.*
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Home shows the published queue plan, separately from reports of an address incident. */
internal fun homePlan(address: SavedAddress, schedule: Schedule?): Schedule? =
    if (address.feedId != null && !address.feedId.startsWith("promin/")) schedule?.publishedQueuePlan()
    else schedule

/** Missing historical observations must not erase an interval explicitly present in the plan. */
internal fun homeHistory(history: List<HistoryInterval>) = history.filter { it.state != PowerState.UNKNOWN }

internal data class HomeIdentity(val title: String, val subtitle: String)

internal fun homeIdentity(address: SavedAddress): HomeIdentity {
    val queue = address.feedGroup?.removePrefix("GPV")
    if (queue == null || address.feedId?.startsWith("promin/") == true)
        return HomeIdentity(address.title,address.description.takeUnless { it == address.title }.orEmpty())
    val automatic = address.title.matches(Regex("Черга\\s+(?:GPV)?\\d+(?:[.\\-]\\d+)?",RegexOption.IGNORE_CASE))
    val provider = address.city.name.let { if (it.substringAfterLast(" · ").contains("обленерго",true)) it.substringAfterLast(" · ") else it }
    val location = address.voeIncidentAddress?.let { "${it.city}, ${it.street}, ${it.house}" } ?: provider
    return HomeIdentity(if (automatic) "Черга $queue" else address.title,
        if (automatic) location else "Черга $queue · $location")
}

internal fun homeStatus(schedule: Schedule?, row: ChartRow, now: Instant): String {
    if (schedule == null) return row.status
    if (schedule.stale(now)) return "Графік застарів"
    val current = row.spans.firstOrNull { now >= it.start && now < it.end }
    fun time(at: Instant): String {
        val local = at.atZone(KYIV)
        val today = now.atZone(KYIV).toLocalDate()
        val prefix = when(local.toLocalDate()) {
            today -> ""
            today.plusDays(1) -> "завтра "
            else -> local.format(DateTimeFormatter.ofPattern("dd.MM "))
        }
        return prefix+local.format(DateTimeFormatter.ofPattern("HH:mm"))
    }
    // Use the full published interval; the visible timeline edge is not its end.
    val spans = schedule.inspectionSpans(notificationWindow(now),now)
    val active = spans.firstOrNull { now >= it.start && now < it.end }
    if (row.current in OUTAGE_STATES) return active?.takeIf { span -> spans.any { it.start == span.end && it.state == PowerState.NO_SCHEDULED_OUTAGE } }
        ?.let { "За планом до ${time(it.end)}" } ?: "Відключення за планом"
    if (row.current == PowerState.UNKNOWN || current?.state == PowerState.UNKNOWN) return "Графік уточнюється"
    val next = spans.firstOrNull { it.start > now && it.state in OUTAGE_STATES }
    return next?.let { span ->
        if (spans.none { it.start == span.end && it.state == PowerState.NO_SCHEDULED_OUTAGE }) "Наступне з ${time(span.start)}"
        else {
            val end = if (span.start.atZone(KYIV).toLocalDate() == span.end.atZone(KYIV).toLocalDate())
                span.end.atZone(KYIV).format(DateTimeFormatter.ofPattern("HH:mm")) else time(span.end)
            "Наступне ${time(span.start)}–$end"
        }
    } ?: "Відключень не заплановано"
}
