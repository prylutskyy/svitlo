package ua.svitlo.app
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import okhttp3.mockwebserver.*
import kotlinx.coroutines.runBlocking
import ua.svitlo.app.data.*
import ua.svitlo.app.domain.*
import java.time.Instant
class QueueFeedClientTest {
 private fun fixture():String {
  val r=JSONObject(javaClass.getResource("/queue-vinnytsia-20261103.json")!!.readText())
  val days=r.getJSONObject("fact").getJSONObject("data");val first=days.getJSONObject(days.keys().next())
  val key=Instant.now().atZone(KYIV).toLocalDate().atStartOfDay(KYIV).toEpochSecond().toString()
  r.put("lastUpdated",Instant.now().toString());r.getJSONObject("fact").put("data",JSONObject().put(key,first));return r.toString()
 }
 private fun address(group:String)=SavedAddress(group,"Дім",AddressItem("feed","Оператор"),AddressItem("queue","Черга"),AddressItem(group,group),feedId="voe/Vinnytsiaoblenerho",feedGroup=group)
 private fun changed(raw:String):String {
  val root=JSONObject(raw);val days=root.getJSONObject("fact").getJSONObject("data")
  days.getJSONObject(days.keys().next()).getJSONObject("GPV1.2").put("1","no")
  return root.toString()
 }
 @Test fun manualRefreshBypassesWarmCacheButSharesOneDownloadBetweenQueues()=runBlocking {
  MockWebServer().use { server ->
   val original=fixture();server.enqueue(MockResponse().setBody(original));server.enqueue(MockResponse().setBody(changed(original)))
   val c=VoeClient(server.url("/"),feedUrl={server.url("/feed.json").toString()})
   c.feedGroups("voe/Vinnytsiaoblenerho")
   val manual=c.Batch(forceRefresh=true)
   val updated=manual.schedule(address("GPV1.2"));manual.schedule(address("GPV2.1"))
   assertEquals(2,server.requestCount)
   assertEquals(PowerState.SCHEDULED_OUTAGE,updated.days.first().slots.first().state)
   server.takeRequest();assertEquals("no-cache",server.takeRequest().getHeader("Cache-Control"))
   server.enqueue(MockResponse().setBody(original))
   c.Batch(forceRefresh=true).schedule(address("GPV1.2"))
   assertEquals(3,server.requestCount) // A new button press is a new source check.
  }
 }
 @Test fun foregroundCacheExpiresAfterOneMinuteRatherThanThree()=runBlocking {
  MockWebServer().use { server ->
   var clock=0L;val raw=fixture()
   server.enqueue(MockResponse().setBody(raw));server.enqueue(MockResponse().setBody(changed(raw)))
   val c=VoeClient(server.url("/"),feedUrl={server.url("/feed.json").toString()},feedClock={clock})
   c.feedGroups("voe/Vinnytsiaoblenerho")
   clock=59_999;c.Batch().schedule(address("GPV1.2"));assertEquals(1,server.requestCount)
   clock=60_000;val next=c.Batch().schedule(address("GPV1.2"))
   assertEquals(2,server.requestCount);assertEquals(PowerState.SCHEDULED_OUTAGE,next.days.first().slots.first().state)
  }
 }
 @Test fun failedManualBatchDoesNotRetryForEachQueue()=runBlocking {
  MockWebServer().use { server ->
   server.enqueue(MockResponse().setResponseCode(503))
   val c=VoeClient(server.url("/"),feedUrl={server.url("/feed.json").toString()});val batch=c.Batch(forceRefresh=true)
   for(group in listOf("GPV1.2","GPV2.1")) {
    try { batch.schedule(address(group));fail("503 must remain a failure") } catch(e:VoeHttpException) { assertEquals(503,e.status) }
   }
   assertEquals(1,server.requestCount)
  }
 }
 @Test fun queuePlansStillUseOneGitHubDownloadAndNoPromin()=runBlocking {
  MockWebServer().use{server->server.enqueue(MockResponse().setBody(fixture()));val c=VoeClient(server.url("/"),feedUrl={server.url("/feed.json").toString()})
   assertEquals(12,c.feedGroups("voe/Vinnytsiaoblenerho").size)
   assertEquals("1.2",c.Batch().schedule(address("GPV1.2")).queue);c.Batch().schedule(address("GPV2.1"))
   assertEquals(1,server.requestCount);assertEquals("/feed.json",server.takeRequest().path)
  }
 }
 @Test fun officialEmergencyLookupIsOptInAndSharedForTheRegion()=runBlocking {
  MockWebServer().use{server->
   server.enqueue(MockResponse().setBody(fixture()))
   server.enqueue(MockResponse().setBody(javaClass.getResource("/voe-emergency-form-20261104.html")!!.readText()).setHeader("Set-Cookie","voe_test=ok; Path=/"))
   server.enqueue(MockResponse().setBody(javaClass.getResource("/voe-emergency-vinnytsia-20261104.html")!!.readText()))
   val c=VoeClient(server.url("/"),feedUrl={server.url("/feed.json").toString()},emergencyUrl=server.url("/emergency").toString())
   val a=address("GPV1.2").copy(voeIncidentAddress=VoeIncidentAddress("23","Вінниця","проспект Юності","14"))
   val s=c.Batch().schedule(a);assertNotNull(s.currentOutage);assertTrue(s.currentOutage!!.emergency)
   c.Batch().schedule(a.copy(id="b"));assertEquals(3,server.requestCount)
   assertEquals("/feed.json",server.takeRequest().path);assertEquals("GET",server.takeRequest().method)
   val get=server.takeRequest();assertEquals("GET",get.method);assertTrue(get.path!!.endsWith("/23"));assertTrue(get.getHeader("Cookie")!!.contains("voe_test=ok"))
  }
 }
 @Test fun aJournalErrorKeepsThePublishedQueuePlan()=runBlocking {
  MockWebServer().use{server->server.enqueue(MockResponse().setBody(fixture()));server.enqueue(MockResponse().setResponseCode(503))
   val c=VoeClient(server.url("/"),feedUrl={server.url("/feed.json").toString()},emergencyUrl=server.url("/emergency").toString())
   val s=c.Batch().schedule(address("GPV1.2").copy(voeIncidentAddress=VoeIncidentAddress("23","Вінниця","проспект Юності","14")))
   assertEquals("1.2",s.queue);assertTrue(s.days.isNotEmpty());assertTrue(s.outagesUnavailable);assertTrue(s.streamIssues!!.any{it.startsWith("Аварії VOE:")})
  }
 }
}
