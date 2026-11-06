package ua.svitlo.app
import org.junit.Assert.*
import org.junit.Test
import ua.svitlo.app.data.*
import ua.svitlo.app.domain.*
import java.time.Instant

class VoeEmergencyTest {
 private val now=Instant.parse("2026-11-04T07:20:00Z")
 private fun fixture(name:String)=javaClass.getResource("/voe-emergency-$name-20261104.html")!!.readText()
 private fun vinnytsia()=VoeEmergencyParser.snapshot(fixture("vinnytsia"),"23",now)
 private fun gopchytsia()=VoeEmergencyParser.snapshot(fixture("pohrebyshche"),"33",now)
 @Test fun officialFormContainsTheDistrictServingGopchytsia() {
  val f=VoeEmergencyParser.form(fixture("form"));assertEquals("disconnection_search_form",f.fields["form_id"])
  assertEquals("Погребищенський р-н",f.regions.single{it.id=="33"}.name)
 }
 @Test fun liveCapturedJournalContainsRealEmergencyRecords() {
  val records=vinnytsia();assertEquals(20,records.records.size)
  val events=VoeEmergencyParser.incidents(records,VoeIncidentAddress("23","м. Вінниця","проспект Юності","14"))
  assertTrue(events.isNotEmpty());assertTrue(events.first().emergency)
  assertEquals(Instant.parse("2026-11-04T00:22:47Z").toEpochMilli(),events.first().startedAt)
 }
 @Test fun officialDistrictDataActuallyIncludesGopchytsia() {
  val events=VoeEmergencyParser.incidents(gopchytsia(),VoeIncidentAddress("33","с. Гопчиця","вулиця Веселівка","1"))
  assertTrue(events.isNotEmpty());assertTrue(events.all{!it.emergency});assertTrue(events.all{it.reason.contains("ГПВ")})
 }
 @Test fun houseTokensAndRoadTypesAreExact() {
  val r=VoeEmergencyRecord("1","Аварійне відключення","ГОПЧИЦЯ: вулиця Центральна 1,14,81/2; провулок Центральний 12А,79-А,ВІННИЦЯ: вулиця Центральна 7",1,3,null,1)
  fun match(city:String="Гопчиця",street:String="вулиця Центральна",house:String)=VoeEmergencyParser.matches(r,VoeIncidentAddress("33",city,street,house))
  assertTrue(match(house="81/2"));assertFalse(match(house="81"));assertFalse(match(house="4"));assertFalse(match(house="7"))
  assertTrue(match(street="провулок Центральний",house="12A"));assertTrue(match(street="провулок Центральний",house="79А"))
  assertFalse(match(street="вулиця Центральний",house="12А"));assertTrue(match(city="Вінниця",house="7"))
 }
 @Test fun sourceFactsCloseEventsRatherThanAnEstimatedDeadline() {
  val r=vinnytsia().records.first();val address=VoeIncidentAddress("23","Вінниця","проспект Юності","14")
  val s=VoeEmergencySnapshot(now.toEpochMilli(),listOf(r.copy(actualEnd=now.toEpochMilli())))
  assertTrue(VoeEmergencyParser.incidents(s,address).isEmpty())
 }
 @Test(expected=VoeFormatException::class) fun wrongDistrictIsNotAnEmptySuccess(){VoeEmergencyParser.snapshot(fixture("vinnytsia"),"33",now)}
 @Test(expected=VoeFormatException::class) fun challengeOrErrorPageIsNotAnEmptySuccess(){VoeEmergencyParser.snapshot("<html>Access denied</html>","23",now)}
 @Test(expected=VoeFormatException::class) fun alteredTableSchemaIsRejected(){VoeEmergencyParser.snapshot(fixture("vinnytsia").replace("accbegin","other_column"),"23",now)}
 @Test(expected=IllegalArgumentException::class) fun ambiguousRoadInputRequiresAType(){VoeEmergencyParser.validate(VoeIncidentAddress("33","Гопчиця","Центральна","1"))}
 @Test fun independentJournalCanStillReportAnEmergencyIfTheQueueFeedFails() {
  val s=VoeEmergencyClient.combine(Result.failure(java.io.IOException("GitHub offline")),Result.success(vinnytsia()),VoeIncidentAddress("23","Вінниця","проспект Юності","14"),"https://example.test/plan",now)
  assertTrue(s.planUnavailable);assertEquals(PowerState.EMERGENCY_OUTAGE,s.powerAt(now,now));assertTrue(s.streamIssues!!.any{it.startsWith("Графік:")})
 }
 @Test fun migrationKeepsPreviousGitHubPlansAndWidgetBindings() {
  val a=SavedAddress("old","Дім",AddressItem("a","Оператор"),AddressItem("q","Черга"),AddressItem("GPV1.2","1.2"),feedId="voe/Vinnytsiaoblenerho",feedGroup="GPV1.2")
  val s=AppState(addresses=listOf(a),schedules=listOf(CachedSchedule(a.id,Schedule(1,emptyList(),emptyList()))))
  val migrated=ProminMigration.apply(s);assertEquals(s.addresses,migrated.addresses);assertEquals(s.schedules,migrated.schedules)
 }
}
