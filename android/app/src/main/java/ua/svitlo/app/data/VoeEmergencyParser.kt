package ua.svitlo.app.data

import org.jsoup.Jsoup
import ua.svitlo.app.domain.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

data class VoeEmergencyForm(val fields: Map<String,String>,val regions: List<AddressItem>)
data class VoeEmergencyRecord(val id: String,val kind: String,val addresses: String,val start: Long,
    val estimatedEnd: Long?,val actualEnd: Long?,val updatedAt: Long?)
data class VoeEmergencySnapshot(val checkedAt: Long,val records: List<VoeEmergencyRecord>)

/** Official region-wide journal, not the hourly queue plan. */
object VoeEmergencyParser {
    const val URL="https://voe.com.ua/disconnection/emergency"
    fun form(html: String): VoeEmergencyForm {
        val f=Jsoup.parse(html).select("form#disconnection-search-form").singleOrNull()
            ?: throw VoeFormatException("Журнал VOE не повернув форму пошуку")
        val fields=f.select("input[type=hidden][name]").associate {it.attr("name") to it.attr("value")}
        if(fields["form_id"]!="disconnection_search_form" || fields["form_build_id"].isNullOrBlank()) throw VoeFormatException("Змінилася форма журналу VOE")
        val regions=f.select("select[name=region] option[value]").filter{it.attr("value").matches(Regex("\\d+"))}.map {AddressItem(it.attr("value"),it.text())}
        if(regions.isEmpty()) throw VoeFormatException("Журнал VOE не повернув райони")
        return VoeEmergencyForm(fields,regions)
    }
    private val timeFormat=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
    private fun time(s:String):Long? = s.trim().takeIf {it.isNotBlank() && it!="-" && it!="—"}?.let {
        try {LocalDateTime.parse(it,timeFormat).atZone(KYIV).toInstant().toEpochMilli()}
        catch(_:Exception){throw VoeFormatException("Невідомий формат часу в журналі VOE")}
    }
    fun snapshot(html:String,regionId:String,now:Instant):VoeEmergencySnapshot {
        val doc=Jsoup.parse(html)
        if(doc.select("select[name=region] option[selected]").singleOrNull()?.attr("value")!=regionId)
            throw VoeFormatException("Журнал VOE повернув інший район")
        val table=doc.select("table.grey-table").singleOrNull() ?: throw VoeFormatException("Журнал VOE не повернув таблицю; стан аварій невідомий")
        val expected=listOf("acctype","accend_plan","city_list","addresses","accbegin","accend_fact","dtupdate","accidentid")
        if(expected.any {table.select("thead th.$it").size!=1}) throw VoeFormatException("Змінилися колонки журналу VOE")
        val rows=table.select("tbody tr.row").map {row ->
            fun cell(name:String)=row.select("td.$name").singleOrNull()?.text() ?: throw VoeFormatException("Неповний рядок журналу VOE")
            val id=cell("accidentid")
            if(!id.matches(Regex("\\d+"))) throw VoeFormatException("Невідомий ID події VOE")
            VoeEmergencyRecord(id,cell("acctype"),cell("addresses"),time(cell("accbegin")) ?: throw VoeFormatException("Немає початку події VOE"),time(cell("accend_plan")),time(cell("accend_fact")),time(cell("dtupdate")))
        }
        if(rows.isEmpty() && table.select("tbody tr").none {it.text().contains("немає",true) || it.text().contains("відсутн",true) || it.text().contains("не знайден",true)})
            throw VoeFormatException("Порожня таблиця VOE без підтвердження відсутності записів")
        return VoeEmergencySnapshot(now.toEpochMilli(),rows)
    }
    private fun clean(s:String)=s.trim().uppercase(Locale.ROOT).replace('’','\'').replace('ʼ','\'').replace(Regex("\\s+")," ")
    private fun city(s:String)=clean(s).replace(Regex("^(СЕЛО|МІСТО|СМТ|С\\.|М\\.)\\s*"),"")
    private fun street(s:String)=clean(s).replace(Regex("^ВУЛ\\.\\s*"),"ВУЛИЦЯ ").replace(Regex("^ПРОВ\\.\\s*"),"ПРОВУЛОК ").replace(Regex("^ПРОСП\\.\\s*"),"ПРОСПЕКТ ")
    private fun house(s:String):String {
        val letters=mapOf('A' to 'А','B' to 'В','C' to 'С','E' to 'Е','H' to 'Н','K' to 'К','M' to 'М','O' to 'О','P' to 'Р','T' to 'Т','X' to 'Х')
        return clean(s).replace(" ","").replace("-","").map{letters[it] ?: it}.joinToString("")
    }
    private val towns=Regex("(?:^|[,;]\\s*)([\\p{L}'’ʼ .()\\-]+):")
    private val road=Regex("^\\s*((?:вулиця|вул\\.|провулок|пров\\.|проспект|просп\\.|площа|бульвар|узвіз|тупик|шосе)\\s+.+?)\\s+(\\d.*)$",RegexOption.IGNORE_CASE)
    fun validate(address:VoeIncidentAddress) {
        require(address.city.isNotBlank() && address.house.matches(Regex("[0-9][\\p{L}0-9/\\- ]{0,29}"))) {"Вкажіть населений пункт і номер будинку"}
        require(Regex("^(ВУЛИЦЯ|ПРОВУЛОК|ПРОСПЕКТ|ПЛОЩА|БУЛЬВАР|УЗВІЗ|ТУПИК|ШОСЕ)\\s+\\S").containsMatchIn(street(address.street))) {"Вкажіть назву разом із типом: наприклад, вулиця Центральна або провулок Садовий"}
    }
    fun matches(record:VoeEmergencyRecord,address:VoeIncidentAddress):Boolean {
        val sections=towns.findAll(record.addresses).toList()
        sections.forEachIndexed {i,m ->
            if(city(m.groupValues[1])!=city(address.city)) return@forEachIndexed
            val end=sections.getOrNull(i+1)?.range?.first ?: record.addresses.length
            val body=record.addresses.substring(m.range.last+1,end)
            body.split(';').forEach {part ->
                val r=road.find(part.trim().trim(',')) ?: return@forEach
                if(street(r.groupValues[1])==street(address.street) && r.groupValues[2].split(',').any {house(it)==house(address.house)}) return true
            }
        }
        return false
    }
    fun incidents(snapshot:VoeEmergencySnapshot,address:VoeIncidentAddress):List<CurrentOutage> = snapshot.records.filter {it.actualEnd==null && matches(it,address)}.map {
        CurrentOutage(it.kind,it.kind.contains("аварій",true),it.start,it.estimatedEnd,"Час завершення орієнтовний.","Офіційний журнал VOE · ID ${it.id}")
    }
}
