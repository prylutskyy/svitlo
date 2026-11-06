package ua.svitlo.app

import org.junit.Assert.*
import org.junit.Test
import ua.svitlo.app.data.*
import ua.svitlo.app.domain.*
import java.time.Instant

class NewVoeParserTest {
    private val now = Instant.parse("2026-11-03T05:00:00Z")
    private fun fixture() = javaClass.getResource("/new-voe-khmelnytske-81-2-20261103.html")!!.readText()
    @Test fun `real new site graph retains all scheduled hours despite no active incident`() {
        val schedule = VoeParser.schedule(fixture(),now)
        assertEquals("1.2",schedule.queue)
        assertNull(schedule.currentOutage)
        val day = schedule.days.single()
        assertEquals("2026-11-03",day.date)
        assertEquals(48,day.slots.size)
        val marked = day.slots.filter { it.state == PowerState.SCHEDULED_OUTAGE }
        assertEquals(480,marked.sumOf { it.endMinute-it.startMinute })
        assertEquals((480 until 600 step 30).toList()+(720 until 960 step 30).toList()+(1200 until 1320 step 30).toList(),marked.map { it.startMinute })
    }
    @Test fun `unobserved partial cells remain unknown rather than clear`() {
        val schedule = VoeParser.schedule(fixture().replace("--start:0;--size:100","--start:25;--size:50"),now)
        assertEquals(16,schedule.days.single().slots.count { it.state == PowerState.UNKNOWN })
    }
    @Test(expected = VoeFormatException::class) fun `active incident shape cannot become an all clear`() {
        VoeParser.schedule(fixture().replace("alert-ok","alert-warning"),now)
    }
    @Test(expected = VoeFormatException::class) fun `missing published table is rejected`() {
        NewVoeParser.schedule("<div class='gpv-card'><h3>Черга 1.2</h3><div class='alert alert-ok'><strong>Активних відключень не знайдено</strong></div></div>",now)
    }
    @Test(expected = VoeFormatException::class) fun `truncated day is rejected`() {
        val doc = org.jsoup.Jsoup.parse(fixture())
        doc.select(".gpv-table > .cell").last()!!.remove()
        VoeParser.schedule(doc.outerHtml(),now)
    }
}
