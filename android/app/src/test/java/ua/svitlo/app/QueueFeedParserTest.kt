package ua.svitlo.app

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import ua.svitlo.app.data.*
import ua.svitlo.app.domain.*
import java.time.Instant
import com.google.gson.Gson

class QueueFeedParserTest {
    private val raw=javaClass.getResource("/queue-vinnytsia-20261103.json")!!.readText()
    private val provider=FeedProviders.get("voe/Vinnytsiaoblenerho")
    private val now=Instant.ofEpochSecond(JSONObject(raw).getLong("lastUpdated"))
    private fun mutate(block:(JSONObject)->Unit):String = JSONObject(raw).also(block).toString()
    private fun hours(root:JSONObject)=root.getJSONObject("fact").getJSONObject("data").getJSONObject("1793656800").getJSONObject("GPV1.2")
    @Test fun actualPlanHasCorrectQueueAndEightHours() {
        val plan=QueueFeedParser.schedule(raw,"GPV1.2",provider,now)
        assertEquals("1.2",plan.queue);assertEquals(provider.url,plan.sourceUrl);assertNull(plan.currentOutage)
        assertEquals(JSONObject(raw).getLong("lastUpdated")*1000,plan.fetchedAt)
        val marked=plan.days.first().slots.filter { it.state==PowerState.SCHEDULED_OUTAGE }
        assertEquals((480 until 600 step 30).toList()+(720 until 960 step 30).toList()+(1200 until 1320 step 30).toList(),marked.map{it.startMinute})
        assertEquals(12,QueueFeedParser.groups(raw,now).size)
    }
    @Test fun halfHoursAndUnknownsStayPrecise() {
        val text=mutate { root -> hours(root).put("1","first").put("2","second").put("3","mystery").remove("4") }
        val states=QueueFeedParser.schedule(text,"GPV1.2",provider,now).days.first().slots.take(8).map{it.state}
        assertEquals(listOf(PowerState.SCHEDULED_OUTAGE,PowerState.NO_SCHEDULED_OUTAGE,PowerState.NO_SCHEDULED_OUTAGE,PowerState.SCHEDULED_OUTAGE)+List(4){PowerState.UNKNOWN},states)
    }
    @Test fun missingGroupIsUnknownRatherThanClear() {
        assertTrue(QueueFeedParser.schedule(raw,"GPVabsent",provider,now).days.all{d->d.slots.all{it.state==PowerState.UNKNOWN}})
    }
    @Test(expected=VoeFormatException::class) fun oldUpstreamTimestampRejected() { QueueFeedParser.schedule(raw,"GPV1.2",provider,now.plusSeconds(7201)) }
    @Test(expected=VoeFormatException::class) fun missingTodayRejected() { QueueFeedParser.schedule(mutate{it.getJSONObject("fact").getJSONObject("data").remove("1793656800")},"GPV1.2",provider,now) }
    @Test fun isoTimeIsSupported() {
        assertEquals(now.toEpochMilli(),QueueFeedParser.schedule(mutate{it.put("lastUpdated",now.toString())},"GPV1.2",provider,now).fetchedAt)
    }
    @Test(expected=VoeFormatException::class) fun futureTimestampRejected() { QueueFeedParser.schedule(mutate{it.put("lastUpdated",now.plusSeconds(301).toString())},"GPV1.2",provider,now) }
    @Test(expected=VoeFormatException::class) fun failedUpstreamStatusRejected() { QueueFeedParser.schedule(mutate{it.put("lastUpdateStatus",JSONObject().put("ok",false))},"GPV1.2",provider,now) }
    @Test(expected=VoeFormatException::class) fun failedSourceCannotOfferQueueSelection() { QueueFeedParser.groups(mutate{it.put("lastUpdateStatus",JSONObject().put("ok",false))},now) }
    @Test fun oldAddressSnapshotsRemainCompatible() {
        val a=Gson().fromJson("""{"id":"old","title":"Дім","city":{"id":"1","name":"Вінниця"},"street":{"id":"2","name":"Вулиця"},"house":{"id":"3","name":"3"},"selected":true}""",SavedAddress::class.java)
        assertNull(a.feedId);assertNull(a.feedGroup);assertEquals("old",a.id)
    }
    @Test fun queuePlanCannotConfirmAddressRestoration() {
        val a=SavedAddress("a","Дім",AddressItem("1","Місто"),AddressItem("2","Вулиця"),AddressItem("3","3"))
        val plan=QueueFeedParser.schedule(raw,"GPV1.2",provider,now)
        val previous=plan.copy(sourceUrl=null,currentOutage=CurrentOutage("Аварійне",true,now.minusSeconds(300).toEpochMilli(),null,"",""))
        val state=AppState(addresses=listOf(a),schedules=listOf(CachedSchedule("a",previous)),currentAddressId="a",eventMonitoring=true)
        assertNull(RestorationPolicy.notificationKey(state,"a",plan))
    }
}
