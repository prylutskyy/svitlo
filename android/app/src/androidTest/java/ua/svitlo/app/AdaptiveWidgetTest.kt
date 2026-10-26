package ua.svitlo.app

import android.content.Context
import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ua.svitlo.app.domain.*
import ua.svitlo.app.widget.*
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class AdaptiveWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = Instant.parse("2026-10-20T18:00:00Z")
    private val window = notificationWindow(now)
    private fun row(name: String, power: PowerState = PowerState.NO_SCHEDULED_OUTAGE, status: String = "") =
        ChartRow(SavedAddress(name,name,AddressItem("1","Вінниця"),AddressItem("2","Вулиця"),AddressItem("3","9")),
            status,power,listOf(ChartSpan(window.start,window.end,power)))
    private fun chart(vararg rows: ChartRow) = NotificationChart(window.start,window.end,now,rows.toList(),rows.size,rows.map { it.address.title })

    @Test fun onlyEventsKeepTimelineEvenWhenOtherDataIsUnknown() {
        val calm = row("Дім")
        val chart = chart(calm)
        assertTrue(AdaptiveWidgetTimeline.compact(calm,chart))
        assertTrue(AdaptiveWidgetTimeline.compact(calm.copy(status = "Відключення завтра о 16:00"),chart))
        assertTrue(AdaptiveWidgetTimeline.compact(calm.copy(status = "Можливе о 19:00"),chart))
        assertTrue(AdaptiveWidgetTimeline.compact(calm.copy(status = "Дані застаріли"),chart))
        assertTrue(AdaptiveWidgetTimeline.compact(calm.copy(spans = emptyList()),chart))
        assertTrue(AdaptiveWidgetTimeline.compact(row("Дім",PowerState.UNKNOWN),chart))
        assertTrue(AdaptiveWidgetTimeline.compact(calm.copy(spans = listOf(ChartSpan(now,window.end,PowerState.NO_SCHEDULED_OUTAGE))),chart))
        val unresolved = calm.copy(current = PowerState.UNKNOWN,status = "Відключення · час уточнюється")
        assertTrue(AdaptiveWidgetTimeline.compact(unresolved,chart))
        assertTrue(WidgetVisibility.relevant(unresolved,chart))
        for (power in listOf(PowerState.POSSIBLE_OUTAGE,PowerState.CONFIRMED_OUTAGE,PowerState.EMERGENCY_OUTAGE)) {
            assertFalse(AdaptiveWidgetTimeline.compact(row("Дім",power),chart))
            assertTrue(AdaptiveWidgetTimeline.compact(calm.copy(spans = listOf(
                ChartSpan(window.start,now,power),ChartSpan(now,window.end,PowerState.NO_SCHEDULED_OUTAGE))),chart))
        }
    }

    @Test fun stableOrderFitsHostsWithoutPromotingUnknownDataToAnEvent() {
        val chart = chart(row("Дім"),row("Робота",PowerState.POSSIBLE_OUTAGE,"Можливе о 22:00"),row("Батьки"),row("Гараж",PowerState.UNKNOWN,"Невідомий стан"))
        val config = WidgetConfig(1,chart.rows.map { it.address.id })
        val palette = WidgetPalette.resolve(context,config)
        assertEquals(listOf("Дім","Робота","Батьки","Гараж"),AdaptiveWidgetTimeline.ordered(chart).rows.map { it.address.title })
        for (width in listOf(160f,336f)) for (scale in listOf(0.8f,1f,2.1f)) for (density in 0..2) {
            val fitted = AdaptiveWidgetTimeline.fit(chart,width,280f,scale,density,false)
            val image = AdaptiveWidgetTimeline.draw(fitted,width,scale,density,false,palette,false)
            if (fitted.rows.size > 1) assertTrue(image.height/2f <= 281f)
            assertEquals(1,fitted.rows.size+fitted.hidden)
            if (fitted.rows.isNotEmpty()) assertEquals("Робота",fitted.rows.first().address.title)
        }
        val calm = chart(row("Дім"),row("Батьки"),row("Робота"))
        assertEquals(0,AdaptiveWidgetTimeline.fit(calm,336f,140f,1f,1,false).rows.size)
        val compact = AdaptiveWidgetTimeline.draw(calm,336f,1f,1,false,palette,false)
        val classic = AdaptiveWidgetTimeline.draw(calm,336f,1f,1,false,palette,true)
        assertTrue(compact.height < classic.height)
        assertEquals(0,Color.alpha(compact.getPixel(0,0)))
    }

    @Test fun unknownTomorrowAndPartialOrStaleDataBecomeCompactInformation() {
        val address = row("Дім").address
        val day = now.atZone(KYIV).toLocalDate()
        val today = ScheduleDay("",day.toString(),listOf(Slot(0,1440,PowerState.NO_SCHEDULED_OUTAGE)))
        fun create(days: List<ScheduleDay>, fetched: Instant = now): NotificationChart = NotificationChart.create(
            AppState(addresses = listOf(address),schedules = listOf(CachedSchedule(address.id,Schedule(fetched.toEpochMilli(),days,emptyList())))),now)
        val missing = create(listOf(today))
        val midnight = day.plusDays(1).atStartOfDay(KYIV).toInstant()
        assertEquals(midnight,missing.rows.single().unpublishedFrom)
        assertTrue(AdaptiveWidgetTimeline.compact(missing.rows.single(),missing))
        val explicitUnknown = create(listOf(today,today.copy(date = day.plusDays(1).toString(),slots = listOf(Slot(0,1440,PowerState.UNKNOWN)))))
        assertNull(explicitUnknown.rows.single().unpublishedFrom)
        assertTrue(AdaptiveWidgetTimeline.compact(explicitUnknown.rows.single(),explicitUnknown))
        val stale = create(listOf(today),now.minusSeconds(7200))
        assertNull(stale.rows.single().unpublishedFrom)
        assertTrue(AdaptiveWidgetTimeline.compact(stale.rows.single(),stale))
        val gap = create(listOf(today.copy(slots = listOf(Slot(0,1320,PowerState.NO_SCHEDULED_OUTAGE)))))
        assertTrue(AdaptiveWidgetTimeline.compact(gap.rows.single(),gap))
    }

    @Test fun nativeFadeRunsOnModeChangeAndDoesNotRestartOnClockUpdates() {
        val state = AppState(addresses = listOf(row("Дім").address),schedules = listOf(CachedSchedule("Дім",
            Schedule(now.toEpochMilli(),emptyList(),emptyList(),CurrentOutage("Аварійне",true,
                now.minusSeconds(300).toEpochMilli(),now.plusSeconds(7200).toEpochMilli(),"","")))))
        val config = WidgetConfig(1,listOf("Дім"),alwaysShowGraphs = true)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val parent = android.widget.FrameLayout(context)
            val root = SvitloWidgets.views(context,state,config,1,360,240,now).apply(context,parent)
            val flipper = root.findViewById<android.widget.ViewFlipper>(R.id.widget_transition)
            assertFalse(flipper.isFlipping)
            assertEquals(0,flipper.displayedChild)
            SvitloWidgets.views(context,state,config,1,360,240,now,1,true).reapply(context,root)
            assertEquals(1,flipper.displayedChild)
            assertEquals(240L,flipper.inAnimation.duration)
            assertNotNull(flipper.currentView.animation)
            flipper.getChildAt(0).clearAnimation(); flipper.getChildAt(1).clearAnimation()
            SvitloWidgets.views(context,state,config,1,360,240,now.plusSeconds(60),1,false).reapply(context,root)
            assertEquals(1,flipper.displayedChild)
            assertNull(flipper.currentView.animation)
            val fresh = SvitloWidgets.views(context,state,config,1,360,240,now,1,false).apply(context,parent)
            assertNotNull(fresh.findViewById<android.widget.ImageView>(R.id.widget_chart).drawable)
        }
    }

    @Test fun ribbonAndEventsKeepSavedOrderIncludingOverflow() {
        val rows = listOf(row("Вінниця",PowerState.POSSIBLE_OUTAGE),row("ВНТУ",PowerState.CONFIRMED_OUTAGE),
            row("Дача",PowerState.EMERGENCY_OUTAGE),row("Батьки"),row("Гараж"),row("Офіс"),row("Дім"),row("Склад",PowerState.POSSIBLE_OUTAGE))
        val all = chart(*rows.toTypedArray())
        val fitted = AdaptiveWidgetTimeline.fit(all,336f,600f,1f,1,false)
        assertEquals(listOf("Вінниця","ВНТУ","Дача","Склад"),fitted.rows.map { it.address.title })
        val ribbon = AdaptiveWidgetTimeline.ribbon(all,288f,1f)
        assertTrue(ribbon.hidden > 0)
        assertEquals(rows.filter { it in ribbon.rows },ribbon.rows)
        val late = chart(row("Дім"),row("Батьки"),row("Гараж"),row("Склад",PowerState.EMERGENCY_OUTAGE))
        assertTrue(AdaptiveWidgetTimeline.ribbon(late,160f,1f).rows.any { it.address.title == "Склад" })
        for (width in listOf(136f,220f,336f)) for (scale in listOf(.8f,1f,2.1f)) for (height in listOf(36f,100f,280f,600f)) {
            val fit = AdaptiveWidgetTimeline.fit(all,width,height,scale,1,false)
            val image = AdaptiveWidgetTimeline.draw(fit,width,scale,1,false,WidgetPalette.resolve(context,WidgetConfig(1,emptyList())),false)
            assertTrue("$width/$height/$scale: ${image.height/2f}",image.height/2f <= height+1f)
            assertEquals(4,fit.rows.size+fit.hidden)
        }
    }

    @Test fun renderNativeWidgetForReview() {
        val addresses = listOf("Вінниця","ВНТУ","Дача").map { row(it).address }
        val day = now.atZone(KYIV).toLocalDate()
        val schedules = addresses.map { address -> CachedSchedule(address.id,Schedule(now.toEpochMilli(),
            listOf(ScheduleDay("",day.toString(),listOf(Slot(0,1320,PowerState.NO_SCHEDULED_OUTAGE),
                Slot(1320,1440,PowerState.CONFIRMED_OUTAGE)))),emptyList())) }
        val state = AppState(addresses = addresses,schedules = schedules)
        val instrument = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        for (mono in listOf(false,true)) for (calm in listOf(false,true)) instrument.runOnMainSync {
            val config = WidgetConfig(1,addresses.map { it.id },monochrome = mono)
            val calmState = state.copy(schedules = schedules.map { cached -> cached.copy(schedule = cached.schedule.copy(
                days = (0L..1L).map { offset -> ScheduleDay("",day.plusDays(offset).toString(),
                    listOf(Slot(0,1440,PowerState.NO_SCHEDULED_OUTAGE))) })) })
            val root = SvitloWidgets.views(context,if (calm) calmState else state,config,1,360,390,now)
                .apply(context,android.widget.FrameLayout(context))
            val d = context.resources.displayMetrics.density
            val w = (360*d).toInt(); val h = (390*d).toInt()
            root.measure(android.view.View.MeasureSpec.makeMeasureSpec(w,android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(h,android.view.View.MeasureSpec.EXACTLY))
            root.layout(0,0,w,h)
            val panel = root.findViewById<android.view.View>(R.id.widget_root)
            assertTrue(panel.height <= h)
            if (calm) assertEquals(android.view.View.GONE,panel.visibility)
            else {
                assertTrue("Event card must include its key time",panel.height/d > 140f)
                assertEquals("22:00",root.findViewById<android.widget.TextView>(R.id.focus_time).text.toString())
                assertEquals("Відключення за графіком",root.findViewById<android.widget.TextView>(R.id.focus_label).text.toString())
            }
            val image = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
            root.draw(Canvas(image))
            val file = File(context.getExternalFilesDir(null),"widget-native-$mono-$calm.png")
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            instrument.uiAutomation.executeShellCommand("cp $file /sdcard/Download/${file.name}").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
        }
    }

    @Test fun renderScenariosForVisualReview() {
        val calm = row("Дім")
        val outage = row("Робота",PowerState.NO_SCHEDULED_OUTAGE,"Відключення о 22:00").copy(spans = listOf(
            ChartSpan(window.start,now.plusSeconds(3600),PowerState.NO_SCHEDULED_OUTAGE),
            ChartSpan(now.plusSeconds(3600),now.plusSeconds(3*3600),PowerState.CONFIRMED_OUTAGE),
            ChartSpan(now.plusSeconds(3*3600),window.end,PowerState.NO_SCHEDULED_OUTAGE)))
        val emergency = row("Батьки",PowerState.EMERGENCY_OUTAGE,"Аварійне · ще ≈ 1 д 8 год").copy(emergency = true,continues = true)
        val midnight = now.atZone(KYIV).toLocalDate().plusDays(1).atStartOfDay(KYIV).toInstant()
        val partial = calm.copy(unpublishedFrom = midnight,spans = listOf(ChartSpan(window.start,midnight,PowerState.NO_SCHEDULED_OUTAGE),
            ChartSpan(midnight,window.end,PowerState.UNKNOWN)))
        val cases = listOf("unpublished" to chart(partial,row("Батьки").copy(unpublishedFrom = midnight,spans = partial.spans)),
            "calm" to chart(calm,row("Батьки"),row("Робота")),
            "one" to chart(calm,outage,row("Батьки")), "several" to chart(calm,outage,emergency),
            "unknown" to chart(calm,row("Робота",PowerState.UNKNOWN,"Дані застаріли"),row("Батьки")))
        val directory = File(context.getExternalFilesDir(null),"verification").apply { mkdirs() }
        for ((name,original) in cases) for (mode in listOf("light","dark","mono","transparent","narrow")) {
            val config = WidgetConfig(1,original.rows.map { it.address.id },monochrome = mode == "mono",
                transparentBackground = mode == "transparent",transparentText = if (mode == "dark") "light" else "dark")
            val resolved = WidgetPalette.resolve(context,config)
            val colors = if (mode == "dark") WidgetColors(Color.rgb(26,23,19),Color.rgb(240,242,245),Color.LTGRAY,Color.rgb(229,184,112),true) else resolved
            val width = if (mode == "narrow") 180f else 336f
            val chart = AdaptiveWidgetTimeline.ordered(original)
            val image = AdaptiveWidgetTimeline.draw(chart,width,1f,1,mode == "mono",colors,false)
            val preview = Bitmap.createBitmap(image.width+48,image.height+48,Bitmap.Config.ARGB_8888)
            Canvas(preview).apply {
                drawColor(if (mode == "transparent") Color.rgb(195,200,207) else colors.background)
                drawBitmap(image,24f,24f,null)
            }
            File(directory,"widget-tva-$name-$mode.png").outputStream().use { preview.compress(Bitmap.CompressFormat.PNG,100,it) }
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("cp $directory/widget-tva-$name-$mode.png /sdcard/Download/widget-tva-$name-$mode.png").use {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
                }
            if (mode == "mono") for (y in 0 until image.height) for (x in 0 until image.width) {
                val p = image.getPixel(x,y)
                if (Color.alpha(p)>0) { assertEquals(Color.red(p),Color.green(p)); assertEquals(Color.red(p),Color.blue(p)) }
            }
        }
    }
}
