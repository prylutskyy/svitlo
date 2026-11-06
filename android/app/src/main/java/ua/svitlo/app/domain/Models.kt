package ua.svitlo.app.domain

import java.time.*

val KYIV: ZoneId = runCatching { ZoneId.of("Europe/Kyiv") }.getOrElse { ZoneId.of("Europe/Kiev") }

data class AddressItem(val id: String, val name: String)
data class VoeIncidentAddress(val regionId: String,val city: String,val street: String,val house: String)
data class SavedAddress(
    val id: String, val title: String,
    val city: AddressItem, val street: AddressItem, val house: AddressItem,
    val selected: Boolean = true,
    val feedId: String? = null, val feedGroup: String? = null,
    val prominCity: String? = null, val prominBuildingId: Long? = null,
    val prominGroupEstimated: Boolean = false,
    val voeIncidentAddress: VoeIncidentAddress? = null,
) {
    val description: String get() = voeIncidentAddress?.let { "${it.city}, ${it.street}, ${it.house}" } ?: "${city.name}, ${street.name}, ${house.name}"
}

enum class PowerState { NO_SCHEDULED_OUTAGE, CONFIRMED_OUTAGE, POSSIBLE_OUTAGE, UNKNOWN, EMERGENCY_OUTAGE, SCHEDULED_OUTAGE }
/** Schedule markers describe the published plan, independently of an address-specific incident. */
val OUTAGE_STATES = setOf(PowerState.CONFIRMED_OUTAGE,PowerState.POSSIBLE_OUTAGE,PowerState.EMERGENCY_OUTAGE,PowerState.SCHEDULED_OUTAGE)
data class Slot(val startMinute: Int, val endMinute: Int, val state: PowerState, val source: String = "")
data class ScheduleDay(val label: String, val date: String?, val slots: List<Slot>)
/** The address-specific current incident is independent of the hourly schedule. End is an estimate. */
data class CurrentOutage(val reason: String, val emergency: Boolean, val startedAt: Long?,
                         val estimatedEnd: Long?, val warning: String, val source: String) {
    val kindLabel: String get() = when {
        emergency -> "Аварійне відключення"
        reason.contains("непланов",ignoreCase=true) -> "Непланове відключення"
        reason.contains("планов",ignoreCase=true) -> "Планове відключення"
        reason.contains("оператив",ignoreCase=true) -> "Оперативне відключення"
        reason.equals("Планове відключення", ignoreCase = true) -> "Планове відключення"
        reason.equals("Непланове відключення", ignoreCase = true) -> "Непланове відключення"
        reason.contains("ГПВ", ignoreCase = true) || reason.contains("графіків погодинних", ignoreCase = true) -> "Відключення за ГПВ"
        else -> "Повідомлене відключення"
    }
    fun estimatePassed(now: Instant) = estimatedEnd?.let { it <= now.toEpochMilli() } == true
    fun endLabel(now: Instant): String? = estimatedEnd?.let {
        val end = Instant.ofEpochMilli(it).atZone(KYIV)
        end.format(java.time.format.DateTimeFormatter.ofPattern(
            if (end.toLocalDate() == now.atZone(KYIV).toLocalDate()) "HH:mm" else "dd.MM HH:mm"))
    }
}
data class Schedule(val fetchedAt: Long, val days: List<ScheduleDay>, val messages: List<String>, val currentOutage: CurrentOutage? = null, val queue: String? = null, val sourceUrl: String? = null,
    val reportedOutages: List<CurrentOutage>? = null, val sourceStale: Boolean = false,
    val planFetchedAt: Long? = null, val outagesFetchedAt: Long? = null,
    val planUnavailable: Boolean = false, val outagesUnavailable: Boolean = false,
    val outagesExpected: Boolean = false, val streamIssues: List<String>? = null) {
    fun today(now: Instant): ScheduleDay? = days.firstOrNull { it.date == now.atZone(KYIV).toLocalDate().toString() }
    fun freshness(now: Instant = Instant.ofEpochMilli(fetchedAt)): Duration = if (currentOutage != null || rawPowerAt(now) in OUTAGE_STATES) Duration.ofMinutes(30) else Duration.ofHours(1)
    fun stale(now: Instant): Boolean = sourceStale || now.toEpochMilli() - fetchedAt > freshness(now).toMillis() || (today(now) == null && currentOutage == null)
    /** Source interpretation without a cache-age check; never use this directly as a live UI state. */
    fun rawPowerAt(at: Instant): PowerState = powerAt(at, Instant.ofEpochMilli(fetchedAt), checkFreshness = false)

    fun powerAt(at: Instant, observedNow: Instant, checkFreshness: Boolean = true): PowerState {
        if (checkFreshness && stale(observedNow)) return PowerState.UNKNOWN
        fun scheduled(): PowerState {
            val local = at.atZone(KYIV)
            val minute = local.hour * 60 + local.minute
            if(planUnavailable || (checkFreshness && planFetchedAt?.let { observedNow.toEpochMilli()-it>3_600_000 }==true)) return PowerState.UNKNOWN
            return days.firstOrNull { it.date == local.toLocalDate().toString() }?.slots
                ?.firstOrNull { minute in it.startMinute until it.endMinute }?.state ?: PowerState.UNKNOWN
        }
        if(outagesExpected && (outagesUnavailable || (checkFreshness && outagesFetchedAt?.let { observedNow.toEpochMilli()-it>1_800_000 }==true)))
            return scheduled().takeIf { it in OUTAGE_STATES } ?: PowerState.UNKNOWN
        currentOutage?.let { incident ->
            val start = incident.startedAt ?: fetchedAt
            if (at.toEpochMilli() >= start) {
                // An estimated end never proves restoration, even if the hourly table is green.
                if (incident.estimatePassed(observedNow) || incident.estimatedEnd?.let { at.toEpochMilli() >= it } == true)
                    return if (at > observedNow) scheduled().takeIf { it in OUTAGE_STATES } ?: PowerState.UNKNOWN else PowerState.UNKNOWN
                return if (incident.emergency) PowerState.EMERGENCY_OUTAGE else PowerState.CONFIRMED_OUTAGE
            }
        }
        reportedOutages.orEmpty().filter { incident ->
            incident.startedAt?.let { at.toEpochMilli() >= it } == true &&
                incident.estimatedEnd?.let { at.toEpochMilli() < it } == true
        }.maxByOrNull { if (it.emergency) 1 else 0 }?.let {
            return if (it.emergency) PowerState.EMERGENCY_OUTAGE else PowerState.SCHEDULED_OUTAGE
        }
        return scheduled()
    }
}
data class CachedSchedule(val addressId: String, val schedule: Schedule, val changedAt: Long? = null)
enum class FetchFailure { NETWORK, SERVER, RATE_LIMIT, FORMAT, OTHER }
data class FetchAttempt(val addressId: String, val at: Long, val failures: Int, val error: String?,
                        val kind: FetchFailure? = null, val retryAfter: Long? = null)
data class OutageObservation(val addressId: String, val key: String, val observedAt: Long)
data class SyncStats(val requests: Long = 0, val receivedBytes: Long = 0, val succeeded: Long = 0,
                     val failed: Long = 0, val durationMs: Long = 0)
data class TrackingSession(val until: Long)
data class AppState(
    val addresses: List<SavedAddress> = emptyList(),
    val schedules: List<CachedSchedule> = emptyList(),
    val attempts: List<FetchAttempt> = emptyList(),
    val session: TrackingSession? = null,
    val notificationsEnabled: Boolean? = null,
    val graphDismissed: Boolean = false,
    val currentAddressId: String? = null,
    val eventMonitoring: Boolean = false,
    val dismissedEventKey: String? = null,
    val restorationNotifiedKey: String? = null,
    // Nullable for snapshots written before widgets existed (Gson does not invoke defaults).
    val widgets: List<WidgetConfig>? = null,
    val observations: List<OutageObservation>? = null,
    val syncStats: SyncStats? = null,
    val historyTails: List<HistoryTail>? = null,
    val dataSourceVersion: Int? = null,
) {
    fun graphsEnabled(now: Instant): Boolean = notificationsEnabled ?: (session?.until?.let { it > now.toEpochMilli() } == true)
    fun widgetAddressIds() = widgets.orEmpty().flatMap { it.addressIds }.toSet()
    fun active(now: Instant): Boolean = graphsEnabled(now) || (eventMonitoring && addresses.any { it.id == currentAddressId && it.selected }) || addresses.any { it.id in widgetAddressIds() }
    fun monitoredAddresses(now: Instant): List<SavedAddress> {
        val widgetIds = widgetAddressIds()
        return addresses.filter { it.id in widgetIds || (it.selected && (graphsEnabled(now) || (eventMonitoring && it.id == currentAddressId))) }
    }
    fun schedule(id: String): Schedule? = schedules.firstOrNull { it.addressId == id }?.schedule
    fun attempt(id: String): FetchAttempt? = attempts.firstOrNull { it.addressId == id }
}

/** Pure scheduling policy: one batch, only selected addresses, no tight retry loops. */
object EnergyPolicy {
    /** One non-wakeup alarm: transitions/expiry and a local 15-minute warning. */
    fun nextBoundary(state: AppState, now: Instant): Instant? {
        if (!state.active(now)) return null
        val local = now.atZone(KYIV)
        val midnight = local.toLocalDate().plusDays(1).atStartOfDay(KYIV).toInstant()
        val candidates = mutableListOf(midnight)
        if (state.notificationsEnabled == null) state.session?.let { candidates += Instant.ofEpochMilli(it.until) }
        if (state.eventMonitoring) FocusEvent.nextBoundaries(state, now).filter { it <= now.plus(Duration.ofMinutes(15)) }.forEach { candidates += it }
        state.monitoredAddresses(now).forEach { address ->
            val cached = state.schedule(address.id) ?: return@forEach
            cached.currentOutage?.let { incident ->
                incident.startedAt?.let { candidates += Instant.ofEpochMilli(it) }
                incident.estimatedEnd?.let { candidates += Instant.ofEpochMilli(it) }
            }
            candidates += Instant.ofEpochMilli(cached.fetchedAt).plus(cached.freshness(now)).plusSeconds(1)
            val day = cached.today(now)
            if (((state.graphsEnabled(now) && !state.graphDismissed) || address.id in state.widgetAddressIds()) && (day?.slots?.isNotEmpty() == true || cached.currentOutage != null)) {
                // Epoch arithmetic also handles repeated/skipped local hours at DST.
                val interval = Duration.ofMinutes(15).seconds
                candidates += Instant.ofEpochSecond((now.epochSecond / interval + 1) * interval)
            }
            day?.slots?.zipWithNext()?.filter { (a, b) -> a.state != b.state }?.forEach { (_, slot) ->
                candidates += local.toLocalDate().atStartOfDay(KYIV).plusMinutes(slot.startMinute.toLong()).toInstant()
            }
        }
        return candidates.filter { it.isAfter(now) }.minOrNull()
    }
}
