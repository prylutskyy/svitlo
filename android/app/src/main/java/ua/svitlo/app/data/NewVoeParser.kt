package ua.svitlo.app.data

import org.jsoup.Jsoup
import ua.svitlo.app.domain.*
import java.time.Instant

/** Adapter for observed new-site HTML. Unsupported incidents/partial cells fail closed. */
object NewVoeParser {
    fun schedule(html: String, now: Instant): Schedule {
        val doc = Jsoup.parse(html)
        val cards = doc.select(".gpv-card")
        if (cards.size != 1) throw VoeFormatException("Новий VOE: неоднозначна картка адреси")
        val card = cards.single()
        val alerts = card.select(".alert")
        if (alerts.size != 1 || !alerts.single().hasClass("alert-ok") ||
            alerts.single().select("strong").text() != "Активних відключень не знайдено")
            throw VoeFormatException("Новий VOE: невідомий формат поточного відключення")
        val queue = Regex("^Черга (\\d+\\.\\d+)$").matchEntire(card.select("h3").text())?.groupValues?.get(1)
            ?: throw VoeFormatException("Новий VOE: відсутня черга")
        val tables = card.select(".gpv-table")
        if (tables.size != 1) throw VoeFormatException("Новий VOE: відсутній графік")
        val table = tables.single()
        table.attr("class","disconnection-detailed-table-container")
        var hour = 0
        table.children().forEach { cell ->
            val header = cell.hasClass("head")
            val day = !header && cell.hasClass("legend")
            if (day) hour = 0
            if (!header && !day && !cell.hasClass("cell")) throw VoeFormatException("Новий VOE: невідома комірка")
            var accepted = false
            val blocks = cell.children()
            if (!header && !day && blocks.size == 1 && blocks.single().hasClass("hour_block") && cell.text().isBlank()) {
                val fills = blocks.single().children()
                val confirmation = cell.classNames().intersect(setOf("confirm_2","confirm_3"))
                val title = "%02d:00-%02d:59".format(java.util.Locale.ROOT,hour,hour)
                if (cell.hasClass("has_disconnection") && confirmation.size == 1 && fills.size == 1 &&
                    fills.single().classNames() == setOf("fill",confirmation.single()) &&
                    fills.single().attr("style").replace(" ","").trimEnd(';') == "--start:0;--size:100" && fills.single().attr("title") == title) {
                    fills.single().remove()
                    val period = "%02d:00–%02d:00".format(java.util.Locale.ROOT,hour,hour+1)
                    blocks.single().attr("title",period).attr("aria-label",period)
                    accepted = true
                }
            }
            cell.removeClass("gpv-cell").addClass("disconnection-detailed-table-cell")
            if (day) cell.removeClass("legend").addClass("day_col")
            if (accepted) cell.addClass("full_hour")
            if (!header && !day) hour++
        }
        val parsed = VoeParser.schedule(table.outerHtml(),now)
        if (parsed.days.isEmpty() || parsed.days.any { it.date == null }) throw VoeFormatException("Новий VOE: невідома дата")
        return parsed.copy(queue = queue,messages = alerts.map { it.text() })
    }
}
