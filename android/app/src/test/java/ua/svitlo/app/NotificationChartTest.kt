package ua.svitlo.app

import org.junit.Assert.*
import org.junit.Test
import ua.svitlo.app.domain.*
import java.time.*

class NotificationChartTest {
    private val now = Instant.parse("2026-10-18T13:20:00Z") // Kyiv 16:20
    private val address = SavedAddress("a", "Дім", AddressItem("1", "Вінниця"), AddressItem("2", "Соборна"), AddressItem("3", "1"))
    private fun schedule(date: String, slots: List<Slot>, at: Instant = now) = Schedule(at.toEpochMilli(), listOf(ScheduleDay(date, date, slots)), emptyList())
    private fun state(value: Schedule) = AppState(addresses = listOf(address), schedules = listOf(CachedSchedule("a", value)))

    @Test fun `real outage boundaries and next change are preserved`() {
        val value = schedule("2026-10-18", listOf(Slot(0, 1080, PowerState.NO_SCHEDULED_OUTAGE), Slot(1080, 1200, PowerState.CONFIRMED_OUTAGE), Slot(1200, 1440, PowerState.NO_SCHEDULED_OUTAGE)))
        val chart = NotificationChart.create(state(value), now)
        assertEquals(now.minusSeconds(3600), chart.start)
        assertEquals("Відключення о 18:00", chart.rows.single().status)
        val outage = chart.rows.single().spans.single { it.state == PowerState.CONFIRMED_OUTAGE }
        assertEquals(Instant.parse("2026-10-18T15:00:00Z"), outage.start)
        assertEquals(Instant.parse("2026-10-18T17:00:00Z"), outage.end)
    }
    @Test fun `stale data never paints a known outage or safe interval`() {
        val old = schedule("2026-10-18", listOf(Slot(0, 1440, PowerState.CONFIRMED_OUTAGE)), now.minusSeconds(3601))
        val row = NotificationChart.create(state(old), now).rows.single()
        assertEquals("Дані застаріли", row.status)
        assertTrue(row.spans.all { it.state == PowerState.UNKNOWN })
    }
    @Test fun `overflow keeps a current outage visible without dropping monitored addresses`() {
        val homes = (0..4).map { address.copy(id = "a$it") }
        val current = state(schedule("2026-10-18", listOf(Slot(0, 1440, PowerState.CONFIRMED_OUTAGE))))
            .copy(addresses = homes, schedules = listOf(CachedSchedule("a4", schedule("2026-10-18", listOf(Slot(0, 1440, PowerState.CONFIRMED_OUTAGE))))))
        val chart = NotificationChart.create(current, now)
        assertEquals(5, chart.total); assertEquals(2, chart.hidden)
        assertEquals("a4", chart.rows.first().address.id)
    }
    @Test fun `rolling window crosses midnight and DST without changing elapsed scale`() {
        val fallback = Instant.parse("2026-10-25T00:20:00Z")
        val value = schedule("2026-10-25", listOf(Slot(0, 1440, PowerState.NO_SCHEDULED_OUTAGE)), fallback)
        val chart = NotificationChart.create(state(value), fallback)
        assertEquals(fallback.plusSeconds(11 * 3600), chart.end)
        assertEquals(Duration.ofHours(12), Duration.between(chart.start, chart.end))
        val late = Instant.parse("2026-10-18T20:20:00Z")
        val row = NotificationChart.create(state(schedule("2026-10-18", listOf(Slot(0, 1440, PowerState.NO_SCHEDULED_OUTAGE)), late)), late).rows.single()
        assertEquals(PowerState.UNKNOWN, row.spans.last().state)
        assertEquals(Instant.parse("2026-10-18T21:00:00Z"), row.spans.last().start)
        assertEquals("", row.status)
    }
    @Test fun `tomorrow never stretches axis and precise slots enter rolling viewport`() {
        val late = Instant.parse("2026-10-18T20:20:00Z")
        val value = schedule("2026-10-18", listOf(Slot(0, 1440, PowerState.NO_SCHEDULED_OUTAGE)))
            .let { it.copy(days = it.days + ScheduleDay("Завтра", "2026-10-19", listOf(Slot(0, 427, PowerState.NO_SCHEDULED_OUTAGE), Slot(427, 1440, PowerState.POSSIBLE_OUTAGE)))) }
        val chart = NotificationChart.create(state(value.copy(fetchedAt = late.toEpochMilli())), late)
        assertEquals(late.plusSeconds(11 * 3600), chart.end)
        assertEquals(Instant.parse("2026-10-19T04:07:00Z"), chart.rows.single().spans.first { it.state == PowerState.POSSIBLE_OUTAGE }.start)
        assertTrue(value.hasTomorrow(now))
    }
    @Test fun `long emergency continues past fixed viewport without promising restoration`() {
        val end = now.plusSeconds(36 * 3600)
        val value = schedule("2026-10-18", emptyList()).copy(currentOutage = CurrentOutage("Аварія", true,
            now.minusSeconds(3600).toEpochMilli(), end.toEpochMilli(), "", ""))
        val chart = NotificationChart.create(state(value), now)
        assertEquals(now.plusSeconds(11 * 3600), chart.end)
        assertTrue(chart.rows.single().continues)
        assertTrue(chart.rows.single().status.contains("1 д 12 год"))
        assertTrue(chart.rows.single().spans.all { it.state == PowerState.EMERGENCY_OUTAGE })
        assertEquals(PowerState.UNKNOWN, value.powerAt(end, now))
        assertEquals(NotificationChart.create(state(schedule("2026-10-18", emptyList())), now).end, chart.end)
    }
    @Test fun `future rows never need a now marker and local hour advances without fetching`() {
        val early = todayWindow(Instant.parse("2026-10-18T04:20:00Z"))
        assertEquals("07:00", early.label(early.start))
        assertEquals("24:00", early.label(early.end))
        assertEquals(early.end, todayWindow(Instant.parse("2026-10-18T05:20:00Z")).end)
    }

    @Test fun `now fraction stays fixed as clock moves by minutes`() {
        listOf(now, now.plusSeconds(60), now.plusSeconds(2400)).forEach { at ->
            val window = notificationWindow(at)
            assertEquals(3600L, Duration.between(window.start, at).seconds)
            assertEquals(43200L, Duration.between(window.start, window.end).seconds)
        }
    }

    @Test fun `outage ending exactly at viewport edge has no continuation arrow`() {
        val at = Instant.parse("2026-10-18T04:00:00Z") // 07:00 to 18:00 ahead
        val value = schedule("2026-10-18", listOf(Slot(0,1080,PowerState.CONFIRMED_OUTAGE), Slot(1080,1440,PowerState.NO_SCHEDULED_OUTAGE)), at)
        assertFalse(NotificationChart.create(state(value), at).rows.single().continues)
    }

    @Test fun `current location drives collapsed context even when another address has emergency`() {
        val work = address.copy(id = "b", title = "Робота")
        val value = schedule("2026-10-18", listOf(Slot(0,1440,PowerState.NO_SCHEDULED_OUTAGE)))
        val input = state(value).copy(addresses = listOf(address,work), currentAddressId = work.id)
        val chart = NotificationChart.create(input, now, 1)
        assertEquals(work.id, chart.focus!!.address.id)
        assertEquals(work.id, chart.rows.single().address.id)
    }
}
