package ua.svitlo.app.data

import kotlinx.coroutines.suspendCancellableCoroutine
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
class VoeHttpException(val status: Int, val retryAt: Long?) : IOException("VOE: HTTP $status")
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

class VoeClient(private val base: HttpUrl = "https://voe.com.ua/".toHttpUrl()) {
    val requestCount = java.util.concurrent.atomic.AtomicLong()
    val receivedBytes = java.util.concurrent.atomic.AtomicLong()
    private val sessionCookies = mutableMapOf<String, List<Cookie>>()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                synchronized(sessionCookies) { sessionCookies[url.host] = (sessionCookies[url.host].orEmpty() + cookies).associateBy { it.name }.values.toList() }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(sessionCookies) {
                sessionCookies[url.host].orEmpty().filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
            }
        }).build()
    private val lookups = object : LinkedHashMap<String, Pair<Long, List<AddressItem>>>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<AddressItem>>>?) = size > 64
    }

    private suspend fun request(path: String, form: Map<String, String>? = null): String {
        val url = base.resolve(path) ?: throw IOException("Некоректна адреса запиту")
        val builder = Request.Builder().url(url).header("User-Agent", "Svitlo-Android/0.1")
            .header("X-Requested-With", "XMLHttpRequest")
        if (form != null) builder.post(FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build())
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
                            val source = it.body?.source() ?: throw IOException("Порожня відповідь VOE")
                            source.request(5_000_001)
                            val bytes = source.buffer.readByteArray()
                            receivedBytes.addAndGet(bytes.size.toLong())
                            if (bytes.size > 5_000_000) throw IOException("Завелика відповідь VOE")
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

    suspend fun suggestions(level: String, query: String, parent: String? = null): List<AddressItem> {
        require(level in setOf("city", "street", "house"))
        if (level != "city") require(parent?.matches(Regex("[0-9]+")) == true)
        val path = "/disconnection/detailed/autocomplete/read_$level" + if (parent == null) "" else "/$parent"
        val url = base.resolve(path)!!.newBuilder().addQueryParameter("q", query).build()
        val key = url.toString()
        synchronized(lookups) { lookups[key] }?.let { (at, rows) -> if (System.currentTimeMillis() - at < 86_400_000) return rows }
        val rows = VoeParser.suggestions(request(url.encodedPath + "?" + url.encodedQuery))
        synchronized(lookups) { lookups[key] = System.currentTimeMillis() to rows }
        return rows
    }

    /** Owned by one serialized batch: a single GET builds the session for all addresses. */
    inner class Batch : ScheduleBatch {
        private var fields: Map<String, String>? = null
        override suspend fun schedule(address: SavedAddress): Schedule {
            if (fields == null) fields = VoeParser.form(request("/disconnection/detailed"))
            val body = fields!! + mapOf("search_type" to "0", "city_id" to address.city.id,
                "street_id" to address.street.id, "house_id" to address.house.id,
                "city" to "", "street" to "", "house" to "", "_triggering_element_name" to "search",
                "_triggering_element_value" to "Показати", "_drupal_ajax" to "1")
            return try {
                val html = VoeParser.insertedForm(request("/disconnection/detailed?ajax_form=1&_wrapper_format=drupal_ajax", body))
                fields = VoeParser.form(html)
                VoeParser.schedule(html, Instant.now())
            } catch (e: Exception) {
                fields = null
                throw e
            }
        }
    }
}
