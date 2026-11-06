package ua.svitlo.app.domain

import java.time.*

enum class RefreshMode { BACKGROUND, FOREGROUND, MANUAL, RECONNECTED, OPENED }

/** All network entry points share these per-address deadlines; drawing never bypasses them. */
object RefreshPolicy {
    const val FOREGROUND_MILLIS = 60_000L
    const val MANUAL_COOLDOWN_SECONDS = 5L
    private val interrupted = OUTAGE_STATES

    /** Rendering cached data is also an opportunity to recover a delayed background check. */
    fun needsRefresh(state: AppState, now: Instant, lowPower: Boolean = false): Boolean =
        state.active(now) && state.monitoredAddresses(now).any { due(state,it.id,now,lowPower = lowPower) }

    fun critical(state: AppState, id: String, now: Instant): Boolean {
        val schedule = state.schedule(id) ?: return false
        return schedule.currentOutage != null || schedule.rawPowerAt(now) in interrupted ||
            schedule.rawPowerAt(Instant.ofEpochMilli(schedule.fetchedAt)) in interrupted ||
            state.observations.orEmpty().any { it.addressId == id }
    }

    fun interval(state: AppState, id: String, now: Instant, lowPower: Boolean = false): Duration {
        // A quiet published plan can change just as suddenly as an active outage.
        return Duration.ofMinutes(if (lowPower && id != state.currentAddressId) 30 else 15)
    }

    /** Actual changes, including tomorrow/midnight and minute-accurate incident estimates. */
    fun boundaries(schedule: Schedule): List<Instant> {
        val bounds = sortedSetOf<Instant>()
        schedule.days.forEach { day ->
            val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: return@forEach
            day.slots.flatMap { listOf(it.startMinute, it.endMinute) }.forEach {
                bounds += date.atStartOfDay().plusMinutes(it.toLong()).atZone(KYIV).toInstant()
            }
        }
        val changes = bounds.filter { at ->
            val before = schedule.rawPowerAt(at.minusMillis(1))
            val after = schedule.rawPowerAt(at)
            before != after && (before in interrupted || after in interrupted)
        }.toMutableList()
        schedule.currentOutage?.let {
            listOfNotNull(it.startedAt, it.estimatedEnd).forEach { epoch -> changes += Instant.ofEpochMilli(epoch) }
        }
        schedule.reportedOutages.orEmpty().forEach {
            listOfNotNull(it.startedAt,it.estimatedEnd).forEach { epoch -> changes += Instant.ofEpochMilli(epoch) }
        }
        return changes.distinct().sorted()
    }

    fun retryDelay(attempt: FetchAttempt, critical: Boolean): Duration {
        val shift = (attempt.failures - 1).coerceIn(0, 5)
        val minutes = when (attempt.kind) {
            FetchFailure.FORMAT -> if (critical) 30L else 60L
            FetchFailure.RATE_LIMIT -> 30L
            else -> ((if (critical) 5L else 15L) shl shift).coerceAtMost(if (critical) 60L else 120L)
        }
        return Duration.ofMinutes(minutes)
    }

    fun nextDue(state: AppState, id: String, now: Instant, mode: RefreshMode = RefreshMode.BACKGROUND,
                lowPower: Boolean = false): Instant {
        val schedule = state.schedule(id)
        val attempt = state.attempt(id)
        fun origin(key: String) = state.addresses.firstOrNull { it.id==key }?.feedId ?: "voe-address"
        val sharedAttempts = state.attempts.filter { origin(it.addressId)==origin(id) }
        val serverPause = state.attempts.mapNotNull { it.retryAfter }.maxOrNull()?.let(Instant::ofEpochMilli)
        if (serverPause != null && serverPause > now) return serverPause
        // A 429 without Retry-After still needs a cooldown, including the manual button.
        val ratePause = sharedAttempts.filter { it.failures > 0 && it.kind == FetchFailure.RATE_LIMIT }
            .maxOfOrNull { Instant.ofEpochMilli(it.at).plus(retryDelay(it,critical(state,id,now))) }
        if (ratePause != null && ratePause > now) return ratePause
        // A shared endpoint failure pauses the batch, not merely its first address.
        if (mode != RefreshMode.MANUAL && mode != RefreshMode.OPENED) {
            val pause = sharedAttempts.filter { it.failures > 0 && it.kind != FetchFailure.OTHER &&
                !(mode == RefreshMode.RECONNECTED && it.kind == FetchFailure.NETWORK) }.map {
                Instant.ofEpochMilli(it.at).plus(retryDelay(it,critical(state,it.addressId,now) || critical(state,id,now)))
            }.maxOrNull()
            if (pause != null && pause > now) return pause
        }
        if (attempt != null && attempt.at > now.plusSeconds(60).toEpochMilli()) return now // Clock corrected backwards.
        val anchor = attempt?.at?.let(Instant::ofEpochMilli) ?: schedule?.fetchedAt?.let(Instant::ofEpochMilli) ?: return now
        if (mode == RefreshMode.MANUAL || mode == RefreshMode.OPENED) return anchor.plusSeconds(MANUAL_COOLDOWN_SECONDS)
        if (attempt != null && attempt.failures > 0) {
            if (mode == RefreshMode.RECONNECTED && attempt.kind == FetchFailure.NETWORK &&
                (schedule == null || now >= Instant.ofEpochMilli(schedule.fetchedAt).plus(interval(state,id,now,lowPower))))
                return anchor.plusSeconds(60)
            return anchor.plus(retryDelay(attempt, critical(state,id,now)))
        }
        var due = anchor.plus(if (mode == RefreshMode.FOREGROUND) Duration.ofMillis(FOREGROUND_MILLIS) else interval(state,id,now,lowPower))
        if (schedule != null) {
            val transitions = boundaries(schedule)
            val warning = if (state.eventMonitoring && id == state.currentAddressId)
                transitions.filter { schedule.rawPowerAt(it) in interrupted }.map { it.minusSeconds(900) } else emptyList()
            (transitions + warning).filter { it > anchor }.minOrNull()?.let { due = minOf(due, it) }
            // Re-check at the calendar boundary only when a new day actually needs data.
            val midnight = anchor.atZone(KYIV).toLocalDate().plusDays(1).atStartOfDay(KYIV).toInstant()
            if (schedule.days.none { it.date == midnight.atZone(KYIV).toLocalDate().toString() && it.slots.isNotEmpty() })
                due = minOf(due, midnight)
        }
        return due
    }

    fun due(state: AppState, id: String, now: Instant, mode: RefreshMode = RefreshMode.BACKGROUND,
            lowPower: Boolean = false) = now >= nextDue(state,id,now,mode,lowPower)
}
