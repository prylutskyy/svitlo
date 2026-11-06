package ua.svitlo.app.data

import org.json.JSONArray
import org.json.JSONObject
import ua.svitlo.app.domain.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

data class ProminConfig(val city: ProminCity,val groups: List<String>,val names: Map<String,String>,
    val outages: Boolean,val addressSchedule: Boolean,val imageSchedule: Boolean) {
    fun groupName(id: String) = names[id] ?: "Черга $id"
}
data class ProminStreet(val id: String,val name: String,val city: String,val cityId: String)
data class ProminSelection(val city: String,val street: ProminStreet?,val house: ProminHouse?,val group: String,val title: String)
data class ProminEvents(val fetchedAt: Long,val stale: Boolean,val incidents: List<CurrentOutage>,val oldRecords: Boolean)
data class ProminHouse(val number: String,val group: String,val buildingId: Long?,val estimated: Boolean)

/** Promin JSON only. Missing data and unsupported capabilities never become a clear-power claim. */
object ProminParser {
    private val dayFormat = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)
    private fun json(raw: String): JSONObject = try {
        JSONObject(raw).also { if (it.has("error") && !it.isNull("error")) throw VoeFormatException("Промінь: ${it.optString("error")}") }
    } catch(e: VoeFormatException) { throw e } catch(_: Exception) { throw VoeFormatException("Не вдалося прочитати JSON Променя") }
    private fun strings(a: JSONArray?) = if(a==null) emptyList() else List(a.length()) { a.getString(it) }
    fun config(city: ProminCity,raw: String): ProminConfig {
        val c=json(raw).getJSONObject("city")
        if (c.getString("slug")!=city.id) throw VoeFormatException("Промінь повернув інше місто")
        val names=c.optJSONObject("group_names")
        return ProminConfig(city,strings(c.optJSONArray("groups")),names?.keys()?.asSequence()?.associateWith { names.getString(it) }.orEmpty(),
            !c.optBoolean("no_outages",true),c.optString("schedule")=="addr",c.optString("schedule")=="image")
    }
    fun queueConfig(city: ProminCity,raw: String): ProminConfig {
        val sc=json(raw); val names=sc.optJSONObject("names")
        return ProminConfig(city,strings(sc.optJSONArray("groups")),names?.keys()?.asSequence()?.associateWith { names.getString(it) }.orEmpty(),false,false,false)
    }
    fun streets(raw: String): List<ProminStreet> {
        val a=json(raw).getJSONArray("items")
        return List(a.length()) { i -> a.getJSONArray(i).let { ProminStreet(it.get(0).toString(),it.getString(1),it.getString(2),it.get(3).toString()) } }
    }
    fun houses(raw: String): List<ProminHouse> {
        val d=json(raw)
        if (d.optBoolean("pending")) throw VoeFormatException("Промінь уточнює черги цієї вулиці. Спробуйте за кілька хвилин.")
        val a=d.getJSONArray("houses")
        return List(a.length()) { i -> val h=a.getJSONObject(i)
            ProminHouse(h.getString("house"),h.optString("g"),h.optJSONArray("pt")?.optLong(2)?.takeIf { it>0 },h.optBoolean("q"))
        }
    }
    private fun timestamp(value: String): Instant = runCatching { OffsetDateTime.parse(value).toInstant() }
        .recoverCatching { LocalDateTime.parse(value).atZone(KYIV).toInstant() }.getOrElse { throw VoeFormatException("Некоректний час Променя") }
    private fun fetched(d: JSONObject,now: Instant): Instant {
        val at=timestamp(d.getString("fetchedAt"))
        if (at>now.plusSeconds(300)) throw VoeFormatException("Час джерела випереджає поточний")
        return at
    }
    private fun normalized(s: String) = s.lowercase(Locale.ROOT).replace('’','\'').replace('ʼ','\'').trim().replace(Regex("\\s+")," ")
    private fun street(s: String) = normalized(s).replace(Regex("^(вулиця|вул\\.)\\s+"),"")
    private fun city(s: String) = normalized(s).replace(Regex("^(м\\.|с\\.|смт|місто|село)\\s*"),"")
    private fun matches(o: JSONObject,a: SavedAddress): Boolean {
        if (a.street.id=="queue") return false
        val pts=o.optJSONArray("pts")
        if (a.prominBuildingId!=null && pts!=null && (0 until pts.length()).any { pts.getJSONArray(it).optLong(2)==a.prominBuildingId }) return true
        return city(o.getString("city"))==city(a.city.name) && street(o.getString("street"))==street(a.street.name) &&
            strings(o.optJSONArray("houses")).any { normalized(it)==normalized(a.house.name) }
    }
    fun events(raw: String,address: SavedAddress,now: Instant): ProminEvents {
        val out=json(raw)
        val at=fetched(out,now)
        val today=now.atZone(KYIV).toLocalDate()
        val items=out.getJSONArray("items")
        val matched=items?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { matches(it,address) }.map { o ->
            val from=timestamp(o.getString("from")).toEpochMilli()
            val until=o.optString("until").takeIf { it.isNotBlank() && it!="null" }?.let { timestamp(it).toEpochMilli() }
            if (until!=null && until<=from) throw VoeFormatException("Некоректний час завершення відключення")
            val reason=o.getString("kind")
            CurrentOutage(reason,o.optInt("typeOff",-1)==1 || reason.contains("аварі",true),from,until,
                "Завершення орієнтовне; фактичне відновлення не підтверджено.",o.optString("comment"))
        } }.orEmpty()
        // Some providers retain months-old incidents despite a fresh response timestamp.
        // Exclude records whose estimate ended before yesterday; do not announce restoration.
        val historyCutoff=today.minusDays(1).atStartOfDay(KYIV).toInstant().toEpochMilli()
        val incidents=matched.filter { it.estimatedEnd==null || it.estimatedEnd>=historyCutoff }

        return ProminEvents(at.toEpochMilli(),out.optBoolean("stale") || now.minusSeconds(1800)>at,incidents,matched.isNotEmpty() && incidents.isEmpty())
    }
    fun schedule(config: ProminConfig,planRaw: String,outagesRaw: String?,address: SavedAddress,now: Instant): Schedule {
        val sc=json(planRaw); val planAt=fetched(sc,now)
        val out=outagesRaw?.let(::json)
        if (config.outages && out==null) throw VoeFormatException("Немає відповіді про адресні відключення")
        val outAt=out?.let { fetched(it,now) }
        val messages=mutableListOf("Джерело: Промінь · ${config.city.name}")
        if (!config.outages) messages += "Промінь не надає адресних аварій для цього міста."
        else if (address.street.id=="queue") messages += "Виберіть будинок, щоб бачити адресні аварії."
        if (address.prominGroupEstimated) messages += "Черга будинку приблизна; погодинний план не застосовано."
        val today=now.atZone(KYIV).toLocalDate()
        val selectedGroup=address.feedGroup.orEmpty().takeUnless { address.prominGroupEstimated }.orEmpty()
        val candidates=listOfNotNull(sc.optJSONObject("today"),sc.optJSONObject("tomorrow")) +
            sc.optJSONArray("archive")?.let { a -> List(a.length()) { a.getJSONObject(it) } }.orEmpty()
        val days=(0L..1L).map { offset ->
            val date=today.plusDays(offset)
            val entry=candidates.firstOrNull { e -> runCatching { LocalDate.parse(e.optString("date"),dayFormat)==date }.getOrDefault(false) }
            val intervals=entry?.optJSONObject("groups")?.optJSONArray(selectedGroup)
            val ranges=intervals?.let { a -> List(a.length()) { i -> val r=a.getJSONArray(i); val start=r.getInt(0); val end=r.getInt(1)
                if (start !in 0..1439 || end !in 1..1440 || start>=end) throw VoeFormatException("Некоректний інтервал графіка")
                start to end
            } }
            val bounds=(listOf(0,1440)+(ranges.orEmpty().flatMap { listOf(it.first,it.second) })).distinct().sorted()
            val slots=bounds.zipWithNext { start,end -> Slot(start,end,when {
                ranges==null -> PowerState.UNKNOWN
                ranges.any { start>=it.first && start<it.second } -> PowerState.SCHEDULED_OUTAGE
                else -> PowerState.NO_SCHEDULED_OUTAGE
            }) }
            ScheduleDay(if(offset==0L) "Сьогодні" else "Завтра",date.toString(),slots)
        }
        if (days.first().slots.all { it.state==PowerState.UNKNOWN }) messages += when {
            config.imageSchedule -> "Графік доступний зображенням на Промені. Часові інтервали не розпізнано."
            config.addressSchedule -> "Погодинний графік не опублікований; адресні повідомлення показано окремо."
            selectedGroup.isBlank() -> "Чергу цього будинку не визначено."
            else -> "Промінь ще не опублікував графік на сьогодні для цієї черги."
        }
        val eventData=outagesRaw?.let { events(it,address,now) }
        val incidents=eventData?.incidents.orEmpty()
        if (eventData?.oldRecords==true) messages += "У джерелі є лише давні адресні записи; поточний стан невідомий."
        val active=incidents.filter { it.startedAt!!<=now.toEpochMilli() }.sortedWith(compareByDescending<CurrentOutage> { it.emergency }.thenByDescending { it.startedAt }).firstOrNull()
        val at=listOfNotNull(planAt,outAt).minOrNull()!!
        val stale=sc.optBoolean("stale") || out?.optBoolean("stale")==true || now.minusSeconds(7200)>at
        if (stale) messages += "Джерело повідомляє про застарілі дані."
        if (config.outages && address.street.id!="queue" && incidents.isEmpty()) messages += "Адресних відключень не повідомлено. Це не вимір наявності електроенергії."
        return Schedule(at.toEpochMilli(),days,messages,active,selectedGroup.takeIf { it.isNotBlank() }?.let { (config.names[it] ?: it).removePrefix("Черга ") },
            "https://promin.vlasen.com${config.city.path}",incidents,stale)
    }
}
