package ua.svitlo.app

import org.junit.Assert.*
import org.junit.Test
import ua.svitlo.app.domain.*
import java.time.Instant

class WidgetFocusCardTest {
    private val now = Instant.parse("2026-10-22T09:00:00Z") // Kyiv 12:00
    private val home = SavedAddress("home", "Дім", AddressItem("1", "Місто"), AddressItem("2", "Вулиця"), AddressItem("3", "1"))
    private val work = home.copy(id = "work", title = "Робота")
    private val day = now.atZone(KYIV).toLocalDate()
    private fun schedule(slots: List<Slot>, tomorrow: List<Slot> = listOf(Slot(0, 1440, PowerState.NO_SCHEDULED_OUTAGE)),
                         incident: CurrentOutage? = null, fetched: Instant = now) =
        Schedule(fetched.toEpochMilli(), listOf(ScheduleDay("", day.toString(), slots),
            ScheduleDay("", day.plusDays(1).toString(), tomorrow)), emptyList(), incident)
    private val calm = listOf(Slot(0, 1440, PowerState.NO_SCHEDULED_OUTAGE))
    private fun state(schedule: Schedule, second: Schedule? = null) = AppState(addresses = listOfNotNull(home, work.takeIf { second != null }),
        schedules = listOfNotNull(CachedSchedule(home.id, schedule), second?.let { CachedSchedule(work.id, it) }))
    private fun card(state: AppState) = WidgetFocusCard.create(state, NotificationChart.create(state, now))

    @Test fun `calm missing or stale schedules never create a card`() {
        assertNull(card(state(schedule(calm))))
        assertNull(card(AppState(addresses = listOf(home))))
        assertNull(card(state(schedule(listOf(Slot(0, 1440, PowerState.CONFIRMED_OUTAGE)), fetched = now.minusSeconds(7200)))))
    }
    @Test fun `single card chooses current emergency rather than all addresses`() {
        val emergency = schedule(calm, incident = CurrentOutage("", true, now.minusSeconds(3600).toEpochMilli(), now.plusSeconds(7200).toEpochMilli(), "", ""))
        val future = schedule(listOf(Slot(0, 780, PowerState.NO_SCHEDULED_OUTAGE), Slot(780, 900, PowerState.CONFIRMED_OUTAGE), Slot(900, 1440, PowerState.NO_SCHEDULED_OUTAGE)))
        val card = requireNotNull(card(state(future, emergency)))
        assertEquals(work, card.row.address)
        assertEquals("Аварійне зараз", card.stateLabel)
        assertEquals("Відновлення сьогодні, орієнтовно", card.label)
        assertEquals("14:00", card.time)
        assertTrue(card.emergency)
        assertEquals(1, card.otherEvents)
    }
    @Test fun `late restoration uses full source and an explicit date rather than chart edge`() {
        val incident = CurrentOutage("", true, now.minusSeconds(3600).toEpochMilli(), now.plusSeconds(30 * 3600).toEpochMilli(), "", "")
        val card = requireNotNull(card(state(schedule(calm, incident = incident))))
        assertEquals("18:00", card.time)
        assertEquals("Відновлення завтра, орієнтовно", card.label)
        assertEquals("Аварійне зараз", card.stateLabel)
        assertEquals("Почалося сьогодні о 11:00", card.caption)
    }
    @Test fun `expired estimates and stale reports never promise a restoration`() {
        val incident = CurrentOutage("", true, now.minusSeconds(3600).toEpochMilli(), now.minusSeconds(1).toEpochMilli(), "", "")
        val expired = requireNotNull(card(state(schedule(calm, incident = incident))))
        assertEquals("—", expired.time); assertFalse(expired.emergency)
        assertEquals("Стан уточнюється", expired.label)
        val stale = requireNotNull(card(state(schedule(calm, incident = incident.copy(estimatedEnd = now.plusSeconds(3600).toEpochMilli()), fetched = now.minusSeconds(7200)))))
        assertEquals("Повідомлення застаріло", stale.caption)
        assertEquals("—", stale.time)
    }
    @Test fun `current report without estimate has no invented time`() {
        val card = requireNotNull(card(state(schedule(calm, incident = CurrentOutage("", false, null, null, "", "")))))
        assertEquals("—", card.time)
        assertEquals("Відключення зараз", card.stateLabel)
        assertEquals("Час відновлення невідомий", card.label)
    }
    @Test fun `potential outage remains qualified when active or future`() {
        val active = requireNotNull(card(state(schedule(listOf(Slot(0, 840, PowerState.POSSIBLE_OUTAGE), Slot(840, 1440, PowerState.NO_SCHEDULED_OUTAGE))))))
        assertEquals("За графіком до", active.label)
        assertEquals("14:00", active.time)
        val tomorrow = requireNotNull(card(state(schedule(calm, listOf(Slot(0, 960, PowerState.NO_SCHEDULED_OUTAGE), Slot(960, 1020, PowerState.POSSIBLE_OUTAGE), Slot(1020, 1440, PowerState.NO_SCHEDULED_OUTAGE))))))
        assertEquals("Відключення за графіком", tomorrow.label)
        assertEquals("16:00", tomorrow.time)
        assertEquals("Завтра · до 17:00", tomorrow.caption)
    }
    @Test fun `schedule end only appears when source actually marks a non outage interval`() {
        val continuous = requireNotNull(card(state(schedule(listOf(Slot(0, 1440, PowerState.CONFIRMED_OUTAGE)), listOf(Slot(0, 1440, PowerState.CONFIRMED_OUTAGE))))))
        assertEquals("Зараз", continuous.time)
        assertEquals("Час завершення не вказаний", continuous.caption)
        val known = requireNotNull(card(state(schedule(listOf(Slot(0, 1440, PowerState.CONFIRMED_OUTAGE)), listOf(Slot(0, 960, PowerState.CONFIRMED_OUTAGE), Slot(960, 1440, PowerState.NO_SCHEDULED_OUTAGE))))))
        assertEquals("16:00", known.time)
        assertEquals("Завтра · за графіком", known.caption)
    }
    @Test fun `earliest event wins and midnight interval keeps its day`() {
        val late = schedule(listOf(Slot(0, 1380, PowerState.NO_SCHEDULED_OUTAGE), Slot(1380, 1440, PowerState.CONFIRMED_OUTAGE)),
            listOf(Slot(0, 60, PowerState.CONFIRMED_OUTAGE), Slot(60, 1440, PowerState.NO_SCHEDULED_OUTAGE)))
        val early = schedule(listOf(Slot(0, 780, PowerState.NO_SCHEDULED_OUTAGE), Slot(780, 900, PowerState.CONFIRMED_OUTAGE), Slot(900, 1440, PowerState.NO_SCHEDULED_OUTAGE)))
        val first = requireNotNull(card(state(late, early)))
        assertEquals(work.id, first.row.address.id)
        val midnight = requireNotNull(card(state(late)))
        assertEquals("23:00", midnight.time)
        assertEquals("Сьогодні · до 01:00 завтра", midnight.caption)
    }
    @Test fun `scheduled interval changing to possible does not promise restoration`() {
        val card = requireNotNull(card(state(schedule(listOf(Slot(0, 840, PowerState.CONFIRMED_OUTAGE),
            Slot(840, 960, PowerState.POSSIBLE_OUTAGE), Slot(960, 1440, PowerState.NO_SCHEDULED_OUTAGE))))))
        assertEquals("За графіком до", card.label)
        assertEquals("14:00", card.time)
        assertEquals("Сьогодні · далі за графіком", card.caption)
    }
    @Test fun `restoration tomorrow never makes current emergency sound like a future outage`() {
        val time = Instant.parse("2026-10-22T18:53:00Z")
        val end = Instant.parse("2026-10-23T03:25:00Z")
        val incident = CurrentOutage("",true,time.minusSeconds(5400).toEpochMilli(),end.toEpochMilli(),"","")
        val state = state(schedule(calm,incident = incident,fetched = time))
        val card = requireNotNull(WidgetFocusCard.create(state,NotificationChart.create(state,time)))
        assertEquals("Аварійне зараз",card.stateLabel)
        assertEquals("Відновлення завтра, орієнтовно",card.label)
        assertEquals("06:25",card.time)
        assertEquals("Почалося сьогодні о 20:23",card.caption)
    }
}
