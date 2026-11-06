package ua.svitlo.app
import org.junit.Assert.*
import org.junit.Test
import ua.svitlo.app.domain.*
import ua.svitlo.app.ui.*
import java.time.Instant
import java.time.LocalDate

class DetailsIntervalsTest {
 private val now=Instant.parse("2026-11-06T11:00:00Z")
 private val today=LocalDate.parse("2026-11-06")
 private fun day(date:LocalDate,start:Int,end:Int)=ScheduleDay("",date.toString(),listOf(Slot(0,start,PowerState.NO_SCHEDULED_OUTAGE),Slot(start,end,PowerState.SCHEDULED_OUTAGE),Slot(end,1440,PowerState.NO_SCHEDULED_OUTAGE)).filter{it.endMinute>it.startMinute})
 private fun source()=Schedule(now.toEpochMilli(),listOf(day(today,480,600),day(today.plusDays(1),840,1080)),emptyList())
 @Test fun todayIncludesPastPublishedEventsAndTomorrowIsIndependent() {
  val a=detailsIntervals(source(),today,now)!!.single();val b=detailsIntervals(source(),today.plusDays(1),now)!!.single()
  assertEquals(8,a.start.atZone(KYIV).hour);assertEquals(10,a.end!!.atZone(KYIV).hour)
  assertEquals(14,b.start.atZone(KYIV).hour);assertEquals(18,b.end!!.atZone(KYIV).hour)
  assertEquals("Планове",a.label);assertFalse(a.estimated)
 }
 @Test fun unpublishedTomorrowIsNotAnAllClear() {
  assertNull(detailsIntervals(source().copy(days=listOf(day(today,480,600))),today.plusDays(1),now))
 }
 @Test fun fullyClearPublishedDayHasNoOutageRows() {
  val clear=source().copy(days=listOf(ScheduleDay("",today.toString(),listOf(Slot(0,1440,PowerState.NO_SCHEDULED_OUTAGE)))))
  assertTrue(detailsIntervals(clear,today,now)!!.isEmpty())
 }
 @Test fun missingAddressCheckDoesNotErasePublishedIntervalTimes() {
  val s=source().copy(outagesExpected=true,outagesUnavailable=true)
  assertEquals(2*3600,java.time.Duration.between(detailsIntervals(s,today,now)!!.single().start,detailsIntervals(s,today,now)!!.single().end).seconds.toInt())
 }
 @Test fun emergencyStaysDistinctFromQueuePlan() {
  val incident=CurrentOutage("Аварійне відключення",true,now.toEpochMilli(),now.plusSeconds(3600).toEpochMilli(),"","")
  val rows=detailsIntervals(source().copy(currentOutage=incident,reportedOutages=listOf(incident)),today,now)!!
  assertEquals(2,rows.size);assertEquals(1,rows.count{it.state==PowerState.EMERGENCY_OUTAGE})
  assertEquals("Аварійне відключення",rows.last().label);assertTrue(rows.last().estimated)
 }
 @Test fun ongoingIncidentWithoutEndIsNotInventedForTomorrow() {
  val s=source().copy(days=emptyList(),currentOutage=CurrentOutage("Аварія",true,now.toEpochMilli(),null,"",""))
  assertNull(detailsIntervals(s,today,now)!!.single().end)
  assertNull(detailsIntervals(s,today.plusDays(1),now))
 }
 @Test fun missingSlotsAndStalePlansDoNotBecomeNoOutageMessages() {
  val partial=source().copy(days=listOf(ScheduleDay("",today.toString(),listOf(Slot(480,600,PowerState.SCHEDULED_OUTAGE)))))
  assertTrue(detailsIntervals(partial,today,now)!!.any{it.state==PowerState.UNKNOWN})
  assertEquals(PowerState.UNKNOWN,detailsIntervals(source().copy(fetchedAt=now.minusSeconds(7200).toEpochMilli()),today,now)!!.single().state)
 }
}
