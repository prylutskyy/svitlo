package ua.svitlo.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ua.svitlo.app.background.NotificationTimeline
import ua.svitlo.app.domain.*
import ua.svitlo.app.widget.SvitloWidgets
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class WidgetTimelineDesignTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val now = Instant.parse("2026-10-19T18:00:00Z") // 21:00 Kyiv
    private fun fixture(time: Instant = now): AppState {
        val addresses = listOf("Дім","Батьки","Робота").mapIndexed { index,title ->
            SavedAddress("design-$index",title,AddressItem("1","Вінниця"),AddressItem("2","Вулиця"),AddressItem("3","9")) }
        val date = time.atZone(KYIV).toLocalDate()
        val days = (0L..1L).map { offset -> ScheduleDay("",date.plusDays(offset).toString(),listOf(
            Slot(0,360,PowerState.POSSIBLE_OUTAGE),Slot(360,1320,PowerState.NO_SCHEDULED_OUTAGE),Slot(1320,1440,PowerState.CONFIRMED_OUTAGE))) }
        return AppState(addresses = addresses,schedules = addresses.mapIndexed { index,address ->
            CachedSchedule(address.id,Schedule(time.toEpochMilli(),if (index == 2) days.map { it.copy(slots = listOf(Slot(0,1440,PowerState.NO_SCHEDULED_OUTAGE))) } else days,emptyList(),
                if (index == 0) CurrentOutage("Аварія",true,time.minusSeconds(3600).toEpochMilli(),time.plusSeconds(7200).toEpochMilli(),"","") else null)) })
    }
    private fun chart() = NotificationChart.create(fixture(),now,3)

    @Test fun compactRowsFitWithoutShrinkingAndLongTextFallsBack() {
        val chart = chart()
        assertTrue(NotificationTimeline.rowLayouts(chart,360f,1f,1).all { it.inline })
        val narrow = chart.copy(rows = chart.rows.map { it.copy(address = it.address.copy(title = "Дуже довга назва адреси")) })
        assertFalse(NotificationTimeline.rowLayouts(narrow,160f,1.5f,1).first().inline)
        val fitted = NotificationTimeline.fitWidget(chart,360f,155f,1f,1)
        assertEquals(3,fitted.rows.size)
        assertEquals(chart.rows.map { it.address.id },fitted.rows.map { it.address.id })
        val bitmap = NotificationTimeline.draw(fitted,false,360f,widget = true)
        assertTrue(bitmap.height/2f <= 155f)
        val taller = NotificationTimeline.fitWidget(chart,360f,450f,1f,1)
        assertEquals(bitmap.height,NotificationTimeline.draw(taller,false,360f,widget = true).height)
    }

    @Test fun sharedMidnightGuideCrossesGapsButNeverText() {
        val chart = chart()
        val width = 360f
        val bitmap = NotificationTimeline.draw(chart,false,width,widget = true)
        val midnight = now.atZone(KYIV).toLocalDate().plusDays(1).atStartOfDay(KYIV).toInstant()
        val fraction = java.time.Duration.between(chart.start,midnight).toMillis().toFloat()/java.time.Duration.between(chart.start,chart.end).toMillis()
        val x = ((2f+(width-8f)*fraction)*2).toInt()
        // Whitespace between the first bar and the next address used to have no guide.
        assertTrue((126 until 140).any { y -> Color.alpha(bitmap.getPixel(x,y)) > 0 })

        val long = chart.copy(rows = chart.rows.map { it.copy(address = it.address.copy(title = "Надзвичайно довга назва адреси")) })
        val protected = NotificationTimeline.draw(long,false,width,widget = true)
        val shift = 12*3600L // same geometry, but its daytime window contains no midnight
        val daytime = long.copy(start = long.start.plusSeconds(shift),end = long.end.plusSeconds(shift),now = long.now.plusSeconds(shift),
            rows = long.rows.map { row -> row.copy(spans = row.spans.map { it.copy(start = it.start.plusSeconds(shift),end = it.end.plusSeconds(shift)) }) })
        val reference = NotificationTimeline.draw(daytime,false,width,widget = true)
        for (y in 67..94) for (column in x-2..x+2)
            assertEquals("Guide must leave the title intact at $column,$y",reference.getPixel(column,y),protected.getPixel(column,y))
    }

    @Test fun renderRealWidgetsAcrossWidthsThemesAndMidnight() {
        data class Case(val name: String,val width: Int,val height: Int,val scale: Float = 1f,val dark: Boolean = false,
            val mono: Boolean = false,val transparent: Boolean = false,val time: Instant = now,val contentScale: Float = 1f)
        val cases = listOf(Case("light",360,230),Case("dark",360,230,dark = true),Case("narrow",180,300),
            Case("large",360,390,1.5f),Case("largest",360,480,1.5f,contentScale = 1.4f),Case("mono",360,230,dark = true,mono = true),
            Case("transparent",360,230,dark = true,transparent = true),
            Case("midnight-before",360,250,time = Instant.parse("2026-10-19T20:53:00Z")),
            Case("midnight-after",360,250,time = Instant.parse("2026-10-19T21:07:00Z")))
        instrumentation.runOnMainSync {
            cases.forEach { case ->
                val configuration = Configuration(context.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (case.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    fontScale = case.scale
                }
                val themed = context.createConfigurationContext(configuration)
                val state = fixture(case.time)
                val config = WidgetConfig(99,state.addresses.map { it.id },monochrome = case.mono,hideTitle = true,hideSettings = true,
                    transparentBackground = case.transparent,transparentText = "light",contentScale = case.contentScale)
                val root = SvitloWidgets.views(themed,state,config,99,case.width,case.height,case.time).apply(themed,FrameLayout(themed))
                val density = themed.resources.displayMetrics.density
                root.measure(View.MeasureSpec.makeMeasureSpec((case.width*density).toInt(),View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((case.height*density).toInt(),View.MeasureSpec.EXACTLY))
                root.layout(0,0,root.measuredWidth,root.measuredHeight)
                val bitmap = Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                if (case.transparent) canvas.drawColor(Color.rgb(40,55,70))
                root.draw(canvas)
                val directory = File(context.getExternalFilesDir(null),"verification").apply { mkdirs() }
                File(directory,"widget-design-${case.name}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
        }
        cases.forEach { case ->
            val name = "widget-design-${case.name}.png"
            instrumentation.uiAutomation.executeShellCommand("cp ${context.getExternalFilesDir(null)}/verification/$name /sdcard/Download/$name").use {
                ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
        }
    }
}
