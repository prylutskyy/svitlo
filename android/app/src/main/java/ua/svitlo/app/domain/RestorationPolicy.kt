package ua.svitlo.app.domain

import java.time.Instant

/** Returns a dedupe key only for a fresh transition from a current outage to a known clear state. */
object RestorationPolicy {
    private val outageStates = setOf(PowerState.CONFIRMED_OUTAGE, PowerState.EMERGENCY_OUTAGE)

    fun observe(state: AppState, now: Instant): AppState {
        var observations = state.observations.orEmpty().filter { old -> state.addresses.any { it.id == old.addressId } }
        state.monitoredAddresses(now).forEach { address ->
            val schedule = state.schedule(address.id) ?: return@forEach
            if (!schedule.stale(now) && (schedule.currentOutage != null || schedule.powerAt(now,now) in outageStates) &&
                observations.none { it.addressId == address.id }) {
                val start = schedule.currentOutage?.startedAt ?: schedule.chartSpans(todayWindow(now).start,
                    todayWindow(now).end, now).firstOrNull { it.start <= now && it.end > now }?.start?.toEpochMilli() ?: now.toEpochMilli()
                observations = observations + OutageObservation(address.id, "${address.id}:$start", now.toEpochMilli())
            }
        }
        return if (observations == state.observations.orEmpty()) state else state.copy(observations = observations)
    }

    fun notificationKey(state: AppState, addressId: String, latest: Schedule): String? {
        if (!state.eventMonitoring || addressId != state.currentAddressId) return null
        val previous = state.schedule(addressId) ?: return null
        val at = Instant.ofEpochMilli(latest.fetchedAt)
        val observation = state.observations.orEmpty().firstOrNull { it.addressId == addressId }
        val previousAt = Instant.ofEpochMilli(previous.fetchedAt)
        val endedSinceCheck = !previous.stale(at) && RefreshPolicy.boundaries(previous).any {
            it > previousAt && it <= at && previous.rawPowerAt(it.minusMillis(1)) in outageStates &&
                previous.rawPowerAt(it) == PowerState.NO_SCHEDULED_OUTAGE
        }
        val hadCurrentOutage = observation != null || previous.currentOutage != null ||
            endedSinceCheck || (!previous.stale(at) && (previous.powerAt(at, at) in outageStates || previous.powerAt(previousAt, previousAt) in outageStates))
        val isFreshAndClear = latest.today(at) != null && !latest.stale(at) && latest.currentOutage == null &&
            latest.powerAt(at, at) == PowerState.NO_SCHEDULED_OUTAGE
        return if (hadCurrentOutage && isFreshAndClear) observation?.let { "${it.key}:restored" } ?: "$addressId:${latest.fetchedAt}" else null
    }
}
