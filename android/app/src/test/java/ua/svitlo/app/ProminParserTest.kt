package ua.svitlo.app

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import ua.svitlo.app.data.*
import ua.svitlo.app.domain.*
import java.time.Instant

class ProminParserTest {
    private val now=Instant.parse("2026-11-03T22:25:00Z")
    private fun fixture(name: String)=javaClass.getResource("/promin/$name.json")!!.readText()
    private val city=ProminCities.get("vinnytsia")
    private fun config(id: String="vinnytsia"): ProminConfig {
        val c=ProminCities.get(id)
        return if(c.queueOnly) ProminParser.queueConfig(c,fixture("$id-schedule")) else ProminParser.config(c,fixture("$id-config"))
    }
    private fun address(street: String="Хмельницьке шосе",house: String="81/2")=SavedAddress("test","Дім",AddressItem("1","Вінниця"),AddressItem("97577531",street),AddressItem(house,house),prominCity="vinnytsia")
    private fun parse(a: SavedAddress=address(),out: String=fixture("vinnytsia-outages"),plan: String=fixture("vinnytsia-schedule"))=
        ProminParser.schedule(config(),plan,out,a,now)
    @Test fun everyPublishedCityHasAReadableContract() {
        assertEquals(19,ProminCities.all.size)
        ProminCities.all.forEach { c ->
            val cfg=config(c.id)
            val a=address().copy(prominCity=c.id,street=AddressItem("queue","Черга"),feedGroup=cfg.groups.firstOrNull())
            val schedule=ProminParser.schedule(cfg,fixture("${c.id}-schedule"),if(cfg.outages)fixture("${c.id}-outages") else null,a,now)
            assertEquals(c.id,java.net.URI(schedule.sourceUrl!!).path.removePrefix("/").let { if(it=="ivano-frankivsk")"ivano" else it })
            assertEquals("2026-11-04",schedule.days.first().date)
        }
    }
    @Test fun addressEventsAcrossAllProvidersCanBeRead() {
        ProminCities.all.filterNot { it.queueOnly }.forEach { c ->
            val cfg=config(c.id)
            if(cfg.outages) {
                val raw=fixture("${c.id}-outages");val items=JSONObject(raw).getJSONArray("items")
                if(items.length()>0) {
                    val o=items.getJSONObject(0)
                    val a=address().copy(city=AddressItem("test",o.getString("city")),street=AddressItem("test",o.getString("street")),house=AddressItem("test",o.getJSONArray("houses").getString(0)))
                    val s=ProminParser.schedule(cfg,fixture("${c.id}-schedule"),raw,a,now)
                    assertNotNull(s.reportedOutages)
                }
            }
        }
    }
    @Test fun oldIncidentsInFreshResponsesDoNotAppearAsCurrentEmergencies() {
        val cfg=config("uzhhorod");val raw=fixture("uzhhorod-outages");val o=JSONObject(raw).getJSONArray("items").getJSONObject(0)
        val a=address().copy(city=AddressItem("a",o.getString("city")),street=AddressItem("b",o.getString("street")),house=AddressItem("c",o.getJSONArray("houses").getString(0)))
        val s=ProminParser.schedule(cfg,fixture("uzhhorod-schedule"),raw,a,now)
        assertNull(s.currentOutage);assertTrue(s.reportedOutages.orEmpty().isEmpty());assertEquals(PowerState.UNKNOWN,s.powerAt(now,now))
    }
    @Test fun streetAndHouseCatalogsPreserveFractionalHouseAndUnknownGroup() {
        val s=ProminParser.streets(fixture("vinnytsia-streets")).single {it.id=="97577531"}
        assertEquals("Хмельницьке шосе",s.name)
        val h=ProminParser.houses(fixture("vinnytsia-houses")).single {it.number=="81/2"}
        assertEquals("",h.group);assertEquals(53739067L,h.buildingId)
    }
    @Test fun fractionalHouseIsNotAnotherHouseAndNoReportDoesNotMeanPowerIsOn() {
        val s=parse();assertNull(s.currentOutage);assertEquals(PowerState.UNKNOWN,s.powerAt(now,now))
        assertTrue(s.messages.any {it.contains("не гарантує").not() && it.contains("не вимір")})
    }
    @Test fun zeroStreetIdFallsBackToExactStreetCityAndHouse() {
        val s=parse(address(house="57"));assertNotNull(s.currentOutage);assertEquals(PowerState.EMERGENCY_OUTAGE,s.powerAt(now,now))
        assertNull(parse(address(house="57/1")).currentOutage)
        assertNull(parse(address().copy(city=AddressItem("other","Гопчиця"))).currentOutage)
    }
    @Test fun lanesAreNotConflatedWithTheMainStreet() {
        assertNull(parse(address(house="2А")).currentOutage)
        assertNotNull(parse(address("провулок 1-й Хмельницьке шосе","2А")).currentOutage)
    }
    @Test fun queueNeverReceivesEveryAddressIncident() {
        assertNull(parse(address().copy(street=AddressItem("queue","Черга"))).currentOutage)
    }
    @Test fun aFutureIncidentHasMinuteAccurateTimelineBoundaries() {
        val o=JSONObject(fixture("vinnytsia-outages"));val one=o.getJSONArray("items").getJSONObject(0)
        one.put("street","Хмельницьке шосе").put("houses",org.json.JSONArray(listOf("81/2")))
            .put("from","2026-11-04T03:17+02:00").put("until","2026-11-04T04:43+02:00")
        o.put("items",org.json.JSONArray().put(one))
        val s=parse(out=o.toString());assertNull(s.currentOutage)
        val start=Instant.parse("2026-11-04T01:17:00Z");val end=Instant.parse("2026-11-04T02:43:00Z")
        val span=s.chartSpans(now,now.plusSeconds(6*3600),now).single {it.state==PowerState.EMERGENCY_OUTAGE}
        assertEquals(start,span.start);assertEquals(end,span.end)
        assertTrue(RefreshPolicy.boundaries(s).contains(start))
    }
    @Test fun overdueEstimateNeverProvesRestoration() {
        val s=parse(address(house="57"));val later=now.plusSeconds(7200)
        assertEquals(PowerState.UNKNOWN,s.powerAt(later,now))
    }
    @Test fun staleIncidentResponseCannotBecomeFresh() {
        val out=JSONObject(fixture("vinnytsia-outages")).put("stale",true)
        val s=parse(address(house="57"),out.toString());assertTrue(s.sourceStale);assertTrue(s.stale(now));assertEquals(PowerState.UNKNOWN,s.powerAt(now,now))
    }
    @Test fun sourceTimeIsPreserved() {
        val s=parse();val expected=minOf(java.time.OffsetDateTime.parse(JSONObject(fixture("vinnytsia-outages")).getString("fetchedAt")).toInstant(),java.time.OffsetDateTime.parse(JSONObject(fixture("vinnytsia-schedule")).getString("fetchedAt")).toInstant())
        assertEquals(expected.toEpochMilli(),s.fetchedAt)
    }
    @Test fun imageOnlyGraphDoesNotInventHourlySlots() {
        val c=config("lutsk");val s=ProminParser.schedule(c,fixture("lutsk-schedule"),null,address().copy(feedGroup="1.1"),now)
        assertTrue(c.imageSchedule);assertEquals(PowerState.UNKNOWN,s.powerAt(now,now));assertTrue(s.messages.any{it.contains("зображенням")})
    }
    @Test fun emptyPublishedGroupIsClearPlanButMissingGroupIsUnknown() {
        val c=config("kyiv");val sc=JSONObject(fixture("kyiv-schedule"))
        sc.getJSONObject("today").getJSONObject("groups").put("1.1",org.json.JSONArray())
        fun value(g:String)=ProminParser.schedule(c,sc.toString(),null,address().copy(feedGroup=g),now).powerAt(now,now)
        assertEquals(PowerState.NO_SCHEDULED_OUTAGE,value("1.1"));assertEquals(PowerState.UNKNOWN,value("not-a-group"))
    }
    @Test fun estimatedHouseGroupIsNeverUsedAsAnExactPlan() {
        val c=config("kyiv");val s=ProminParser.schedule(c,fixture("kyiv-schedule"),null,address().copy(feedGroup="1001.1",prominGroupEstimated=true),now)
        assertNull(s.queue);assertEquals(PowerState.UNKNOWN,s.powerAt(now,now))
    }
    @Test fun regionalGroupLabelsAreNotLost() {
        assertEquals("обл. 1.1",config("kyiv").groupName("1001.1"));assertEquals("ЦЕК 1.1",config("dnipro").groupName("1001.1"))
    }
    @Test(expected=VoeFormatException::class) fun upstreamErrorIsNotAnEmptyOutageList() {parse(out="""{"error":"offline"}""")}
    @Test(expected=VoeFormatException::class) fun missingAddressOutageResponseIsNotSuccess() {ProminParser.schedule(config(),fixture("vinnytsia-schedule"),null,address(),now)}
    @Test fun sourceMigrationKeepsPlaceIdsAndClearsForeignCache() {
        val old=address().copy(prominCity=null,feedId="old-provider")
        val state=AppState(addresses=listOf(old),schedules=listOf(CachedSchedule(old.id,Schedule(1,emptyList(),emptyList()))))
        val migrated=ProminMigration.apply(state)
        assertEquals(listOf(old),migrated.addresses);assertTrue(migrated.schedules.isEmpty());assertEquals(migrated,ProminMigration.apply(migrated))
    }
}
