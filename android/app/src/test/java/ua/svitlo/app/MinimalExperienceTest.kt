package ua.svitlo.app

import org.junit.Assert.*
import org.junit.Test
import ua.svitlo.app.domain.*
import java.time.Instant
import java.time.LocalDate

class MinimalExperienceTest {
    private val now = Instant.parse("2026-10-26T07:00:00Z")
    private val addresses = listOf("a","b").map { SavedAddress(it,it,AddressItem("1","Вінниця"),AddressItem("2","Вулиця"),AddressItem("3","3")) }
    private val state = AppState(addresses = addresses,schedules = addresses.map {
        CachedSchedule(it.id,Schedule(now.toEpochMilli(),listOf(ScheduleDay("","2026-10-26",listOf(Slot(0,1440,PowerState.NO_SCHEDULED_OUTAGE)))),emptyList()))
    })
    @Test fun `date groups use Kyiv midnight and newest events first`() {
        fun event(id: Long, at: String) = Instant.parse(at).toEpochMilli().let { HistoryIncident(id,it,it,it,null,false,"",null,true) }
        val old = event(1,"2026-10-25T21:45:00Z")
        val midnight = event(2,"2026-10-25T22:15:00Z")
        val later = event(3,"2026-10-26T01:00:00Z")
        val days = HistoryJournal.days(listOf(old,midnight,later))
        assertEquals(listOf(LocalDate.parse("2026-10-26"),LocalDate.parse("2026-10-25")),days.keys.toList())
        assertEquals(listOf(later,midnight),days.values.first())
        assertEquals("Сьогодні",HistoryJournal.label(days.keys.first(),now))
        assertEquals("Вчора",HistoryJournal.label(days.keys.last(),now))
    }
    @Test fun `comparison retains ordered locations while single presets retain one`() {
        val raw = WidgetConfig(1,listOf("b","a","b","deleted"),transparentBackground = true,roundedCorners = false,hideWhenQuiet = true)
        val multi = raw.forStyle(state,now,WidgetStyle.TIMELINE)
        assertEquals(listOf("b","a"),multi.addressIds)
        assertTrue(multi.alwaysShowGraphs)
        for (kind in listOf(WidgetStyle.SHORT,WidgetStyle.CARD,WidgetStyle.MONO)) {
            val one = raw.forStyle(state,now,kind)
            assertEquals(1,one.addressIds.size)
            assertTrue(one.transparentBackground)
            assertEquals(false,one.roundedCorners)
            assertEquals(true,one.hideWhenQuiet)
            assertEquals(kind,one.style)
        }
    }
    @Test fun `every preset preserves per widget scale and hidden menu through persistence`() {
        for (style in WidgetStyle.entries) {
            val raw = WidgetConfig(7,listOf("a"),contentScale = 1.2f,hideSettings = true)
            val normalized = raw.forStyle(state,now,style)
            assertEquals(1.2f,normalized.sizeMultiplier,0.001f)
            assertTrue(normalized.hideSettings)
            val saved = com.google.gson.Gson().fromJson(com.google.gson.Gson().toJson(normalized),WidgetConfig::class.java)
            assertEquals(normalized,saved.forStyle(state,now,style))
            assertEquals(1.8f,normalized.effectiveFontScale(1.5f),0.001f)
        }
    }
    @Test fun `monochrome inversion changes both opaque and transparent ink choices`() {
        val raw = WidgetConfig(1,listOf("a"),inverted = true)
        val inverted = raw.forStyle(state,now,WidgetStyle.MONO)
        assertTrue(inverted.monochrome)
        assertEquals("light",inverted.surfaceStyle)
        assertEquals("dark",inverted.transparentText)
        assertEquals("dark",raw.copy(inverted = false).forStyle(state,now,WidgetStyle.MONO).surfaceStyle)
    }
    @Test fun `quiet widgets are visible by default and hide only by explicit choice`() {
        val chart = WidgetConfig(1,listOf("a")).chart(state,now)
        val calm = requireNotNull(WidgetFocusCard.forWidget(state,chart,false))
        assertEquals("✓",calm.time)
        assertNull(WidgetFocusCard.forWidget(state,chart,true))
        val missing = state.copy(schedules = emptyList())
        val unknown = requireNotNull(WidgetFocusCard.forWidget(missing,WidgetConfig(1,listOf("a")).chart(missing,now),false))
        assertEquals("Стан уточнюється",unknown.label)
        assertNotEquals("✓",unknown.time)
    }
}
