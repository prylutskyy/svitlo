package ua.svitlo.app

import org.junit.Assert.*
import org.junit.Test
import ua.svitlo.app.domain.*
import java.time.Duration
import java.time.Instant

class TimelineNavigationTest {
    private val now = Instant.parse("2026-10-26T07:00:00Z")
    private val window = notificationWindow(now)
    @Test fun `next planned event and continuing outage are different signals`() {
        val current = ChartSpan(window.start.minusSeconds(300),window.end.plusSeconds(3600),PowerState.SCHEDULED_OUTAGE)
        val later = ChartSpan(window.end.plusSeconds(7200),window.end.plusSeconds(10800),PowerState.SCHEDULED_OUTAGE)
        assertEquals(current,TimelineNavigation.continuing(listOf(current,later),window))
        assertEquals(later,TimelineNavigation.next(listOf(current,later),window))
        assertNull(TimelineNavigation.next(listOf(current),window))
        assertNull(TimelineNavigation.continuing(listOf(later),window))
    }
    @Test fun `jump preserves 12 hour scale and gives one hour of context`() {
        val at = now.plusSeconds(29*3600)
        val shifted = TimelineNavigation.around(at)
        assertEquals(Duration.ofHours(12),Duration.between(shifted.start,shifted.end))
        assertEquals(at.minusSeconds(3600),shifted.start)
        assertTrue(at < shifted.end)
        assertTrue(TimelineNavigation.ticks(shifted).all { it.atZone(KYIV).hour%3 == 0 })
    }
    @Test fun `long outage navigation keeps a twelve hour window`() {
        val span = ChartSpan(now.minusSeconds(6*3600),now.plusSeconds(25*3600),PowerState.EMERGENCY_OUTAGE)
        val next = TimelineNavigation.around(window.end.plusSeconds(3600))
        assertEquals(window.end,next.start)
        assertEquals(Duration.ofHours(12),Duration.between(next.start,next.end))
        assertEquals(span,TimelineNavigation.continuing(listOf(span),next))
    }
    @Test fun `actual small and landscape dimensions choose distinct widget compositions`() {
        assertEquals(WidgetLayout.COMPACT,WidgetLayout.forSize(110,110))
        assertEquals(WidgetLayout.WIDE,WidgetLayout.forSize(250,110))
        assertEquals(WidgetLayout.LARGE,WidgetLayout.forSize(250,180))
        assertEquals(WidgetLayout.COMPACT,WidgetLayout.forSize(130,56))
    }
    @Test fun `selection reports actual minute boundaries outside visible window`() {
        val start = now.minusSeconds(2*3600+13*60)
        val end = now.plusSeconds(4*3600+47*60)
        val schedule = Schedule(now.toEpochMilli(),emptyList(),emptyList(),
            CurrentOutage("Аварійне відключення",true,start.toEpochMilli(),end.toEpochMilli(),"",""))
        val spans = schedule.inspectionSpans(window,now)
        val selection = requireNotNull(TimelineNavigation.at(spans,now))
        assertEquals(start,selection.start)
        assertEquals(end,selection.end)
        assertEquals("Сьогодні 06:47 — 13:47",TimelineNavigation.range(selection,now))
    }
    @Test fun `unknown future never advertises an outage arrow`() {
        val unknown = ChartSpan(window.end,window.end.plusSeconds(3600),PowerState.UNKNOWN)
        assertNull(TimelineNavigation.next(listOf(unknown),window))
        assertNull(TimelineNavigation.continuing(listOf(unknown),window))
    }
    @Test fun `crossing midnight includes the next day in exact readout`() {
        val span = ChartSpan(Instant.parse("2026-10-26T21:15:00Z"),Instant.parse("2026-10-27T00:45:00Z"),PowerState.SCHEDULED_OUTAGE)
        assertEquals("Сьогодні 23:15 — завтра 02:45",TimelineNavigation.range(span,now))
    }
    @Test fun `widget normalization preserves scale while selecting one location`() {
        val address = SavedAddress("home","Дім",AddressItem("1",""),AddressItem("2",""),AddressItem("3",""))
        val app = AppState(addresses = listOf(address))
        val old = WidgetConfig(7,listOf("home"),monochrome = true,contentScale = 1.4f,verticalDensity = 2,
            transparentBackground = true,alwaysShowGraphs = true,surfaceStyle = "dark")
        assertEquals(WidgetConfig(7,listOf("home"),surfaceStyle = "dark",transparentBackground = true,contentScale = 1.4f),old.standardized(app,now))
    }
}
