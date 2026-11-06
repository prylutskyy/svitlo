package ua.svitlo.app

import org.junit.Assert.*
import org.junit.Test
import ua.svitlo.app.domain.*
import ua.svitlo.app.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.time.Instant

class ScheduleExperienceTest {
    private val now=Instant.parse("2026-11-06T10:00:00Z") // 12:00 Kyiv
    private val home=SavedAddress("home","Дім",AddressItem("1","Вінниця"),AddressItem("2","Шосе"),AddressItem("3","81/2"),feedId="voe/Vinnytsiaoblenerho",feedGroup="GPV3.2")
    private fun plan()=Schedule(now.minusSeconds(60).toEpochMilli(),listOf(ScheduleDay("","2026-11-06",listOf(
        Slot(0,720,PowerState.SCHEDULED_OUTAGE),Slot(720,1440,PowerState.NO_SCHEDULED_OUTAGE)))),emptyList(),sourceUrl="https://raw.githubusercontent.com/example/plan")
    private fun state(plan:Schedule)=AppState(addresses=listOf(home),currentAddressId=home.id,eventMonitoring=true,schedules=listOf(CachedSchedule(home.id,plan)))
    @Test fun scheduledEndUsesWallClockEvenWhenUpstreamTimestampHasNotChanged() {
        val plan=plan()
        val observed=RestorationPolicy.observe(state(plan),now.minusSeconds(30))
        assertEquals(1,observed.observations!!.size)
        val key=RestorationPolicy.notificationKey(observed,home.id,plan,now)
        assertNotNull(key);assertTrue(key!!.endsWith(":plan-ended"))
        assertEquals(key,RestorationPolicy.notificationKey(observed,home.id,plan,now.plusSeconds(1)))
        assertNull(RestorationPolicy.notificationKey(state(plan),home.id,plan,now))
        assertNull(RestorationPolicy.notificationKey(observed,home.id,plan,now.minusSeconds(1)))
        assertNull(RestorationPolicy.notificationKey(observed,home.id,plan,now.plusSeconds(7200)))
        assertNull(RestorationPolicy.notificationKey(observed,home.id,plan.copy(days=emptyList()),now))
    }
    @Test fun unavailableAddressCheckCannotBePresentedAsAddressRestoration() {
        val incident=CurrentOutage("Аварія",true,now.minusSeconds(120).toEpochMilli(),null,"","")
        val old=plan().copy(currentOutage=incident,outagesExpected=true,outagesFetchedAt=now.minusSeconds(30).toEpochMilli())
        val observed=RestorationPolicy.observe(state(old),now.minusSeconds(30))
        assertNull(RestorationPolicy.notificationKey(observed,home.id,plan().copy(outagesExpected=true,outagesUnavailable=true),now))
        val clear=plan().copy(outagesExpected=true,outagesFetchedAt=now.toEpochMilli())
        assertTrue(RestorationPolicy.notificationKey(observed,home.id,clear,now)!!.endsWith(":restored"))
    }
    @Test fun unknownGapDoesNotCountAsPlannedOutageOrProduceJournalEvent() {
        val spans=PowerHistory.resolve(listOf(HistoryInterval(0,60_000,PowerState.SCHEDULED_OUTAGE),HistoryInterval(120_000,180_000,PowerState.SCHEDULED_OUTAGE)),0,240_000)
        val report=HistoryReport(0,240_000,spans,emptyList(),0)
        assertEquals(120_000,report.outageMillis)
        val events=HistoryJournal.events(report)
        assertEquals(2,events.size)
        assertTrue(events.all{it.reason=="За опублікованим графіком"})
        assertEquals(120_000,events.sumOf{it.lastSeen-it.start})
    }
    @Test fun durationsHaveUnitsAndSupportLongHistory() {
        assertEquals("0 хв",historyDuration(0))
        assertEquals("< 1 хв",historyDuration(1))
        assertEquals("35 хв",historyDuration(35*60_000L))
        assertEquals("2 год 5 хв",historyDuration(125*60_000L))
        assertEquals("2 д 3 год",historyDuration(51*3600_000L))
        assertTrue(historyDuration(62*86400_000L).startsWith("≈ 2 міс."))
    }
    @Test fun customColorsValidateHexAndKeepPrimaryReadableInBothThemes() {
        assertEquals(0xFF12AB34,parseAccentHex("#12ab34"))
        assertNull(parseAccentHex("123"));assertNull(parseAccentHex("GGGGGG"))
        for(dark in listOf(false,true))for(seed in listOf(0xFFFFFFFF,0xFF000000,0xFFFFFF00,0xFF0000FF,0xFFFF0080)) {
            val scheme=accentScheme(dark,AppAccent.CUSTOM,seed)
            fun contrast(a:Color,b:Color)=(maxOf(a.luminance(),b.luminance())+.05f)/(minOf(a.luminance(),b.luminance())+.05f)
            assertTrue("$dark $seed: primary/background",contrast(scheme.primary,scheme.background)>=4.4f)
            assertTrue("$dark $seed: primary/onPrimary",contrast(scheme.primary,scheme.onPrimary)>=4.5f)
        }
    }
}
