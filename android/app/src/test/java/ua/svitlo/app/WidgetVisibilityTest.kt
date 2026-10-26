package ua.svitlo.app

import org.junit.Assert.*
import org.junit.Test
import ua.svitlo.app.domain.*
import java.time.Instant

class WidgetVisibilityTest {
    private val now = Instant.parse("2026-10-22T09:00:00Z")
    private val window = notificationWindow(now)
    private val address = SavedAddress("home","Дім",AddressItem("1","Вінниця"),AddressItem("2","Вулиця"),AddressItem("3","15"))
    private fun row(state: PowerState = PowerState.NO_SCHEDULED_OUTAGE,status: String = "") =
        ChartRow(address,status,state,listOf(ChartSpan(window.start,window.end,state)))
    private fun chart(row: ChartRow) = NotificationChart(window.start,window.end,now,listOf(row),1,listOf(address.title))

    @Test fun `calm unknown missing publication loading and errors never wake an adaptive widget`() {
        for (status in listOf("","Дані застаріли","Немає графіка","Отримуємо графік…","Збій отримання","Невідомий стан")) {
            val row = row(PowerState.UNKNOWN,status).copy(unpublishedFrom = now.plusSeconds(3600))
            assertTrue("$status is not an outage",WidgetVisibility.rows(chart(row),false).isEmpty())
        }
        assertTrue(WidgetVisibility.rows(chart(row()),false).isEmpty())
    }
    @Test fun `active and future confirmed possible and emergency events are relevant`() {
        for (state in listOf(PowerState.CONFIRMED_OUTAGE,PowerState.POSSIBLE_OUTAGE,PowerState.EMERGENCY_OUTAGE)) {
            val active = row(state)
            assertTrue(WidgetVisibility.relevant(active,chart(active)))
            val future = row().copy(spans = listOf(ChartSpan(now.plusSeconds(60),window.end,state)))
            assertTrue(WidgetVisibility.relevant(future,chart(future)))
        }
    }
    @Test fun `an outage ending exactly now and recent history cannot keep the widget visible`() {
        for (end in listOf(now,now.minusSeconds(1))) {
            val past = row().copy(spans = listOf(ChartSpan(window.start,end,PowerState.CONFIRMED_OUTAGE),
                ChartSpan(end,window.end,PowerState.NO_SCHEDULED_OUTAGE)))
            assertFalse(WidgetVisibility.relevant(past,chart(past)))
        }
    }
    @Test fun `a known future event outside the viewport gets a compact precise status`() {
        val next = row().copy(nextOutageAt = now.plusSeconds(28*3600),nextOutageState = PowerState.POSSIBLE_OUTAGE)
        assertTrue(WidgetVisibility.relevant(next,chart(next)))
        assertFalse(WidgetVisibility.hasTimeline(next,chart(next)))
        assertEquals("За графіком завтра · 16:00",WidgetVisibility.upcomingStatus(next,chart(next)))
    }
    @Test fun `unresolved incidents remain visible without claiming recovery`() {
        for (status in listOf("Аварійне · дані застаріли","Відключення · час уточнюється","Відключення · дані застаріли")) {
            val pending = row(PowerState.UNKNOWN,status)
            assertTrue(WidgetVisibility.relevant(pending,chart(pending)))
            assertNull(WidgetVisibility.upcomingStatus(pending,chart(pending)))
        }
    }
    @Test fun `always visible is explicit and selected address order is preserved`() {
        val calm = row()
        val active = row(PowerState.CONFIRMED_OUTAGE).copy(address = address.copy(id = "work"))
        val possible = row(PowerState.POSSIBLE_OUTAGE).copy(address = address.copy(id = "parents"))
        val chart = chart(calm).copy(rows = listOf(calm,possible,active),total = 3)
        assertEquals(listOf(possible,active),WidgetVisibility.rows(chart,false))
        assertEquals(chart.rows,WidgetVisibility.rows(chart,true))
    }
    @Test fun `published event after a gap is found without parsing Ukrainian status text`() {
        val day = now.atZone(KYIV).toLocalDate()
        val schedule = Schedule(now.toEpochMilli(),listOf(
            ScheduleDay("",day.toString(),listOf(Slot(0,780,PowerState.NO_SCHEDULED_OUTAGE))),
            ScheduleDay("",day.plusDays(1).toString(),listOf(Slot(960,1080,PowerState.POSSIBLE_OUTAGE)))),emptyList())
        val state = AppState(addresses = listOf(address),schedules = listOf(CachedSchedule(address.id,schedule)))
        val chart = NotificationChart.create(state,now)
        val row = chart.rows.single()
        assertEquals(now.plusSeconds(28*3600),row.nextOutageAt)
        assertTrue(WidgetVisibility.relevant(row,chart))
        assertEquals("За графіком завтра · 16:00",WidgetVisibility.upcomingStatus(row,chart))
    }
}
