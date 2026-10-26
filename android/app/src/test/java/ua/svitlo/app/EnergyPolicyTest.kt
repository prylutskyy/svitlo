package ua.svitlo.app

import org.junit.Assert.*
import org.junit.Test
import ua.svitlo.app.domain.*
import java.time.*

class EnergyPolicyTest {
    private val now = Instant.parse("2026-10-18T10:00:00Z")
    private val address = SavedAddress("a", "Дім", AddressItem("1", "Місто"), AddressItem("2", "Вулиця"), AddressItem("3", "1"))
    private fun state(slots: List<Slot>, until: Instant = now.plusSeconds(4 * 3600)): AppState = AppState(
        addresses = listOf(address), session = TrackingSession(until.toEpochMilli()),
        schedules = listOf(CachedSchedule("a", Schedule(now.toEpochMilli(), listOf(ScheduleDay("нд 18.10", "2026-10-18", slots)), emptyList()))))

    @Test fun `expired tracking never schedules another local wakeup`() {
        assertNull(EnergyPolicy.nextBoundary(state(emptyList(), now), now))
        assertNull(EnergyPolicy.nextBoundary(AppState(), now))
    }
    @Test fun `timeline refresh is local and bounded to quarter hours`() {
        val slots = (0..47).map { Slot(it * 30, it * 30 + 30, PowerState.NO_SCHEDULED_OUTAGE) }
        assertEquals(now.plusSeconds(900), EnergyPolicy.nextBoundary(state(slots), now))
        assertEquals(now.plusSeconds(900), EnergyPolicy.nextBoundary(state(slots), now.plusSeconds(899)))
        assertEquals(now.plusSeconds(1800), EnergyPolicy.nextBoundary(state(slots), now.plusSeconds(900)))
    }
    @Test fun `real transitions can precede the marker refresh`() {
        val slots = listOf(Slot(0, 790, PowerState.NO_SCHEDULED_OUTAGE), Slot(790, 1440, PowerState.UNKNOWN))
        assertEquals(now.plusSeconds(600), EnergyPolicy.nextBoundary(state(slots), now))
    }
    @Test fun `deselected addresses do not schedule their transitions`() {
        val slots = listOf(Slot(0, 810, PowerState.NO_SCHEDULED_OUTAGE), Slot(810, 1440, PowerState.UNKNOWN))
        assertEquals(now.plusSeconds(4 * 3600), EnergyPolicy.nextBoundary(state(slots).copy(addresses = listOf(address.copy(selected = false))), now))
    }
    @Test fun `yesterday's cached schedule cannot be shown as today's`() {
        val schedule = Schedule(now.toEpochMilli(), listOf(ScheduleDay("сб 17.10", "2026-10-17", emptyList())), emptyList())
        assertNull(schedule.today(now))
        assertTrue(schedule.stale(now))
    }
    @Test fun `date math respects Kyiv DST instead of 24-hour assumptions`() {
        val fallback = Instant.parse("2026-10-25T00:00:00Z")
        val current = AppState(session = TrackingSession(fallback.plusSeconds(30 * 3600).toEpochMilli()))
        assertEquals(Instant.parse("2026-10-25T22:00:00Z"), EnergyPolicy.nextBoundary(current, fallback))
    }
}
