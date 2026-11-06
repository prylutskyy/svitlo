package ua.svitlo.app.data

import org.json.JSONObject
import ua.svitlo.app.domain.*
import java.time.*

/** Public queue plans; timestamps describe upstream data, never the download time. */
object QueueFeedParser {
    private fun root(raw: String): JSONObject = try { JSONObject(raw) } catch (_: Exception) { throw VoeFormatException("Джерело повернуло некоректний JSON") }
    private fun updated(root: JSONObject, now: Instant): Instant {
        val status=root.optJSONObject("lastUpdateStatus")
        if (status?.has("ok")==true && !status.optBoolean("ok")) throw VoeFormatException("Парсер джерела повідомив про помилку")
        val value = root.opt("lastUpdated")
        val at = runCatching { if (value is Number) Instant.ofEpochSecond(value.toLong()) else Instant.parse(value.toString()) }.getOrNull()
            ?: throw VoeFormatException("Джерело не вказало час оновлення")
        if (at > now.plusSeconds(300)) throw VoeFormatException("Некоректна дата оновлення джерела")
        if (at < now.minusSeconds(7200)) throw VoeFormatException("Джерело не оновлювалося понад 2 години. Актуальний графік невідомий.")
        return at
    }
    private fun data(root: JSONObject) = root.optJSONObject("fact")?.optJSONObject("data") ?: throw VoeFormatException("Джерело не опублікувало графік")
    private fun date(key: String): LocalDate? = runCatching { Instant.ofEpochSecond(key.toLong()).atZone(KYIV).toLocalDate() }.getOrNull()
    fun groups(raw: String,now: Instant): List<String> {
        val root = root(raw); updated(root,now)
        val days=data(root); val today=now.atZone(KYIV).toLocalDate()
        val keys=days.keys().asSequence().filter { date(it)==today }.toList()
        if (keys.size!=1) throw VoeFormatException("Джерело не опублікувало графік на сьогодні")
        val groups=days.getJSONObject(keys.single()).keys().asSequence().filter { it.matches(Regex("GPV[A-Za-z0-9._-]{1,32}")) }.sorted().toList()
        if (groups.isEmpty()) throw VoeFormatException("Немає опублікованих черг")
        return groups
    }
    fun schedule(raw: String,group: String,provider: FeedProvider,now: Instant): Schedule {
        val root=root(raw); val at=updated(root,now); val data=data(root)
        val today=now.atZone(KYIV).toLocalDate()
        val days=data.keys().asSequence().mapNotNull { key ->
            val day=date(key) ?: throw VoeFormatException("Невідома дата графіка")
            if (day < today || day > today.plusDays(2)) return@mapNotNull null
            val hours=data.getJSONObject(key).optJSONObject(group)
            val slots=(0..47).map { half ->
                val value=hours?.optString((half/2+1).toString()).orEmpty().lowercase(java.util.Locale.ROOT)
                val state=when(value) {
                    "yes" -> PowerState.NO_SCHEDULED_OUTAGE
                    "no" -> PowerState.SCHEDULED_OUTAGE
                    "first" -> if (half%2==0) PowerState.SCHEDULED_OUTAGE else PowerState.NO_SCHEDULED_OUTAGE
                    "second" -> if (half%2==1) PowerState.SCHEDULED_OUTAGE else PowerState.NO_SCHEDULED_OUTAGE
                    else -> PowerState.UNKNOWN
                }
                Slot(half*30,half*30+30,state)
            }
            ScheduleDay(day.toString(),day.toString(),slots)
        }.sortedBy { it.date }.toList()
        if (days.none { it.date==today.toString() }) throw VoeFormatException("Джерело не опублікувало графік на сьогодні")
        if (days.map { it.date }.distinct().size != days.size) throw VoeFormatException("Неоднозначна дата графіка")
        return Schedule(at.toEpochMilli(),days,listOf(provider.label,"Сторонній план за чергою. Адресні аварії та фактичне відновлення не перевіряються.",provider.url),queue=group.removePrefix("GPV"),sourceUrl=provider.url)
    }
}
