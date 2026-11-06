package ua.svitlo.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import okhttp3.FormBody
import ua.svitlo.app.domain.*
import java.time.Instant

class VoeEmergencyClient(private val api:VoeClient,private val url:String) {
    private val mutex=Mutex()
    private val cache=mutableMapOf<String,VoeEmergencySnapshot>()
    private var regionCache:Pair<Long,List<AddressItem>>?=null
    private var pauseUntil=0L
    suspend fun regions():List<AddressItem> {
        regionCache?.let {if(System.currentTimeMillis()-it.first<86_400_000)return it.second}
        val form=VoeEmergencyParser.form(api.request(url))
        regionCache=System.currentTimeMillis() to form.regions
        return form.regions
    }
    suspend fun snapshot(regionId:String,now:Instant):VoeEmergencySnapshot {
        require(regionId.matches(Regex("\\d+")))
        mutex.lock()
        try {
            cache[regionId]?.let {if(now.toEpochMilli()-it.checkedAt in 0..299_999)return it}
            if(System.currentTimeMillis()<pauseUntil) throw VoeHttpException(429,pauseUntil)
            require(regions().any{it.id==regionId}) {"Невідомий район VOE"}
            val local=now.atZone(KYIV)
            val month="%02d".format(java.util.Locale.ROOT,local.monthValue)
            // Observed official POST/303 route; it is directly readable without a session.
            val route="${url.trimEnd('/')}/${local.year}/$month/$regionId"
            val result=VoeEmergencyParser.snapshot(api.request(route),regionId,Instant.now())
            cache[regionId]=result
            return result
        } catch(e:VoeHttpException) {
            if(e.status==429) pauseUntil=e.retryAt ?: System.currentTimeMillis()+900_000
            throw e
        } finally {mutex.unlock()}
    }
    companion object {
        fun combine(plan:Result<Schedule>,events:Result<VoeEmergencySnapshot>,address:VoeIncidentAddress,planUrl:String,now:Instant):Schedule {
            if(plan.isFailure && events.isFailure) throw plan.exceptionOrNull()!!
            val p=plan.getOrNull();val e=events.getOrNull()
            val issues=buildList {
                plan.exceptionOrNull()?.let{add("Графік: ${it.message ?: "не вдалося оновити"}")}
                events.exceptionOrNull()?.let{add("Аварії VOE: ${it.message ?: "не вдалося перевірити"}")}
            }
            val incidents=e?.let{VoeEmergencyParser.incidents(it,address)}.orEmpty()
            val active=incidents.filter{it.startedAt!!<=now.toEpochMilli()}.sortedWith(compareByDescending<CurrentOutage>{it.emergency}.thenByDescending{it.startedAt}).firstOrNull()
            val days=p?.days ?: listOf(ScheduleDay("Сьогодні",now.atZone(KYIV).toLocalDate().toString(),listOf(Slot(0,1440,PowerState.UNKNOWN))))
            val messages=p?.messages.orEmpty().filterNot{it.contains("Адресні аварії та фактичне відновлення не перевіряються")}.toMutableList()
            if(e!=null) messages += if(incidents.isEmpty()) "Журнал VOE: за вказаною адресою активних відключень не повідомлено. Це не вимір напруги." else "Адресні події — з офіційного журналу Вінницяобленерго."
            messages += VoeEmergencyParser.URL
            messages += issues
            return Schedule(listOfNotNull(p?.fetchedAt,e?.checkedAt).maxOrNull()!!,days,messages,active,p?.queue,planUrl,incidents,
                planFetchedAt=p?.fetchedAt,outagesFetchedAt=e?.checkedAt,planUnavailable=p==null,outagesUnavailable=e==null,outagesExpected=true,streamIssues=issues)
        }
    }
}
