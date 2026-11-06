package ua.svitlo.app
import org.junit.Assert.*
import org.junit.Test
import ua.svitlo.app.domain.*
import java.time.Instant
class FeedRefreshTest {
 private val now=Instant.parse("2026-11-03T12:00:00Z")
 private fun address(id:String,feed:String)=SavedAddress(id,id,AddressItem("feed","Оператор"),AddressItem("q","Черга"),AddressItem("1","1"),feedId=feed,feedGroup="GPV1")
 @Test fun badOperatorDoesNotDelayAnotherFeed() {
  val a=address("a","bad");val b=address("b","good")
  val state=AppState(addresses=listOf(a,b),attempts=listOf(FetchAttempt("a",now.toEpochMilli(),1,"Застаріло",FetchFailure.FORMAT)))
  assertEquals(now,RefreshPolicy.nextDue(state,"b",now));assertTrue(RefreshPolicy.nextDue(state,"a",now)>now)
 }
 @Test fun upstreamTimestampDoesNotForceRepeatedPollingAfterSuccess() {
  val a=address("a","source")
  val plan=Schedule(now.minusSeconds(1800).toEpochMilli(),listOf(ScheduleDay("Сьогодні","2026-11-03",(0..47).map{Slot(it*30,it*30+30,PowerState.NO_SCHEDULED_OUTAGE)})),emptyList(),sourceUrl="https://raw.githubusercontent.com/example/feed")
  val state=AppState(addresses=listOf(a),schedules=listOf(CachedSchedule("a",plan)),attempts=listOf(FetchAttempt("a",now.toEpochMilli(),0,null)))
  assertFalse(RefreshPolicy.due(state,"a",now));assertTrue(RefreshPolicy.nextDue(state,"a",now)>now)
 }
}
