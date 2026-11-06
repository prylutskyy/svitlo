package ua.svitlo.app
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import ua.svitlo.app.data.*
import ua.svitlo.app.domain.*
import java.time.Instant

class ProminStreamsTest {
    private val now=Instant.parse("2026-11-03T22:25:00Z")
    private fun fixture(name:String)=javaClass.getResource("/promin/$name.json")!!.readText()
    private val config=ProminParser.config(ProminCities.get("vinnytsia"),fixture("vinnytsia-config"))
    private fun address(house:String="81/2")=SavedAddress("a","Дім",AddressItem("1","Вінниця"),AddressItem("97577531","Хмельницьке шосе"),AddressItem(house,house),prominCity="vinnytsia")
    @Test fun vinnytsiaAddressWithoutAQueueStillHasAReadableGraph() {
        val s=ProminStreams.combine(config,address(),null,Result.success(fixture("vinnytsia-outages")),now)
        assertFalse(s.planUnavailable);assertFalse(s.stale(now));assertEquals(PowerState.NO_SCHEDULED_OUTAGE,s.powerAt(now,now))
        assertNull(s.queue);assertTrue(s.messages.any{it.contains("не вимір")})
        assertEquals(PowerState.UNKNOWN,s.powerAt(now.atZone(KYIV).toLocalDate().plusDays(1).atStartOfDay(KYIV).toInstant(),now))
        assertEquals(PowerState.UNKNOWN,s.powerAt(now.minusSeconds(3600),now))
    }
    @Test fun actualEmergencyRemainsVisibleWithoutAnHourlyPlan() {
        val s=ProminStreams.combine(config,address("57"),null,Result.success(fixture("vinnytsia-outages")),now)
        assertNotNull(s.currentOutage);assertEquals(PowerState.EMERGENCY_OUTAGE,s.powerAt(now,now))
    }
    @Test fun aFailedPlanDoesNotEraseFreshAddressEvents() {
        val s=ProminStreams.combine(config.copy(addressSchedule=false),address("57"),Result.failure(java.io.IOException("503")),Result.success(fixture("vinnytsia-outages")),now)
        assertTrue(s.planUnavailable);assertTrue(s.streamIssues!!.any{it.startsWith("Графік:")});assertEquals(PowerState.EMERGENCY_OUTAGE,s.powerAt(now,now))
    }
    @Test fun anInvalidPlanDoesNotEraseFreshEvents() {
        val s=ProminStreams.combine(config.copy(addressSchedule=false),address("57"),Result.success("not JSON"),Result.success(fixture("vinnytsia-outages")),now)
        assertEquals(PowerState.EMERGENCY_OUTAGE,s.powerAt(now,now))
    }
    @Test fun failedEventsDoNotEraseAPublishedPlanButCannotProveClearPower() {
        val sc=JSONObject(fixture("kyiv-schedule"));val group=sc.getJSONObject("today").getJSONObject("groups")
        group.put("1.1",org.json.JSONArray("[[0,60]]"))
        val a=address().copy(feedGroup="1.1")
        val s=ProminStreams.combine(config.copy(addressSchedule=false),a,Result.success(sc.toString()),Result.failure(java.io.IOException("offline")),now)
        assertTrue(s.outagesUnavailable);assertEquals(PowerState.SCHEDULED_OUTAGE,s.powerAt(now,now))
        assertEquals(PowerState.UNKNOWN,s.powerAt(now.plusSeconds(7200),now))
        assertTrue(s.streamIssues!!.any{it.startsWith("Адресні події:")})
    }
    @Test fun staleAddressListCannotPaintAGreenGraph() {
        val out=JSONObject(fixture("vinnytsia-outages")).put("stale",true)
        val s=ProminStreams.combine(config,address(),null,Result.success(out.toString()),now)
        assertTrue(s.outagesUnavailable);assertEquals(PowerState.UNKNOWN,s.powerAt(now,now))
    }
    @Test fun freshEventsCannotMakeAnOldHourlyPlanFresh() {
        val sc=JSONObject(fixture("kyiv-schedule")).put("fetchedAt",now.minusSeconds(3*3600).toString())
        val s=ProminStreams.combine(config.copy(addressSchedule=false),address().copy(feedGroup="1001.1"),Result.success(sc.toString()),Result.success(fixture("vinnytsia-outages")),now)
        assertTrue(s.planUnavailable);assertEquals(PowerState.UNKNOWN,s.powerAt(now,now))
    }
    @Test(expected=java.io.IOException::class) fun twoFailedStreamsAreNotAnEmptySuccess() {
        ProminStreams.combine(config.copy(addressSchedule=false),address(),Result.failure(java.io.IOException("plan")),Result.failure(java.io.IOException("events")),now)
    }
    @Test(expected=VoeFormatException::class) fun addressOnlyCityRequiresAHouseNotAQueue() {
        ProminStreams.combine(config,address().copy(street=AddressItem("queue","Черга")),null,null,now)
    }
}
