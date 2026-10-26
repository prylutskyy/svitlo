package ua.svitlo.app

import org.junit.Assert.*
import org.junit.Test
import ua.svitlo.app.domain.*
import java.time.Instant

class RestorationPolicyTest {
    private val at = Instant.parse("2026-10-18T10:00:00Z")
    private val home = SavedAddress("home", "Дім", AddressItem("1", "Вінниця"), AddressItem("2", "Вулиця"), AddressItem("3", "9"))
    private fun schedule(out: Boolean, fetchedAt: Instant = at, futureOut: Boolean = false): Schedule {
        val state = if (out) PowerState.EMERGENCY_OUTAGE else PowerState.NO_SCHEDULED_OUTAGE
        val future = if (futureOut) PowerState.CONFIRMED_OUTAGE else PowerState.NO_SCHEDULED_OUTAGE
        return Schedule(fetchedAt.toEpochMilli(), listOf(ScheduleDay("Сьогодні", "2026-10-18", listOf(
            Slot(0, 600, PowerState.NO_SCHEDULED_OUTAGE), Slot(600, 1200, state), Slot(1200, 1440, future)))), emptyList())
    }
    private fun state(previous: Schedule) = AppState(addresses = listOf(home), schedules = listOf(CachedSchedule(home.id, previous)),
        currentAddressId = home.id, eventMonitoring = true)

    @Test fun `fresh response that ends a current emergency creates one restoration key`() {
        val previous = schedule(out = true)
        val next = schedule(out = false, futureOut = true)
        assertEquals("home:${next.fetchedAt}", RestorationPolicy.notificationKey(state(previous), home.id, next))
    }

    @Test fun `scheduled future interruption alone is not a current outage or a restoration`() {
        val previous = schedule(out = false, futureOut = true)
        val next = schedule(out = false, fetchedAt = at.plusSeconds(600), futureOut = true)
        assertNull(RestorationPolicy.notificationKey(state(previous), home.id, next))
    }

    @Test fun `stale unknown or still interrupted responses never claim restoration`() {
        val previous = schedule(out = true)
        val stale = schedule(out = false, fetchedAt = at.plusSeconds(3601))
        val stillOut = schedule(out = true, fetchedAt = at.plusSeconds(600))
        val unknown = Schedule(at.plusSeconds(600).toEpochMilli(), listOf(ScheduleDay("Сьогодні", "2026-10-18", emptyList())), emptyList())
        assertNull(RestorationPolicy.notificationKey(state(previous), home.id, stale))
        assertNull(RestorationPolicy.notificationKey(state(previous), home.id, stillOut))
        assertNull(RestorationPolicy.notificationKey(state(previous), home.id, unknown))
    }

    @Test fun `restoration push is restricted to manually selected focus`() {
        val state = state(schedule(out = true))
        val clear = schedule(out = false)
        assertNull(RestorationPolicy.notificationKey(state.copy(eventMonitoring = false), home.id, clear))
        assertNull(RestorationPolicy.notificationKey(state.copy(currentAddressId = "elsewhere"), home.id, clear))
    }
    @Test fun `planned end compares observed history instead of reevaluating only the old clock`() {
        val before = at.plusSeconds(59)
        val old = Schedule(before.toEpochMilli(),listOf(ScheduleDay("Сьогодні","2026-10-18",listOf(
            Slot(0,781,PowerState.CONFIRMED_OUTAGE),Slot(781,1440,PowerState.NO_SCHEDULED_OUTAGE)))),emptyList())
        val end = at.plusSeconds(60)
        val observed = RestorationPolicy.observe(state(old),before)
        val clear = schedule(false,fetchedAt=end)
        assertNotNull(RestorationPolicy.notificationKey(observed,home.id,clear))
    }
    @Test fun `possible only intervals never produce a light restored assertion`() {
        val possible = schedule(false).copy(days=listOf(ScheduleDay("Сьогодні","2026-10-18",listOf(Slot(0,1440,PowerState.POSSIBLE_OUTAGE)))))
        val observed = RestorationPolicy.observe(state(possible),at)
        assertTrue(observed.observations.orEmpty().isEmpty())
        assertNull(RestorationPolicy.notificationKey(observed,home.id,schedule(false,fetchedAt=at.plusSeconds(60))))
    }
}
