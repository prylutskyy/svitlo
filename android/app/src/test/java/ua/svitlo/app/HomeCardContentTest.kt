package ua.svitlo.app

import org.junit.Assert.*
import org.junit.Test
import ua.svitlo.app.domain.*
import ua.svitlo.app.ui.*
import java.time.Instant

class HomeCardContentTest {
    private val now = Instant.parse("2026-11-06T11:00:00Z") // 13:00 Kyiv
    private val place = SavedAddress("home","Черга 1.2",AddressItem("feed","Вінницька · Вінницяобленерго"),
        AddressItem("queue","Черга"),AddressItem("GPV3.2","3.2"),feedId="voe/Vinnytsiaoblenerho",feedGroup="GPV3.2")
    private fun plan() = Schedule(now.toEpochMilli(),listOf(ScheduleDay("","2026-11-06",listOf(
        Slot(0,840,PowerState.NO_SCHEDULED_OUTAGE),Slot(840,1080,PowerState.SCHEDULED_OUTAGE),
        Slot(1080,1200,PowerState.NO_SCHEDULED_OUTAGE),Slot(1200,1320,PowerState.SCHEDULED_OUTAGE),
        Slot(1320,1440,PowerState.NO_SCHEDULED_OUTAGE)))),emptyList(),queue="3.2",sourceUrl="https://raw.githubusercontent.com/example/feed")
    private fun row(schedule:Schedule,at:Instant = now) = NotificationChart.create(AppState(addresses=listOf(place),
        schedules=listOf(CachedSchedule(place.id,schedule))),at).rows.single()
    @Test fun automaticTitleUsesCurrentQueueAndShowsOperatorOnce() {
        val identity=homeIdentity(place)
        assertEquals("Черга 3.2",identity.title);assertEquals("Вінницяобленерго",identity.subtitle)
        assertFalse(identity.toString().contains("1.2"))
    }
    @Test fun customNameIsPreservedAndCurrentQueueAppearsOnlyInSubtitle() {
        val identity=homeIdentity(place.copy(title="Дім"))
        assertEquals("Дім",identity.title);assertEquals("Черга 3.2 · Вінницяобленерго",identity.subtitle)
    }
    @Test fun missingAddressCheckDoesNotErasePublishedClearIntervalOnHome() {
        val source=plan().copy(outagesExpected=true,outagesUnavailable=true)
        val gap=Instant.parse("2026-11-06T17:00:00Z") // 19:00 between two outages
        assertEquals(PowerState.UNKNOWN,source.powerAt(gap,now))
        val displayed=homePlan(place,source)!!
        assertEquals(PowerState.NO_SCHEDULED_OUTAGE,displayed.powerAt(gap,now))
        assertTrue(source.outagesExpected);assertTrue(source.outagesUnavailable) // No change to other screens/data.
    }
    @Test fun unknownHistoryDoesNotReplaceAnExplicitlyPublishedClearInterval() {
        val start=now.minusSeconds(600);val end=now.minusSeconds(300)
        val history=listOf(HistoryInterval(start.toEpochMilli(),end.toEpochMilli(),PowerState.UNKNOWN))
        val spans=PowerHistory.overlay(plan().chartSpans(start,now,now),homeHistory(history),now)
        assertTrue(spans.all {it.state==PowerState.NO_SCHEDULED_OUTAGE})
    }
    @Test fun actualMissingCellsAndStaleDataRemainUnknown() {
        val missing=plan().copy(days=listOf(ScheduleDay("","2026-11-06",listOf(Slot(840,1080,PowerState.SCHEDULED_OUTAGE)))))
        assertEquals(PowerState.UNKNOWN,homePlan(place,missing)!!.powerAt(now,now))
        assertEquals("Графік уточнюється",homeStatus(missing,row(missing),now))
        val old=plan().copy(fetchedAt=now.minusSeconds(7200).toEpochMilli())
        assertEquals(PowerState.UNKNOWN,homePlan(place,old)!!.powerAt(now,now))
    }
    @Test fun statusShowsNextIntervalAndActiveIntervalWithoutExtraBadge() {
        val plan=plan()
        assertEquals("Наступне 14:00–18:00",homeStatus(plan,row(plan),now))
        val at=Instant.parse("2026-11-06T13:00:00Z") // 15:00 with a fresh check
        val fresh=plan.copy(fetchedAt=at.toEpochMilli())
        assertEquals("За планом до 18:00",homeStatus(fresh,row(fresh,at),at))
    }
    @Test fun homeProjectionDoesNotInventMissingPublishedPlan() {
        val unavailable=plan().copy(planUnavailable=true,outagesExpected=true,outagesUnavailable=true)
        assertEquals(PowerState.UNKNOWN,homePlan(place,unavailable)!!.powerAt(now,now))
        assertNull(homePlan(place,null))
    }
    @Test fun unavailableAddressCheckDoesNotHidePublishedEndTimeInWidget() {
        val at=Instant.parse("2026-11-06T13:00:00Z")
        val source=plan().copy(fetchedAt=at.toEpochMilli(),outagesExpected=true,outagesUnavailable=true)
        val state=AppState(addresses=listOf(place),schedules=listOf(CachedSchedule(place.id,source)))
        val card=WidgetFocusCard.forWidget(state,NotificationChart.create(state,at),false)!!
        assertEquals("18:00",card.time)
        assertEquals("За графіком до",card.label)
    }
}
