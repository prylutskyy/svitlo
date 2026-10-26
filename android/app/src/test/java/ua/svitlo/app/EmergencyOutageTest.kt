package ua.svitlo.app

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import ua.svitlo.app.data.VoeParser
import ua.svitlo.app.domain.*
import java.time.Instant

class EmergencyOutageTest {
    private val now = Instant.parse("2026-10-18T18:00:00Z")
    private val address = SavedAddress("a", "Дім", AddressItem("510100000", "Вінниця"),
        AddressItem("1587", "Гайдамацька"), AddressItem("41668", "9"))
    private fun fixture() = javaClass.classLoader!!.getResource("emergency-haidamatska-9.html")!!.readText()
    private fun parsed() = VoeParser.schedule(fixture(), now)
    private fun state(schedule: Schedule) = AppState(addresses = listOf(address),
        schedules = listOf(CachedSchedule(address.id, schedule)), session = TrackingSession(now.plusSeconds(7200).toEpochMilli()))

    @Test fun `live address response has an incident independent of its all clear hourly table`() {
        val value = parsed()
        val incident = requireNotNull(value.currentOutage)
        assertTrue(incident.emergency)
        assertEquals("Аварійне відключення", incident.reason)
        assertEquals(Instant.parse("2026-10-18T16:56:00Z").toEpochMilli(), incident.startedAt)
        assertEquals(Instant.parse("2026-10-19T14:56:00Z").toEpochMilli(), incident.estimatedEnd)
        assertTrue(value.days.single().slots.all { it.state == PowerState.NO_SCHEDULED_OUTAGE })
        assertEquals(PowerState.EMERGENCY_OUTAGE, value.powerAt(now, now))
        assertTrue(incident.warning.contains("можуть не діяти"))
    }
    @Test fun `minute boundary and cross midnight incident override are exact`() {
        val value = parsed()
        val start = Instant.parse("2026-10-18T16:56:00Z")
        val spans = value.chartSpans(start.minusSeconds(3600), start.plusSeconds(3600), now)
        assertEquals(start, spans.first { it.state == PowerState.EMERGENCY_OUTAGE }.start)
        assertEquals(PowerState.NO_SCHEDULED_OUTAGE, value.powerAt(start.minusSeconds(1), now))
        assertEquals(PowerState.EMERGENCY_OUTAGE, value.powerAt(Instant.parse("2026-10-18T23:00:00Z"), now))
    }
    @Test fun `estimated finish switches to unknown and never automatically to green`() {
        val end = Instant.parse("2026-10-19T14:56:00Z")
        val later = end.minusSeconds(600)
        val value = parsed().copy(fetchedAt = later.toEpochMilli())
        assertFalse(value.stale(later)) // current incident is valid without a daily table
        val spans = value.chartSpans(end.minusSeconds(3600), end.plusSeconds(3600), later)
        assertEquals(end, spans.first { it.state == PowerState.UNKNOWN }.start)
        assertEquals(PowerState.UNKNOWN, value.powerAt(end, end))
        val row = NotificationChart.create(state(value), end).rows.single()
        assertTrue(row.status.contains("час уточнюється"))
        assertTrue(row.emergency)
    }
    @Test fun `stale incident remains labelled but cannot paint a confirmed state`() {
        val later = now.plusSeconds(3601)
        val row = NotificationChart.create(state(parsed()), later).rows.single()
        assertEquals("Аварійне · дані застаріли", row.status)
        assertTrue(row.spans.all { it.state == PowerState.UNKNOWN })
    }
    @Test fun `missing and invalid times do not erase an explicit emergency`() {
        val bad = fixture().replace("19:56 2026.10.18", "99:56 2026.10.18")
            .replace("17:56 2026.10.19", "17:56 2026.02.30")
        val value = VoeParser.schedule(bad, now)
        assertNotNull(value.currentOutage)
        assertNull(value.currentOutage!!.startedAt)
        assertNull(value.currentOutage.estimatedEnd)
        assertEquals(PowerState.EMERGENCY_OUTAGE, value.powerAt(now, now))
    }
    @Test fun `old persisted cache and no incident response remain compatible`() {
        val old = """{"fetchedAt":${now.toEpochMilli()},"days":[],"messages":[]}"""
        assertNull(Gson().fromJson(old, Schedule::class.java).currentOutage)
        val clear = VoeParser.schedule(javaClass.classLoader!!.getResource("schedule.html")!!.readText(), now)
        assertNull(clear.currentOutage)
        assertEquals(PowerState.NO_SCHEDULED_OUTAGE, clear.powerAt(now, now))
        val restored = Gson().fromJson(Gson().toJson(state(parsed())), AppState::class.java)
        assertEquals(parsed().currentOutage, restored.schedule("a")!!.currentOutage)
    }
    @Test fun `emergency gets overflow priority and tomorrow date in its status`() {
        val homes = (1..5).map { address.copy(id = "$it", title = "Адреса $it") }
        val value = state(parsed()).copy(addresses = homes, schedules = listOf(CachedSchedule("5", parsed())))
        val chart = NotificationChart.create(value, now)
        assertEquals("5", chart.rows.first().address.id)
        assertEquals("Аварійне · ще ≈ 20 год 56 хв", chart.rows.first().status)
    }
    @Test fun `local boundary schedules exact estimate without polling more often`() {
        val end = Instant.parse("2026-10-19T14:56:00Z")
        val at = end.minusSeconds(60)
        val value = parsed().copy(fetchedAt = at.toEpochMilli())
        val selected = state(value).copy(session = TrackingSession(end.plusSeconds(3600).toEpochMilli()))
        assertEquals(end, EnergyPolicy.nextBoundary(selected, at))
    }
}
