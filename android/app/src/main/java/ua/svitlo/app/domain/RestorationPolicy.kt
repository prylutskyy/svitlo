package ua.svitlo.app.domain

import java.time.Instant

/** Returns a dedupe key only for a fresh transition from a current outage to a known clear state. */
object RestorationPolicy {
    private val outageStates = setOf(PowerState.CONFIRMED_OUTAGE, PowerState.EMERGENCY_OUTAGE,PowerState.SCHEDULED_OUTAGE)

    fun observe(state: AppState, now: Instant): AppState {
        var observations = state.observations.orEmpty().filter { old -> state.addresses.any { it.id == old.addressId } }
        state.monitoredAddresses(now).forEach { address ->
            val schedule = state.schedule(address.id) ?: return@forEach
            if (!schedule.stale(now) && (schedule.currentOutage != null || schedule.powerAt(now,now) in outageStates) &&
                observations.none { it.addressId == address.id }) {
                val start = schedule.currentOutage?.startedAt ?: schedule.chartSpans(todayWindow(now).start,
                    todayWindow(now).end, now).firstOrNull { it.start <= now && it.end > now }?.start?.toEpochMilli() ?: now.toEpochMilli()
                observations = observations + OutageObservation(address.id, "${address.id}:$start${if(schedule.currentOutage!=null)":address" else ""}", now.toEpochMilli())
            }
        }
        return if (observations == state.observations.orEmpty()) state else state.copy(observations = observations)
    }

    fun notificationKey(state: AppState, addressId: String, latest: Schedule, observedNow:Instant = Instant.ofEpochMilli(latest.fetchedAt)): String? {
        if (!state.eventMonitoring || addressId != state.currentAddressId) return null
        val previous = state.schedule(addressId) ?: return null
        val at = observedNow
        val observation = state.observations.orEmpty().firstOrNull { it.addressId == addressId }
        if(latest.sourceUrl!=null) {
            if(latest.sourceUrl.startsWith("https://promin.") || observation==null || latest.currentOutage!=null) return null
            if(observation.key.endsWith(":address") || previous.currentOutage!=null) {
                if(latest.outagesExpected && !latest.outagesUnavailable && latest.outagesFetchedAt?.let {at.toEpochMilli()-it<=1_800_000}==true &&
                    !latest.stale(at) && latest.powerAt(at,at)==PowerState.NO_SCHEDULED_OUTAGE)
                    return "${observation.key}:restored"
                return null
            }
            val plan=latest.publishedQueuePlan()
            if(plan.stale(at) || previous.stale(at) || plan.powerAt(at,at)!=PowerState.NO_SCHEDULED_OUTAGE) return null
            return "${observation.key}:plan-ended"
        }
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
