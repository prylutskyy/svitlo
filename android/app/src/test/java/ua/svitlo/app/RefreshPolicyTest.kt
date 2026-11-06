package ua.svitlo.app

import org.junit.Assert.*
import org.junit.Test
import ua.svitlo.app.domain.*
import java.time.*

class RefreshPolicyTest {
    private val at = Instant.parse("2026-10-19T10:00:00Z") // 13:00 Kyiv
    private val home = SavedAddress("home","Дім",AddressItem("1","Місто"),AddressItem("2","Вулиця"),AddressItem("3","1"))
    private fun schedule(start: Int = 900, end: Int = 960, state: PowerState = PowerState.CONFIRMED_OUTAGE, fetched: Instant = at) =
        Schedule(fetched.toEpochMilli(),listOf(ScheduleDay("Сьогодні","2026-10-19", listOf(
            Slot(0,start,PowerState.NO_SCHEDULED_OUTAGE),Slot(start,end,state),Slot(end,1440,PowerState.NO_SCHEDULED_OUTAGE)))),emptyList())
    private fun state(schedule: Schedule = schedule()) = AppState(addresses = listOf(home), notificationsEnabled = true,
        currentAddressId = home.id, schedules = listOf(CachedSchedule(home.id,schedule)),
        attempts = listOf(FetchAttempt(home.id,schedule.fetchedAt,0,null)))

    @Test fun `quiet and active addresses both check every fifteen minutes`() {
        assertEquals(at.plusSeconds(900), RefreshPolicy.nextDue(state(),home.id,at))
        assertEquals(at.plusSeconds(900), RefreshPolicy.nextDue(state(schedule(700,900)),home.id,at))
    }
    @Test fun `predicted end is due even one second after the previous successful check`() {
        val previous = state(schedule(700,781)) // Ends 13:01
        val check = at.plusSeconds(59)
        val value = previous.copy(attempts = listOf(FetchAttempt(home.id,check.toEpochMilli(),0,null)))
        val end = at.plusSeconds(60)
        assertEquals(end,RefreshPolicy.nextDue(value,home.id,check))
        assertFalse(RefreshPolicy.due(value,home.id,check))
        assertTrue(RefreshPolicy.due(value,home.id,end))
        val updated = state(schedule(700,781,fetched = end))
        assertFalse(RefreshPolicy.due(updated,home.id,end)) // Same boundary never loops.
    }
    @Test fun `widget only address also refreshes at start and end without focus events`() {
        val value = state(schedule(781,790)).copy(notificationsEnabled = false,currentAddressId = null,
            addresses = listOf(home.copy(selected = false)),widgets = listOf(WidgetConfig(1,listOf(home.id))))
        assertEquals(at.plusSeconds(60),RefreshPolicy.nextDue(value,home.id,at))
    }
    @Test fun `warning deadline fetches once then start still has its own deadline`() {
        val value = state(schedule(800,900)).copy(eventMonitoring = true)
        val warning = at.plusSeconds(300)
        assertEquals(warning,RefreshPolicy.nextDue(value,home.id,at))
        val updated = state(schedule(800,900,fetched = warning)).copy(eventMonitoring = true)
        assertEquals(at.plusSeconds(1200),RefreshPolicy.nextDue(updated,home.id,warning))
    }
    @Test fun `drawing cannot force a quiet network query before its deadline`() {
        val value = state()
        assertEquals(at.plusSeconds(900),EnergyPolicy.nextBoundary(value,at))
        assertFalse(RefreshPolicy.due(value,home.id,at.plusSeconds(300)))
        assertTrue(RefreshPolicy.due(value,home.id,at.plusSeconds(900)))
    }
    @Test fun `an extension does not create a restore event and schedules the new end`() {
        val oldEnd = at.plusSeconds(60)
        val extended = state(schedule(700,800,fetched = oldEnd))
        assertEquals(oldEnd.plusSeconds(900),RefreshPolicy.nextDue(extended,home.id,oldEnd))
        assertNull(RestorationPolicy.notificationKey(state(schedule(700,781)),home.id,extended.schedule(home.id)!!))
    }
    @Test fun `passed incident estimate remains critical and unknown until fresh response`() {
        val incident = CurrentOutage("Аварія",true,at.minusSeconds(3600).toEpochMilli(),at.plusSeconds(60).toEpochMilli(),"","")
        val value = state(schedule().copy(currentOutage = incident))
        assertEquals(at.plusSeconds(60),RefreshPolicy.nextDue(value,home.id,at))
        assertEquals(PowerState.UNKNOWN,value.schedule(home.id)!!.powerAt(at.plusSeconds(61),at.plusSeconds(61)))
        assertTrue(RefreshPolicy.critical(value,home.id,at.plusSeconds(61)))
    }
    @Test fun `battery saving slows other addresses without blocking current place`() {
        val active = state(schedule(700,900))
        assertEquals(Duration.ofMinutes(15),RefreshPolicy.interval(active,home.id,at,true))
        assertEquals(Duration.ofMinutes(30),RefreshPolicy.interval(active.copy(currentAddressId=null),home.id,at,true))
        assertEquals(Duration.ofMinutes(30),RefreshPolicy.interval(state().copy(currentAddressId=null),home.id,at,true))
    }
    @Test fun `open screen checks every minute and manual refresh has five second floor`() {
        assertEquals(at.plusSeconds(60),RefreshPolicy.nextDue(state(),home.id,at,RefreshMode.FOREGROUND))
        assertFalse(RefreshPolicy.due(state(),home.id,at.plusSeconds(59),RefreshMode.FOREGROUND))
        assertTrue(RefreshPolicy.due(state(),home.id,at.plusSeconds(60),RefreshMode.FOREGROUND))
        assertFalse(RefreshPolicy.due(state(),home.id,at.plusSeconds(4),RefreshMode.MANUAL))
        assertTrue(RefreshPolicy.due(state(),home.id,at.plusSeconds(5),RefreshMode.MANUAL))
    }
    @Test fun `shared retry after protects every address including manual refresh`() {
        val retry = at.plusSeconds(1200)
        val value = state().copy(attempts = listOf(FetchAttempt("other",at.toEpochMilli(),1,"429",FetchFailure.RATE_LIMIT,retry.toEpochMilli())))
        assertEquals(retry,RefreshPolicy.nextDue(value,home.id,at,RefreshMode.MANUAL))
        assertEquals(retry,RefreshPolicy.nextDue(value,home.id,at))
    }
    @Test fun `rate limit without retry after also protects manual refresh`() {
        val value = state().copy(attempts = listOf(FetchAttempt(home.id,at.toEpochMilli(),1,"429",FetchFailure.RATE_LIMIT)))
        assertEquals(at.plusSeconds(1800),RefreshPolicy.nextDue(value,home.id,at.plusSeconds(5),RefreshMode.MANUAL))
    }
    @Test fun `opening bypasses foreground minute interval but deduplicates rapid resumes`() {
        assertFalse(RefreshPolicy.due(state(),home.id,at.plusSeconds(4),RefreshMode.OPENED))
        assertTrue(RefreshPolicy.due(state(),home.id,at.plusSeconds(5),RefreshMode.OPENED))
        assertFalse(RefreshPolicy.due(state(),home.id,at.plusSeconds(5),RefreshMode.FOREGROUND))
        val retry=at.plusSeconds(900)
        val limited=state().copy(attempts=listOf(FetchAttempt(home.id,at.toEpochMilli(),1,"429",FetchFailure.RATE_LIMIT,retry.toEpochMilli())))
        assertEquals(retry,RefreshPolicy.nextDue(limited,home.id,at.plusSeconds(5),RefreshMode.OPENED))
    }
    @Test fun `critical network error backs off five minutes and reconnect is bounded`() {
        val value = state(schedule(700,900)).copy(attempts = listOf(FetchAttempt(home.id,at.toEpochMilli(),1,"offline",FetchFailure.NETWORK)))
        assertEquals(at.plusSeconds(300),RefreshPolicy.nextDue(value,home.id,at))
        assertFalse(RefreshPolicy.due(value,home.id,at.plusSeconds(30),RefreshMode.RECONNECTED))
        assertTrue(RefreshPolicy.due(value,home.id,at.plusSeconds(901),RefreshMode.RECONNECTED))
    }
    @Test fun `format failure pauses all addresses rather than hammering the endpoint`() {
        val value = state().copy(attempts = listOf(FetchAttempt("other",at.toEpochMilli(),1,"format",FetchFailure.FORMAT)))
        assertEquals(at.plusSeconds(3600),RefreshPolicy.nextDue(value,home.id,at))
    }
    @Test fun `tomorrow outage at midnight has a boundary across calendar days`() {
        val late = Instant.parse("2026-10-19T20:59:59Z")
        val schedule = Schedule(late.toEpochMilli(),listOf(
            ScheduleDay("Сьогодні","2026-10-19",listOf(Slot(0,1440,PowerState.NO_SCHEDULED_OUTAGE))),
            ScheduleDay("Завтра","2026-10-20",listOf(Slot(0,60,PowerState.CONFIRMED_OUTAGE),Slot(60,1440,PowerState.NO_SCHEDULED_OUTAGE)))),emptyList())
        assertEquals(late.plusSeconds(1),RefreshPolicy.nextDue(state(schedule),home.id,late))
    }
    @Test fun `clock rollback recovers instead of keeping future attempt forever`() {
        val value = state().copy(attempts = listOf(FetchAttempt(home.id,at.plusSeconds(7200).toEpochMilli(),0,null)))
        assertTrue(RefreshPolicy.due(value,home.id,at))
    }

    @Test fun `stale widget only cache requests recovery without requiring visible graphs`() {
        val value = state().copy(notificationsEnabled = false,currentAddressId = null,eventMonitoring = false,
            addresses = listOf(home.copy(selected = false)),widgets = listOf(WidgetConfig(1,listOf(home.id))))
        assertFalse(RefreshPolicy.needsRefresh(value,at.plusSeconds(60)))
        val later = at.plusSeconds(7200)
        assertTrue(value.schedule(home.id)!!.stale(later))
        assertTrue(RefreshPolicy.needsRefresh(value,later,lowPower = true))
        assertFalse(RefreshPolicy.needsRefresh(value.copy(widgets = emptyList()),later))
    }

    @Test fun `stale recovery respects retries instead of fetching on every render`() {
        val later = at.plusSeconds(7200)
        val value = state().copy(attempts = listOf(FetchAttempt(home.id,later.toEpochMilli(),1,"offline",FetchFailure.NETWORK)))
        assertTrue(value.schedule(home.id)!!.stale(later))
        assertFalse(RefreshPolicy.needsRefresh(value,later))
        assertTrue(RefreshPolicy.needsRefresh(value,RefreshPolicy.nextDue(value,home.id,later)))
        val paused = value.copy(attempts = listOf(FetchAttempt(home.id,later.toEpochMilli(),1,"429",FetchFailure.RATE_LIMIT,
            later.plusSeconds(3600).toEpochMilli())))
        assertFalse(RefreshPolicy.needsRefresh(paused,later.plusSeconds(1800)))
    }
}
