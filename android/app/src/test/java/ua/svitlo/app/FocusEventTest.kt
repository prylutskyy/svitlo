package ua.svitlo.app

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import ua.svitlo.app.domain.*
import java.time.Instant

class FocusEventTest {
    private val now = Instant.parse("2026-10-18T10:00:00Z") // 13:00 Kyiv
    private val home = SavedAddress("home", "Дім", AddressItem("1", "Вінниця"), AddressItem("2", "Вулиця"), AddressItem("3", "9"))
    private fun state(start: Int = 795, power: PowerState = PowerState.CONFIRMED_OUTAGE): AppState {
        val schedule = Schedule(now.toEpochMilli(), listOf(ScheduleDay("Сьогодні", "2026-10-18", listOf(
            Slot(0, start, PowerState.NO_SCHEDULED_OUTAGE), Slot(start, 840, power), Slot(840, 1440, PowerState.NO_SCHEDULED_OUTAGE)))), emptyList(),
            if (power == PowerState.EMERGENCY_OUTAGE) CurrentOutage("Аварія", true, now.plusSeconds(900).toEpochMilli(), now.plusSeconds(1800).toEpochMilli(), "", "") else null)
        return AppState(addresses = listOf(home), schedules = listOf(CachedSchedule(home.id, schedule)),
            notificationsEnabled = false, currentAddressId = home.id, eventMonitoring = true)
    }
    @Test fun `unlimited opt in survives hours and days but does not silently opt in a new user`() {
        assertFalse(AppState().active(now))
        val state = state().copy(notificationsEnabled = true, eventMonitoring = false)
        assertTrue(state.active(now.plusSeconds(7 * 86400)))
        assertTrue(state.copy(graphDismissed = true).active(now))
        assertFalse(state.copy(notificationsEnabled = false).active(now))
    }
    @Test fun `warn only inside fifteen minutes and remove pill as outage begins`() {
        val state = state()
        assertNull(FocusEvent.create(state, now.minusSeconds(1)))
        val warning = FocusEvent.create(state, now)!!
        assertTrue(warning.upcoming)
        assertEquals(now.plusSeconds(900), warning.target)
        assertFalse(warning.detail.contains("18.10"))
        assertFalse(warning.detail.contains("23:15"))
        val possible = FocusEvent.create(state(power = PowerState.POSSIBLE_OUTAGE), now)!!
        assertTrue(possible.possible)
        assertNull(FocusEvent.create(state, now.plusSeconds(900)))
        assertNull(FocusEvent.create(state, now.plusSeconds(3600)))
    }
    @Test fun `legacy possible marker uses neutral schedule language`() {
        val event = FocusEvent.create(state(power = PowerState.POSSIBLE_OUTAGE), now)!!
        assertTrue(event.possible)
        assertEquals("Вимкнення за графіком", event.title)
        assertNull(FocusEvent.create(state(power = PowerState.POSSIBLE_OUTAGE), now.plusSeconds(900)))
    }
    @Test fun `upcoming overnight warning preserves identity across midnight`() {
        val late = Instant.parse("2026-10-18T20:45:00Z")
        val schedule = Schedule(late.toEpochMilli(), listOf(
            ScheduleDay("Сьогодні", "2026-10-18", listOf(Slot(0, 1440, PowerState.NO_SCHEDULED_OUTAGE))),
            ScheduleDay("Завтра", "2026-10-19", listOf(Slot(0, 30, PowerState.CONFIRMED_OUTAGE), Slot(30, 1440, PowerState.NO_SCHEDULED_OUTAGE)))), emptyList())
        val state = state(start = 1275).copy(currentAddressId = home.id, eventMonitoring = true,
            schedules = listOf(CachedSchedule(home.id, schedule)))
        val event = FocusEvent.create(state, late)!!
        assertEquals(late.plusSeconds(900), event.target)
        assertEquals(event.key, FocusEvent.create(state, late.plusSeconds(300))!!.key)
        assertNull(FocusEvent.create(state, event.target!!))
    }
    @Test fun `stale missing deselected and unchosen addresses never generate events`() {
        val state = state()
        assertNull(FocusEvent.create(state.copy(currentAddressId = null), now))
        assertNull(FocusEvent.create(state.copy(addresses = listOf(home.copy(selected = false))), now))
        assertNull(FocusEvent.create(state.copy(schedules = emptyList()), now))
        assertNull(FocusEvent.create(state, now.plusSeconds(3601)))
    }
    @Test fun `only current location is fetched when graphs are off`() {
        val state = state().copy(addresses = listOf(home, home.copy(id = "work")))
        assertEquals(listOf("home"), state.monitoredAddresses(now).map { it.id })
        assertEquals(2, state.copy(notificationsEnabled = true).monitoredAddresses(now).size)
        assertTrue(state.copy(eventMonitoring = false).monitoredAddresses(now).isEmpty())
    }
    @Test fun `hidden graph has no fifteen minute marker alarm but keeps data refresh`() {
        val state = state().let { it.copy(notificationsEnabled = true, graphDismissed = true, eventMonitoring = false,
            schedules = it.schedules.map { cached -> cached.copy(schedule = cached.schedule.copy(days = listOf(
                ScheduleDay("Сьогодні", "2026-10-18", listOf(Slot(0,1440,PowerState.NO_SCHEDULED_OUTAGE)))))) }) }
        assertTrue(state.active(now))
        assertEquals(now.plusSeconds(3601), EnergyPolicy.nextBoundary(state, now))
    }
    @Test fun `warning boundary does not require polling every minute`() {
        val state = state(start = 800)
        assertEquals(now.plusSeconds(300), EnergyPolicy.nextBoundary(state, now))
    }
    @Test fun `dismissal and focus survive JSON round trip`() {
        val value = state().copy(graphDismissed = true, dismissedEventKey = FocusEvent.create(state(), now)!!.key)
        val parsed = Gson().fromJson(Gson().toJson(value), AppState::class.java)
        assertEquals(value, parsed)
        val legacy = Gson().fromJson("{\"session\":{\"until\":${now.plusSeconds(100).toEpochMilli()}}}", AppState::class.java)
        assertTrue(legacy.graphsEnabled(now)); assertFalse(legacy.graphsEnabled(now.plusSeconds(101)))
    }
    @Test fun `unknown table boundary is never used as restoration deadline`() {
        val state = state(start = 800).let { state -> state.copy(schedules = state.schedules.map { it.copy(schedule = it.schedule.copy(
            days = listOf(ScheduleDay("Сьогодні", "2026-10-18", listOf(Slot(0, 800, PowerState.NO_SCHEDULED_OUTAGE), Slot(800, 1440, PowerState.UNKNOWN)))))) }) }
        assertNull(FocusEvent.create(state, now))
    }
    @Test fun `emergency warning also disappears when its start arrives`() {
        val state = state(start = 795, power = PowerState.EMERGENCY_OUTAGE)
        val event = FocusEvent.create(state, now)!!
        assertTrue(event.emergency)
        assertEquals(now.plusSeconds(900), event.target)
        assertNull(FocusEvent.create(state, event.target!!))
    }
}
