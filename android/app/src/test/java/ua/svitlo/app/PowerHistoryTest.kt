package ua.svitlo.app

import org.junit.Assert.*
import org.junit.Test
import ua.svitlo.app.domain.*
import java.time.Instant
import java.time.LocalDate

class PowerHistoryTest {
    @Test fun overlapsAreCountedOnceAndUnknownIsNotElectricity() {
        val spans = PowerHistory.resolve(listOf(
            HistoryInterval(10,90,PowerState.NO_SCHEDULED_OUTAGE),
            HistoryInterval(20,70,PowerState.CONFIRMED_OUTAGE),
            HistoryInterval(30,50,PowerState.EMERGENCY_OUTAGE),
            HistoryInterval(60,80,PowerState.POSSIBLE_OUTAGE)),0,100)
        val report = HistoryReport(0,100,spans,emptyList(),10)
        assertEquals(100L,spans.sumOf { it.end-it.start })
        assertEquals(20L,report.millis(PowerState.UNKNOWN))
        assertEquals(20L,report.millis(PowerState.EMERGENCY_OUTAGE))
        assertEquals(30L,report.millis(PowerState.CONFIRMED_OUTAGE))
        assertEquals(10L,report.millis(PowerState.POSSIBLE_OUTAGE))
        assertEquals(50L,report.outageMillis)
    }
    @Test fun coverageEndsAtFreshnessAndDoesNotIncludeFuture() {
        val now = Instant.parse("2026-10-19T10:00:00Z")
        val graph = Schedule(now.toEpochMilli(),listOf(ScheduleDay("","2026-10-19",listOf(
            Slot(0,1440,PowerState.NO_SCHEDULED_OUTAGE)))),emptyList())
        assertEquals(3_600_000L,PowerHistory.observed(graph,now.plusSeconds(7200).toEpochMilli()).sumOf { it.end-it.start })
        assertEquals(60_000L,PowerHistory.observed(graph,now.plusSeconds(60).toEpochMilli()).sumOf { it.end-it.start })
        assertTrue(PowerHistory.observed(graph,now.minusSeconds(1).toEpochMilli()).isEmpty())
    }
    @Test fun historicalEmergencySurvivesNewGraphButDoesNotOverwritePresentOrFuture() {
        val now = Instant.ofEpochMilli(50)
        val spans = listOf(ChartSpan(Instant.ofEpochMilli(0),Instant.ofEpochMilli(100),PowerState.NO_SCHEDULED_OUTAGE))
        val overlay = PowerHistory.overlay(spans,listOf(HistoryInterval(10,90,PowerState.EMERGENCY_OUTAGE)),now)
        assertTrue(overlay.any { it.state == PowerState.EMERGENCY_OUTAGE && it.end == now })
        assertTrue(overlay.filter { it.start >= now }.all { it.state == PowerState.NO_SCHEDULED_OUTAGE })
    }
    @Test fun dstDayUsesElapsedHoursInsteadOfAssumingTwentyFour() {
        val day = LocalDate.parse("2026-10-25")
        val start = day.atStartOfDay(KYIV).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(KYIV).toInstant().toEpochMilli()
        val spans = PowerHistory.resolve(listOf(HistoryInterval(start,end,PowerState.CONFIRMED_OUTAGE)),start,end)
        assertEquals(25 * 3_600_000L,spans.sumOf { it.end-it.start })
    }
}
