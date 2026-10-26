package ua.svitlo.app

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ua.svitlo.app.domain.*
import ua.svitlo.app.widget.*
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

@RunWith(AndroidJUnit4::class)
class WidgetGeometryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = Instant.parse("2026-10-22T18:30:00Z")
    private fun chart(title: String = "Дім",status: String = "Аварійне · ще ≈ 6 год"): NotificationChart {
        val window = notificationWindow(now)
        val address = SavedAddress("geometry",title,AddressItem("1","Місто"),AddressItem("2","Вулиця"),AddressItem("3","15"))
        val row = ChartRow(address,status,PowerState.EMERGENCY_OUTAGE,
            listOf(ChartSpan(window.start,window.end,PowerState.EMERGENCY_OUTAGE)),emergency = true,continues = true)
        return NotificationChart(window.start,window.end,now,listOf(row),1,listOf(title))
    }
    private val colors get() = WidgetPalette.resolve(context,WidgetConfig(1,emptyList()))

    @Test fun exactEndLabelExplainsTheTwelveHourWindowAtHalfPastNine() {
        val time = Instant.parse("2026-10-22T06:30:00Z") // 09:30 Kyiv
        val window = notificationWindow(time)
        assertEquals(12L,Duration.between(window.start,window.end).toHours())
        assertEquals("08:30",window.start.atZone(KYIV).format(DateTimeFormatter.ofPattern("HH:mm")))
        val chart = chart().copy(start = window.start,end = window.end,now = time)
        for (width in listOf(136f,180f,336f,576f)) for (scale in listOf(.8f,1f,2.1f))
            assertTrue("$width/$scale must show the exact end",AdaptiveWidgetTimeline.axisLabels(chart,width,scale).any { it.value == "20:30" })
    }

    @Test fun statusIsRightAlignedWhenItFitsAndWrapsOnlyWhenNecessary() {
        val chart = chart()
        val wide = AdaptiveWidgetTimeline.rowLayouts(chart,336f,1f,1,false).single()
        assertTrue(wide.inline)
        assertTrue(wide.statusX > wide.textX)
        assertTrue(wide.titleRight < wide.statusX)
        val narrow = AdaptiveWidgetTimeline.rowLayouts(chart,136f,1.5f,1,false).single()
        assertFalse(narrow.inline)
        assertEquals(narrow.textX,narrow.statusX,0f)
    }

    @Test fun nowMarkerCrossesAndExtendsBelowTheLastBarAtEveryDensity() {
        val chart = chart()
        for (density in 0..2) for (scale in listOf(.8f,1f,2.1f)) for (width in listOf(136f,336f)) {
            val image = AdaptiveWidgetTimeline.draw(chart,width,scale,density,false,colors,false)
            val layout = AdaptiveWidgetTimeline.rowLayouts(chart,width,scale,density,false).single()
            val rail = 44f*scale+layout.graphOffset!!
            val x = (AdaptiveWidgetTimeline.Axis(chart,width).x(now)*2).toInt()
            for (offset in listOf(0f,4f,6f)) assertTrue("Marker missing below rail at $density/$scale/$width/$offset",
                (-1..1).any { dx -> Color.alpha(image.getPixel(x+dx,((rail+offset*scale)*2).toInt())) > 0 })
        }
    }

    @Test fun midnightIsSolidAcrossWhitespaceAndCutOnlyAcrossActualText() {
        val chart = chart("Дуже довга назва адреси","Аварійне · час уточнюється")
        val width = 336f
        val image = AdaptiveWidgetTimeline.draw(chart,width,1f,1,false,colors,false)
        val layout = AdaptiveWidgetTimeline.rowLayouts(chart,width,1f,1,false).single()
        assertFalse(layout.inline)
        val midnight = now.atZone(KYIV).toLocalDate().plusDays(1).atStartOfDay(KYIV).toInstant()
        val x = (AdaptiveWidgetTimeline.Axis(chart,width).x(midnight)*2).toInt()
        // Between the status descenders and the rail there must be no dashed gaps.
        for (y in 44+38..44+42) assertTrue("Guide gap at $y",
            (0..1).any { dx -> Color.alpha(image.getPixel(x+dx,y*2)) > 0 })
        // With identical geometry but no midnight, glyph pixels must remain unchanged.
        val shift = 12*3600L
        val reference = chart.copy(start = chart.start.plusSeconds(shift),end = chart.end.plusSeconds(shift),now = chart.now.plusSeconds(shift),
            rows = chart.rows.map { row -> row.copy(spans = row.spans.map { it.copy(start = it.start.plusSeconds(shift),end = it.end.plusSeconds(shift)) }) })
        val daytime = AdaptiveWidgetTimeline.draw(reference,width,1f,1,false,colors,false)
        for (y in (44+4)*2..(44+16)*2) for (dx in -1..1)
            assertEquals("Guide changed title glyphs",daytime.getPixel(x+dx,y),image.getPixel(x+dx,y))
    }
}
