package ua.svitlo.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ua.svitlo.app.domain.*
import ua.svitlo.app.widget.*
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class WidgetHierarchyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = Instant.parse("2026-10-22T18:00:00Z")
    private val window = notificationWindow(now)
    private val address = SavedAddress("1","Дім",AddressItem("1","Місто"),AddressItem("2","Вулиця"),AddressItem("3","15"))
    private fun chart(power: PowerState): NotificationChart {
        val future = power == PowerState.NO_SCHEDULED_OUTAGE
        val spans = if (future) listOf(ChartSpan(window.start,now.plusSeconds(3600),power),
            ChartSpan(now.plusSeconds(3600),window.end,PowerState.CONFIRMED_OUTAGE)) else listOf(ChartSpan(window.start,window.end,power))
        val status = when (power) {
            PowerState.EMERGENCY_OUTAGE -> "Аварійне · ще ≈ 6 год"
            PowerState.UNKNOWN -> "Аварійне · дані застаріли"
            else -> "Відключення о 22:00"
        }
        val row = ChartRow(address,status,power,spans,emergency = power == PowerState.EMERGENCY_OUTAGE || power == PowerState.UNKNOWN)
        return NotificationChart(window.start,window.end,now,listOf(row),1,listOf(address.title))
    }
    private fun red(pixel: Int) = Color.alpha(pixel)>0 && Color.red(pixel)>Color.green(pixel)+30 && Color.red(pixel)>Color.blue(pixel)+30
    @Test fun colourInTextDistinguishesCurrentEmergencyFromFutureOrUnverifiedStates() {
        val palette = WidgetPalette.resolve(context,WidgetConfig(1,emptyList()))
        for (power in listOf(PowerState.NO_SCHEDULED_OUTAGE,PowerState.EMERGENCY_OUTAGE,PowerState.UNKNOWN)) {
            val chart = chart(power)
            val image = AdaptiveWidgetTimeline.draw(chart,336f,1f,1,false,palette,false)
            val layout = AdaptiveWidgetTimeline.rowLayouts(chart,336f,1f,1,false).single()
            val axis = if (layout.graphOffset == null) 0f else 44f
            val baseline = axis+if (layout.inline) 15f else 32f
            var coloured = 0
            for (y in ((baseline-12f)*2).toInt()..((baseline+2f)*2).toInt())
                for (x in (layout.statusX*2).toInt() until image.width) if (red(image.getPixel(x,y))) coloured++
            assertEquals("$power must reserve red text for a current emergency",power == PowerState.EMERGENCY_OUTAGE,coloured>0)
        }
    }
    @Test fun shorterTextDoesNotChangeTimeGeometryOrContinuationAndStaysAchromaticInMono() {
        val chart = chart(PowerState.EMERGENCY_OUTAGE).let { it.copy(rows = it.rows.map { row -> row.copy(continues = true) }) }
        val colors = WidgetPalette.resolve(context,WidgetConfig(1,emptyList(),monochrome = true))
        val image = AdaptiveWidgetTimeline.draw(chart,336f,1f,1,true,colors,false)
        assertEquals(12L,java.time.Duration.between(chart.start,chart.end).toHours())
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val pixel = image.getPixel(x,y)
            if (Color.alpha(pixel)>0) { assertEquals(Color.red(pixel),Color.green(pixel)); assertEquals(Color.green(pixel),Color.blue(pixel)) }
        }
        assertEquals(Bitmap.Config.ARGB_8888,image.config)
    }
}
