package ua.svitlo.app.domain

import java.time.Instant

/** A pre-outage warning for the manually selected location. */
data class FocusEvent(val key: String, val address: SavedAddress, val title: String, val detail: String,
    val target: Instant?, val upcoming: Boolean, val emergency: Boolean, val possible: Boolean) {
    fun canPromote(now: Instant) = upcoming && target != null && target > now && target <= now.plusSeconds(900)
    companion object {
        fun create(state: AppState, now: Instant): FocusEvent? {
            val address = state.addresses.firstOrNull { it.id == state.currentAddressId && it.selected } ?: return null
            val schedule = state.schedule(address.id)?.takeUnless { it.stale(now) } ?: return null
            val start = now.atZone(KYIV).toLocalDate().atStartOfDay(KYIV).toInstant()
            val end = now.atZone(KYIV).toLocalDate().plusDays(2).atStartOfDay(KYIV).toInstant()
            val spans = schedule.chartSpans(start, end, now)
            val outageStates = OUTAGE_STATES
            val span = spans.firstOrNull { it.start > now && it.start <= now.plusSeconds(900) && it.state in outageStates } ?: return null
            // Never show a pill/push after an outage starts; only the upcoming 15-minute warning qualifies.
            val upcoming = true
            val possible = span.state == PowerState.POSSIBLE_OUTAGE
            return FocusEvent("${address.id}:slot:${span.end.toEpochMilli()}:${span.state}", address,
                when { possible -> "Вимкнення за графіком"; span.state == PowerState.SCHEDULED_OUTAGE -> "За графіком"; span.state == PowerState.EMERGENCY_OUTAGE -> "Аварійне вимкнення"; else -> "Вимкнення за графіком" },
                when { possible -> "За графіком джерела"; span.state == PowerState.SCHEDULED_OUTAGE -> "За графіком джерела"; span.state == PowerState.EMERGENCY_OUTAGE -> "Аварійна подія джерела"; else -> "За плановим графіком" },
                span.start, upcoming, span.state == PowerState.EMERGENCY_OUTAGE, possible)
        }
        fun nextBoundaries(state: AppState, now: Instant): List<Instant> {
            val address = state.addresses.firstOrNull { it.id == state.currentAddressId && it.selected } ?: return emptyList()
            val schedule = state.schedule(address.id)?.takeUnless { it.stale(now) } ?: return emptyList()
            val end = now.atZone(KYIV).toLocalDate().plusDays(2).atStartOfDay(KYIV).toInstant()
            val outageStates = OUTAGE_STATES
            return schedule.chartSpans(now, end, now).flatMap { span ->
                listOf(span.start, span.end) + if (span.state in outageStates)
                    listOf(span.start.minusSeconds(900)) else emptyList()
            }.filter { it > now }
        }
    }
}
