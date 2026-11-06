package ua.svitlo.app.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.jsoup.Jsoup
import ua.svitlo.app.domain.*
import java.io.IOException
import java.time.*
import java.util.concurrent.TimeUnit
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class VoeFormatException(message: String) : IOException(message)
class VoeHttpException(val status: Int, val retryAt: Long?) : IOException("Джерело: HTTP $status")
fun interface ScheduleBatch { suspend fun schedule(address: SavedAddress): Schedule }

object VoeParser {
    fun form(html: String): Map<String, String> {
        val forms = Jsoup.parse(html).select("form.disconnection-detailed-search-form")
        if (forms.size != 1) throw VoeFormatException("VOE змінив форму пошуку")
        val fields = forms.single().select("input[name]").filter {
            it.attr("type") !in listOf("radio", "checkbox") || it.hasAttr("checked")
        }.associate { it.attr("name") to it.attr("value") }
        if (fields["form_id"] != "disconnection_detailed_search_form" || fields["form_build_id"].isNullOrBlank())
            throw VoeFormatException("Відсутній стан форми VOE")
        return fields
    }

    fun commands(raw: String): JSONArray {
        val text = if (raw.trimStart().startsWith("<textarea>")) {
            val nodes = Jsoup.parse(raw).body().children()
            if (nodes.size != 1 || nodes.single().tagName() != "textarea")
                throw VoeFormatException("Невідома відповідь VOE")
            nodes.single().wholeText()
        } else raw
        return try { JSONArray(text) } catch (_: Exception) { throw VoeFormatException("VOE повернув некоректний JSON") }
    }

    fun suggestions(raw: String): List<AddressItem> {
        val array = commands(raw)
        return List(array.length()) { i ->
            val item = array.getJSONObject(i)
            val labels = Jsoup.parseBodyFragment(item.getString("label")).select(".change_api_autocomplit[data-id]")
            if (labels.size != 1 || !labels.single().attr("data-id").matches(Regex("[0-9]+")))
                throw VoeFormatException("Не вдалося прочитати ID адреси")
            AddressItem(labels.single().attr("data-id"), item.getString("value"))
        }
    }

    fun insertedForm(raw: String): String {
        val array = commands(raw)
        val forms = (0 until array.length()).map { array.getJSONObject(it) }.filter {
            it.optString("command") == "insert" && it.optString("data").contains("disconnection-detailed-search-form")
        }
        if (forms.size != 1) throw VoeFormatException("VOE не повернув форму графіка")
        return forms.single().getString("data")
    }

    fun schedule(html: String, now: Instant): Schedule {
        val doc = Jsoup.parse(html)
        if (doc.select(".gpv-card, .gpv-table, #gpvResult").isNotEmpty()) return NewVoeParser.schedule(html,now)
        val days = mutableListOf<ScheduleDay>()
        doc.select(".disconnection-detailed-table-container").forEach { table ->
            val cells = table.children().filter { it.hasClass("disconnection-detailed-table-cell") }
            val headings = cells.filter { it.hasClass("head") }.map { it.text() }
            val expected = (0..23).map { String.format(Locale.ROOT, "%02d-%02d", it, it + 1) }
            if (headings.size != 25 || headings.drop(1) != expected) throw VoeFormatException("Змінився формат часової шкали VOE")
            var label: String? = null
            var date: String? = null
            var slots = mutableListOf<Slot>()
            fun finishDay() {
                label?.let {
                    if (slots.size != 48) throw VoeFormatException("VOE повернув неповну добу")
                    days += ScheduleDay(it, date, slots.toList())
                }
            }
            cells.filterNot { it.hasClass("head") }.forEach { cell ->
                if (cell.hasClass("day_col")) {
                    finishDay()
                    label = cell.text()
                    date = resolveDate(cell.text(), now)
                    slots = mutableListOf()
                } else {
                    if (label == null || !cell.hasClass("cell") || slots.size >= 48)
                        throw VoeFormatException("Невідома структура графіка VOE")
                    val state = cellState(cell,slots.size / 2)
                    repeat(2) {
                        val start = slots.size * 30
                        slots += Slot(start, start + 30, state, if (state != PowerState.NO_SCHEDULED_OUTAGE) cell.outerHtml() else "")
                    }
                }
            }
            finishDay()
        }
        val messages = doc.select(".disconnection-detailed-table-message, .disconnection-detailed-table__empty, .messages--error").map { it.text() }
        val incident = doc.select(".disconnection-detailed-table-message").firstOrNull {
            it.select("p").any { p -> p.text().equals("За вашою адресою в даний момент відсутня електроенергія", ignoreCase = true) }
        }?.let { block ->
            val lines = block.select("p").map { it.text() }
            val reason = lines.firstOrNull { it.startsWith("Причина відключення:", ignoreCase = true) }
                ?.substringAfter(':')?.trim().orEmpty().ifEmpty { "Причину не вказано" }
            fun timestamp(prefix: String): Long? {
                val line = lines.firstOrNull { it.startsWith(prefix, ignoreCase = true) } ?: return null
                val value = Regex("(\\d{2}:\\d{2})\\s+(\\d{4}\\.\\d{2}\\.\\d{2})").find(line) ?: return null
                return runCatching { LocalDateTime.parse("${value.groupValues[2]} ${value.groupValues[1]}",
                    java.time.format.DateTimeFormatter.ofPattern("uuuu.MM.dd HH:mm")
                        .withResolverStyle(java.time.format.ResolverStyle.STRICT)).atZone(KYIV).toInstant().toEpochMilli() }.getOrNull()
            }
            val start = timestamp("Час початку")
            val end = (timestamp("Орієнтовний час відновлення") ?: timestamp("Орієнтовний час завершення"))?.takeIf { start == null || it > start }
            CurrentOutage(reason, reason.equals("Аварійне відключення", ignoreCase = true), start, end,
                block.select(".disconnection-message__warning").text(), block.text())
        }
        val queue = doc.select(".disconnection-detailed-table > p").map { it.text() }
            .firstOrNull { it.matches(Regex("\\d+\\.\\d+\\s+черга",RegexOption.IGNORE_CASE)) }?.substringBefore(' ')
        return Schedule(now.toEpochMilli(), days, messages, incident, queue)
    }

    private fun cellState(cell: org.jsoup.nodes.Element,hour: Int): PowerState {
        val layout = setOf("disconnection-detailed-table-cell","cell","current_day","grey")
        val children = cell.getAllElements().drop(1)
        if (cell.attributes().size() != 1 || cell.text().isNotBlank()) return PowerState.UNKNOWN
        if (cell.hasClass("no_disconnection") && (layout + "no_disconnection").containsAll(cell.classNames()) && children.all {
            it.tagName() == "div" && it.classNames() == setOf("hour_block") && it.attributes().size() == 1 && it.children().isEmpty() && it.text().isBlank()
        }) return PowerState.NO_SCHEDULED_OUTAGE
        val period = String.format(Locale.ROOT,"%02d:00–%02d:00",hour,hour+1)
        val confirmation = cell.classNames().intersect(setOf("confirm_2","confirm_3"))
        if (cell.hasClass("has_disconnection") && cell.hasClass("full_hour") && confirmation.size == 1 &&
            (layout + setOf("has_disconnection","full_hour") + confirmation).containsAll(cell.classNames()) && children.size == 1) {
            val block = children.single()
            if (block.tagName() == "div" && block.classNames() == setOf("hour_block","has_any") &&
                block.attributes().map { it.key }.toSet() == setOf("class","title","aria-label") && block.children().isEmpty() &&
                block.attr("title") == period && block.attr("aria-label") == period) return PowerState.SCHEDULED_OUTAGE
        }
        return PowerState.UNKNOWN
    }

    /** The source has no year; accept only a nearby unambiguous calendar date. */
    private fun resolveDate(label: String, now: Instant): String? {
        val match = Regex("(\\d{2})\\.(\\d{2})").find(label) ?: return null
        val today = now.atZone(KYIV).toLocalDate()
        return (today.year - 1..today.year + 1).mapNotNull { year ->
            runCatching { LocalDate.of(year, match.groupValues[2].toInt(), match.groupValues[1].toInt()) }.getOrNull()
        }.filter { kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(today, it)) <= 7 }.singleOrNull()?.toString()
    }
}

class VoeClient(private val base: HttpUrl = "https://raw.githubusercontent.com/".toHttpUrl(), private val feedUrl: (FeedProvider)->String = {it.url}, private val emergencyUrl: String = VoeEmergencyParser.URL,
                private val feedClock: () -> Long = System::currentTimeMillis) {
    val requestCount = java.util.concurrent.atomic.AtomicLong()
    val receivedBytes = java.util.concurrent.atomic.AtomicLong()
    private val cookies=mutableListOf<Cookie>()
    private val http = OkHttpClient.Builder().cookieJar(object: CookieJar {
        override fun saveFromResponse(url:HttpUrl,received:List<Cookie>)=synchronized(cookies) {
            received.forEach { c -> cookies.removeAll{it.name==c.name && it.domain==c.domain && it.path==c.path};cookies.add(c) }
        }
        override fun loadForRequest(url:HttpUrl):List<Cookie> = synchronized(cookies) {
            cookies.removeAll{it.expiresAt<System.currentTimeMillis()};cookies.filter{it.matches(url)}
        }
    })
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    internal suspend fun request(path: String,body: FormBody? = null,revalidate: Boolean = false): String {
        val url = base.resolve(path) ?: throw IOException("Некоректна адреса запиту")
        val builder = Request.Builder().url(url).header("User-Agent", "Svitlo-Android/0.4")
        if (revalidate) builder.cacheControl(CacheControl.FORCE_NETWORK)

        if(body!=null) builder.post(body)
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(builder.build())
            requestCount.incrementAndGet()
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use {
                            if (!it.isSuccessful) {
                                val retry = it.header("Retry-After")?.let { value ->
                                    value.toLongOrNull()?.coerceIn(0, 604800)?.let { seconds -> System.currentTimeMillis() + seconds * 1000 }
                                        ?: runCatching { ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
                                }
                                throw VoeHttpException(it.code, retry)
                            }
                            val source = it.body?.source() ?: throw IOException("Порожня відповідь джерела")
                            source.request(5_000_001)
                            val bytes = source.buffer.readByteArray()
                            receivedBytes.addAndGet(bytes.size.toLong())
                            if (bytes.size > 5_000_000) throw IOException("Завелика відповідь джерела")
                            bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                        }
                        if (continuation.isActive) continuation.resume(text)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    private val cacheMutex = kotlinx.coroutines.sync.Mutex()
    private val cache = java.util.LinkedHashMap<String,Pair<Long,String>>(16,.75f,true)
    internal suspend fun prominText(path: String, ttl: Long = 120_000): String {
        require(!path.startsWith("http") && !path.startsWith("//") && ".." !in path)
        cacheMutex.lock()
        try {
            cache[path]?.let { (at,raw) -> if (System.currentTimeMillis()-at < ttl) return raw }
            val raw = request("https://promin.vlasen.com"+path)
            cache[path] = System.currentTimeMillis() to raw
            while (cache.size>24 || cache.values.sumOf { it.second.length.toLong() }>10_000_000) cache.remove(cache.keys.first())
            return raw
        } finally { cacheMutex.unlock() }
    }
    suspend fun prominConfig(id: String): ProminConfig {
        val city = ProminCities.get(id)
        if (city.queueOnly) return ProminParser.queueConfig(city, prominText(city.base + "api/schedule"))
        return ProminParser.config(city, prominText(city.base + "api/config", 3_600_000))
    }
    suspend fun prominStreets(id: String) = ProminParser.streets(prominText(ProminCities.get(id).base + "api/streets", 3_600_000))
    suspend fun prominHouses(id: String, street: ProminStreet) = ProminParser.houses(prominText(
        ProminCities.get(id).base + "api/street?id=${street.id}&city=${street.cityId}", 120_000))
    private val feedMutex=kotlinx.coroutines.sync.Mutex()
    private val feeds=mutableMapOf<String,Pair<Long,String>>()
    private suspend fun feedText(id:String,forceRefresh:Boolean = false):String {
        feedMutex.lock()
        try {
            val provider=FeedProviders.get(id)
            if (!forceRefresh) feeds[id]?.let {
                val age = feedClock()-it.first
                if (age in 0 until RefreshPolicy.FOREGROUND_MILLIS) return it.second
            }
            val raw=request(feedUrl(provider),revalidate=true)
            feeds[id]=feedClock() to raw
            return raw
        } finally {feedMutex.unlock()}
    }
    suspend fun feedGroups(id:String)=QueueFeedParser.groups(feedText(id),Instant.now())
    val emergency=VoeEmergencyClient(this,emergencyUrl)
    inner class Batch(private val forceRefresh:Boolean = false) : ScheduleBatch {
        private val batchMutex = kotlinx.coroutines.sync.Mutex()
        private val batchFeeds = mutableMapOf<String,Result<String>>()
        // One source snapshot (or failure) per operator, even when manual refresh bypasses cache.
        private suspend fun text(id:String):String = batchMutex.withLock {
            val result = batchFeeds[id] ?: run {
                val fetched = try { Result.success(feedText(id,forceRefresh))
                } catch(e:kotlinx.coroutines.CancellationException) { throw e
                } catch(e:Exception) { Result.failure<String>(e) }
                batchFeeds[id] = fetched
                fetched
            }
            result.getOrThrow()
        }
        override suspend fun schedule(address:SavedAddress):Schedule {
            val id=address.feedId?.takeUnless{it.startsWith("promin/")}
                ?: throw VoeFormatException("Виберіть оператора та чергу: «Джерело та аварії».")
            val provider=FeedProviders.get(id)
            val group=address.feedGroup ?: throw VoeFormatException("Виберіть чергу")
            suspend fun <T> capture(f:suspend ()->T):Result<T> = try{Result.success(f())}
                catch(e:kotlinx.coroutines.CancellationException){throw e}
                catch(e:Exception){Result.failure(e)}
            val plan=capture{QueueFeedParser.schedule(text(id),group,provider,Instant.now())}
            val incidentAddress=address.voeIncidentAddress
            if(incidentAddress==null) return plan.getOrThrow()
            val events=capture{emergency.snapshot(incidentAddress.regionId,Instant.now())}
            return VoeEmergencyClient.combine(plan,events,incidentAddress,provider.url,Instant.now())
        }
    }
}
