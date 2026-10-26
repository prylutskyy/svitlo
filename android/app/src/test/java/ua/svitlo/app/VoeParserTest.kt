package ua.svitlo.app

import org.junit.Assert.*
import org.junit.Test
import ua.svitlo.app.data.*
import ua.svitlo.app.domain.*
import java.time.Instant

class VoeParserTest {
    private val now = Instant.parse("2026-10-18T10:00:00Z")
    private fun fixture(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun `real city response preserves provider IDs`() {
        val rows = VoeParser.suggestions(fixture("cities.json"))
        assertEquals("510100000", rows.first().id)
        assertTrue(rows.first().name.contains("Вінниця"))
    }
    @Test fun `one street is still an array`() {
        assertEquals(listOf(AddressItem("1652", "вулиця. Соборна")), VoeParser.suggestions(fixture("streets.json")))
    }
    @Test fun `real schedule yields 48 half-hour slots without inventing outage semantics`() {
        val schedule = VoeParser.schedule(fixture("schedule.html"), now)
        assertEquals(1, schedule.days.size)
        assertEquals("2026-10-18", schedule.days.single().date)
        assertEquals(48, schedule.days.single().slots.size)
        assertEquals(1440, schedule.days.single().slots.last().endMinute)
        assertTrue(schedule.days.single().slots.all { it.state == PowerState.NO_SCHEDULED_OUTAGE })
    }
    @Test fun `new outage markers stay unknown with their evidence`() {
        val html = fixture("schedule.html").replaceFirst("no_disconnection", "has_disconnection confirm_1")
        val slots = VoeParser.schedule(html, now).days.single().slots
        assertEquals(PowerState.UNKNOWN, slots[0].state)
        assertEquals(PowerState.UNKNOWN, slots[1].state)
        assertTrue(slots[0].source.contains("confirm_1"))
        assertEquals(PowerState.NO_SCHEDULED_OUTAGE, slots[2].state)
    }
    @Test fun `partial hour child markup cannot be mistaken for no outage`() {
        val html = fixture("schedule.html").replaceFirst("class=\"hour_block\"", "class=\"hour_block\" style=\"width:50%\"")
        assertEquals(PowerState.UNKNOWN, VoeParser.schedule(html, now).days.single().slots.first().state)
    }
    @Test(expected = VoeFormatException::class) fun `changed headings fail closed`() {
        VoeParser.schedule(fixture("schedule.html").replaceFirst("00-01", "00-02"), now)
    }
    @Test fun `no table is missing data not a clear day`() {
        assertTrue(VoeParser.schedule("<div>Немає даних</div>", now).days.isEmpty())
    }
    @Test fun `Drupals textarea envelope is supported`() {
        assertEquals("insert", VoeParser.commands("<textarea>[{\"command\":\"insert\"}]</textarea>").getJSONObject(0).getString("command"))
    }
    @Test(expected = VoeFormatException::class) fun `ordinary HTML is not decoded as data`() {
        VoeParser.commands("<html>maintenance</html>")
    }
    @Test fun `dates far from the fetch time remain unresolved`() {
        assertNull(VoeParser.schedule(fixture("schedule.html"), now.plusSeconds(30 * 86400)).days.single().date)
    }
}
