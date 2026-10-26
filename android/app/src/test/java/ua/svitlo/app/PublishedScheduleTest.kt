package ua.svitlo.app

import org.junit.Assert.*
import org.junit.Test
import ua.svitlo.app.data.VoeParser
import ua.svitlo.app.domain.*
import java.time.Instant

/** Regression cases from the old site's actual address responses on 26 October. */
class PublishedScheduleTest {
    private fun html(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val kalynova = "schedule-kalynova-3-20261026.html"
    private val khmelnytske = "schedule-khmelnytske-81-2-20261026.html"
    private val now = Instant.parse("2026-10-26T07:56:40Z") // 09:56 Kyiv, UTC+2
    private val address = SavedAddress("home", "Дім", AddressItem("520681003", "Вінницькі Хутори"),
        AddressItem("1811", "Калинова"), AddressItem("396498", "3"))
    private fun state(schedule: Schedule) = AppState(addresses = listOf(address),
        schedules = listOf(CachedSchedule(address.id, schedule)), currentAddressId = address.id)

    @Test fun `no current incident message does not cancel evening GPV`() {
        val schedule = VoeParser.schedule(html(kalynova), now)
        assertNull(schedule.currentOutage)
        assertEquals(PowerState.NO_SCHEDULED_OUTAGE, schedule.powerAt(now, now))
        assertEquals(4, schedule.today(now)!!.slots.count { it.state == PowerState.SCHEDULED_OUTAGE })
        assertEquals(PowerState.SCHEDULED_OUTAGE, schedule.powerAt(Instant.parse("2026-10-26T14:00:00Z"), now))
        assertEquals(PowerState.NO_SCHEDULED_OUTAGE, schedule.powerAt(Instant.parse("2026-10-26T16:00:00Z"), now))
        val app = state(schedule)
        val card = requireNotNull(WidgetFocusCard.create(app, NotificationChart.create(app, now)))
        assertEquals("За графіком", card.label)
        assertEquals("16:00–18:00", card.time)
        assertEquals("Сьогодні", card.caption)
        assertEquals("Наступна подія", card.stateLabel)
    }

    @Test fun `both observed confirm markers describe schedule without inventing certainty`() {
        val at = Instant.parse("2026-10-26T05:49:00Z") // 07:49 Kyiv
        val schedule = VoeParser.schedule(html(khmelnytske), at)
        assertEquals("1.2", schedule.queue)
        assertEquals(PowerState.SCHEDULED_OUTAGE, schedule.days.single().slots[14].state)
        assertEquals(PowerState.SCHEDULED_OUTAGE, schedule.days.single().slots[36].state)
        val incident = requireNotNull(schedule.currentOutage)
        assertEquals(Instant.parse("2026-10-26T05:00:00Z").toEpochMilli(), incident.startedAt)
        assertEquals(Instant.parse("2026-10-26T06:00:00Z").toEpochMilli(), incident.estimatedEnd)
        assertEquals("Відключення за ГПВ", incident.kindLabel)
        assertEquals(PowerState.CONFIRMED_OUTAGE, schedule.powerAt(at, at))
        val app = state(schedule)
        val card = requireNotNull(WidgetFocusCard.create(app, NotificationChart.create(app, at)))
        assertEquals("Відключення за ГПВ зараз", card.stateLabel)
        assertEquals("08:00", card.time)
        assertTrue(card.label.contains("орієнтовно"))
    }

    @Test fun `estimate passing never proves restoration or hides next scheduled event`() {
        val at = Instant.parse("2026-10-26T05:49:00Z")
        val schedule = VoeParser.schedule(html(khmelnytske), at)
        val observed = Instant.parse("2026-10-26T06:01:00Z")
        assertEquals(PowerState.UNKNOWN, schedule.powerAt(observed, observed))
        assertEquals(PowerState.SCHEDULED_OUTAGE, schedule.powerAt(Instant.parse("2026-10-26T16:00:00Z"), observed))
        val app = state(schedule)
        val card = requireNotNull(WidgetFocusCard.create(app, NotificationChart.create(app, observed)))
        assertEquals("Стан уточнюється", card.label)
        assertEquals("—", card.time)
    }

    @Test fun `fresh clear response removes incident but keeps future plan`() {
        val schedule = VoeParser.schedule(html(kalynova), now)
        val previous = schedule.copy(currentOutage = CurrentOutage("Аварійне відключення", true,
            now.minusSeconds(3600).toEpochMilli(), now.minusSeconds(60).toEpochMilli(), "", ""))
        val app = state(previous).copy(eventMonitoring = true)
        assertNotNull(RestorationPolicy.notificationKey(app, address.id, schedule))
        assertNull(RestorationPolicy.notificationKey(app, address.id, previous.copy(fetchedAt = now.plusSeconds(1).toEpochMilli())))
        assertEquals(PowerState.SCHEDULED_OUTAGE, schedule.powerAt(Instant.parse("2026-10-26T14:00:00Z"), now))
    }

    @Test fun `unknown marker and partial interval cannot become known plan or clear`() {
        val unknown = VoeParser.schedule(html(kalynova).replace("confirm_2", "confirm_99"), now)
        assertEquals(PowerState.UNKNOWN, unknown.days.single().slots[32].state)
        val partial = VoeParser.schedule(html(kalynova).replace("full_hour", "first_half"), now)
        assertEquals(PowerState.UNKNOWN, partial.days.single().slots[32].state)
        assertTrue(partial.days.single().slots[32].source.contains("first_half"))
    }

    @Test fun `missing data after outage is not restoration`() {
        val schedule = VoeParser.schedule(html(khmelnytske), now)
        assertNull(RestorationPolicy.notificationKey(state(schedule).copy(eventMonitoring = true), address.id,
            VoeParser.schedule("<div>Немає даних</div>", now)))
    }
}
