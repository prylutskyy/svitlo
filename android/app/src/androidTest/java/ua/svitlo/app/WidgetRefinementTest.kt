package ua.svitlo.app

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ua.svitlo.app.domain.*
import ua.svitlo.app.widget.*
import java.io.File
import java.time.Instant

/** Offline fixtures, real Canvas/RemoteViews and activity navigation; never contacts VOE. */
@RunWith(AndroidJUnit4::class)
class WidgetRefinementTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val now = Instant.parse("2026-10-22T18:53:00Z")
    private fun fixture(time: Instant = now): AppState {
        val addresses = listOf("Дім","Робота","Батьки","Дача").mapIndexed { i,title ->
            SavedAddress("refinement-$i",title,AddressItem("1","Вінниця"),AddressItem("2","Вулиця"),AddressItem("3","15")) }
        val day = time.atZone(KYIV).toLocalDate()
        val days = (0L..1L).map { offset -> ScheduleDay("",day.plusDays(offset).toString(),
            listOf(Slot(0,1440,PowerState.NO_SCHEDULED_OUTAGE))) }
        return AppState(addresses = addresses,schedules = addresses.take(3).mapIndexed { i,address ->
            CachedSchedule(address.id,Schedule(time.toEpochMilli(),if (i == 1) days.mapIndexed { index,d ->
                d.copy(slots = if (index == 0) listOf(Slot(0,1320,PowerState.NO_SCHEDULED_OUTAGE),Slot(1320,1440,PowerState.CONFIRMED_OUTAGE))
                    else listOf(Slot(0,30,PowerState.CONFIRMED_OUTAGE),Slot(30,240,PowerState.NO_SCHEDULED_OUTAGE),
                        Slot(240,300,PowerState.POSSIBLE_OUTAGE),Slot(300,1440,PowerState.NO_SCHEDULED_OUTAGE)))
            } else days,emptyList(),if (i == 2) CurrentOutage("Аварійне",true,time.minusSeconds(3600).toEpochMilli(),
                time.plusSeconds(30*3600).toEpochMilli(),"","") else null)) })
    }

    @Test fun labelsNeverCollideAndTimeScaleDoesNotChangeAcrossMidnightOrDst() {
        for (time in listOf(now,Instant.parse("2026-10-22T20:53:00Z"),Instant.parse("2026-10-22T21:07:00Z"),
            Instant.parse("2026-10-25T00:30:00Z"))) {
            val chart = WidgetConfig(1,fixture(time).addresses.map { it.id }).chart(fixture(time),time)
            for (width in listOf(136f,180f,336f,576f)) for (scale in listOf(.8f,1f,1.5f,2.1f)) {
                val labels = AdaptiveWidgetTimeline.axisLabels(chart,width,scale)
                assertEquals(time.atZone(KYIV).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),labels.first().value)
                labels.forEach { assertTrue(it.left >= 0 && it.right <= width+.01f) }
                for (line in listOf(false,true)) labels.filter { it.secondLine == line }.sortedBy { it.left }.zipWithNext().forEach { (a,b) ->
                    assertTrue("$time/$width/$scale: $a overlaps $b",a.right+3f*scale <= b.left+.01f)
                }
                val axis = AdaptiveWidgetTimeline.Axis(chart,width)
                val step = (axis.right-axis.left)/12f
                assertEquals(step,axis.x(time.plusSeconds(3600))-axis.x(time),.001f)
                assertEquals(step,axis.x(time)-axis.x(chart.start),.001f)
            }
        }
    }

    @Test fun continuationIsVisibleInsideBitmapAndUsesTheEdgeEventColour() {
        val state = fixture()
        val original = WidgetConfig(1,state.addresses.map { it.id }).chart(state,now)
        val emergency = original.rows.first { it.emergency }.copy(current = PowerState.NO_SCHEDULED_OUTAGE,emergency = false)
        val chart = original.copy(rows = listOf(emergency),total = 1)
        for (width in listOf(136f,180f,336f,576f)) for (scale in listOf(.8f,1f,2.1f)) for (mono in listOf(false,true)) {
            val colors = WidgetPalette.resolve(context,WidgetConfig(1,emptyList(),monochrome = mono))
            val image = AdaptiveWidgetTimeline.draw(chart,width,scale,1,mono,colors,true)
            val noArrow = AdaptiveWidgetTimeline.draw(chart.copy(rows = listOf(emergency.copy(continues = false))),width,scale,1,mono,colors,true)
            val edge = AdaptiveWidgetTimeline.Axis(chart,width).right
            val layout = AdaptiveWidgetTimeline.rowLayouts(chart,width,scale,1,true).single()
            val cy = ((44f*scale+layout.graphOffset!!)*2).toInt()
            var changed = 0
            for (y in cy-(8*scale).toInt()..cy+(8*scale).toInt()) for (x in ((edge+1)*2).toInt() until image.width) {
                if (image.getPixel(x,y) != noArrow.getPixel(x,y)) changed++
                assertEquals("Arrow clipped at $width/$scale",0,Color.alpha(image.getPixel(image.width-1,y)))
            }
            assertTrue("Missing arrow at $width/$scale/$mono",changed > 0)
            val unknown = emergency.copy(spans = listOf(ChartSpan(chart.start,chart.end,PowerState.UNKNOWN)))
            val a = AdaptiveWidgetTimeline.draw(chart.copy(rows = listOf(unknown)),width,scale,1,mono,colors,true)
            val b = AdaptiveWidgetTimeline.draw(chart.copy(rows = listOf(unknown.copy(continues = false))),width,scale,1,mono,colors,true)
            assertTrue("Unknown data cannot imply an outage continuation",a.sameAs(b))
        }
    }

    @Test fun missingAndStaleStatesDoNotInventAReasonToAppear() {
        val state = fixture()
        val chart = WidgetConfig(1,state.addresses.map { it.id }).chart(state,now)
        val unknown = chart.rows.last()
        val tomorrow = now.atZone(KYIV).toLocalDate().plusDays(1).atStartOfDay(KYIV).toInstant()
        val partial = chart.rows.first().copy(unpublishedFrom = tomorrow)
        val stale = partial.copy(address = partial.address.copy(id = "stale"),unpublishedFrom = null,status = "Дані застаріли",current = PowerState.UNKNOWN)
        val info = chart.copy(rows = listOf(unknown,partial,stale),total = 3)
        val fit = AdaptiveWidgetTimeline.fit(info,336f,300f,1f,1,false)
        assertTrue(fit.rows.isEmpty())
        assertEquals(0,fit.hidden)
        val palette = WidgetPalette.resolve(context,WidgetConfig(1,emptyList()))
        val image = AdaptiveWidgetTimeline.draw(fit,336f,1f,1,false,palette,false)
        assertEquals(2,image.height)
        val graphs = AdaptiveWidgetTimeline.draw(info,336f,1f,1,false,palette,true)
        assertTrue(graphs.height > image.height)
    }

    @Test fun realRemoteViewsFitAndRenderAcrossThemesSizesAndLargeText() {
        val state = fixture()
        val directory = File(context.getExternalFilesDir(null),"widget-refinement").apply { mkdirs() }
        shell("mkdir -p /sdcard/Download/widget-refinement")
        instrument.runOnMainSync {
            for (mode in listOf("light","dark","mono","transparent","narrow","large","wide","hidden-header")) {
                val dark = mode in listOf("dark","mono","transparent")
                val configContext = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    fontScale = if (mode == "large") 1.5f else 1f
                })
                val width = when (mode) { "narrow" -> 180; "wide" -> 600; else -> 360 }
                val height = if (mode == "large") 600 else 370
                val config = WidgetConfig(99,state.addresses.map { it.id },monochrome = mode == "mono",
                    transparentBackground = mode == "transparent",transparentText = "light",
                    contentScale = if (mode == "large") 1.4f else 1f,hideTitle = mode == "hidden-header",hideSettings = mode == "hidden-header",alwaysShowGraphs = true)
                val root = SvitloWidgets.views(configContext,state,config,99,width,height,now).apply(configContext,FrameLayout(configContext))
                val d = configContext.resources.displayMetrics.density
                root.measure(View.MeasureSpec.makeMeasureSpec((width*d).toInt(),View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((height*d).toInt(),View.MeasureSpec.EXACTLY))
                root.layout(0,0,root.measuredWidth,root.measuredHeight)
                val image = root.findViewById<ImageView>(R.id.widget_chart)
                val bitmap = (image.drawable as BitmapDrawable).bitmap
                assertEquals((width-24).toFloat(),bitmap.width/2f,.01f)
                // 12dp padding rounds to pixels independently on either side at density 2.625.
                // Check the true aspect ratio, allowing only pixel rounding rather than text shrinkage.
                assertEquals("$mode aspect ratio",image.width.toFloat()*bitmap.height/bitmap.width,image.height.toFloat(),1.5f)
                assertTrue("$mode bitmap=${bitmap.width}x${bitmap.height}, image=${image.width}x${image.height}, density=$d",bitmap.height/2f <= image.height/d+2f)
                assertTrue("$mode panel exceeds host",root.findViewById<View>(R.id.widget_root).bottom <= root.height)
                assertTrue(image.contentDescription.contains("Дача")) // explicit comparison includes every selected address
                if (mode == "mono") for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(x,y)
                    if (Color.alpha(pixel)>0) { assertEquals(Color.red(pixel),Color.green(pixel)); assertEquals(Color.green(pixel),Color.blue(pixel)) }
                }
                val capture = Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
                val canvas = Canvas(capture)
                if (mode == "transparent") canvas.drawColor(Color.rgb(38,50,66))
                root.draw(canvas)
                File(directory,"$mode.png").outputStream().use { capture.compress(Bitmap.CompressFormat.PNG,100,it) }
                shell("cp $directory/$mode.png /sdcard/Download/widget-refinement/$mode.png")
            }
        }
    }

    @Test fun calmWidgetIsInvisibleEvenWithHiddenHeaderControls() {
        val state = fixture().let { state -> state.copy(addresses = state.addresses.take(3),schedules = state.schedules.map { cached ->
            cached.copy(schedule = cached.schedule.copy(currentOutage = null,days = cached.schedule.days.map { day ->
                day.copy(slots = listOf(Slot(0,1440,PowerState.NO_SCHEDULED_OUTAGE))) }))
        }) }
        instrument.runOnMainSync {
            val config = WidgetConfig(99,state.addresses.map { it.id },hideTitle = true,hideSettings = true)
            val root = SvitloWidgets.views(context,state,config,99,360,240,now).apply(context,FrameLayout(context))
            val d = context.resources.displayMetrics.density
            root.measure(View.MeasureSpec.makeMeasureSpec((360*d).toInt(),View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((240*d).toInt(),View.MeasureSpec.EXACTLY))
            root.layout(0,0,root.measuredWidth,root.measuredHeight)
            val image = root.findViewById<ImageView>(R.id.widget_chart)
            assertEquals(View.GONE,root.findViewById<View>(R.id.widget_root).visibility)
            val capture = Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
            root.draw(Canvas(capture))
            for (y in 0 until capture.height) for (x in 0 until capture.width) assertEquals(0,Color.alpha(capture.getPixel(x,y)))
        }
    }

    private fun shell(command: String) = instrument.uiAutomation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        instrument.uiAutomation.clearCache()
        fun collect(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node)+(0 until node.childCount).flatMap { node.getChild(it)?.let(::collect).orEmpty() }
        return instrument.uiAutomation.rootInActiveWindow?.let(::collect).orEmpty()
    }
    private fun awaitNode(text: String): AccessibilityNodeInfo {
        repeat(40) {
            nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }?.let { return it }
            Thread.sleep(150)
        }
        error("Missing $text: ${nodes().map { it.text to it.contentDescription }}")
    }
    @Test fun widgetAddressOpensDetailsAndBackReturnsToItsOwnList(): Unit = runBlocking {
        val original = context.app.store.load()
        val time = Instant.now()
        val fixture = fixture(time)
        try {
            context.app.store.update { fixture.copy(widgets = listOf(WidgetConfig(991,fixture.addresses.map { it.id }))) }
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            ActivityScenario.launch<WidgetAddressesActivity>(Intent(context,WidgetAddressesActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,991)).use {
                val node = awaitNode("Деталі графіка Робота")
                var clickable: AccessibilityNodeInfo? = node
                while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                assertTrue(requireNotNull(clickable).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                awaitNode("Джерело та оновлення")
                assertTrue(nodes().any { it.text?.toString() == "Сьогодні" })
                val file = File(context.getExternalFilesDir(null),"widget-refinement/details.png")
                file.parentFile!!.mkdirs()
                instrument.waitForIdleSync()
                file.outputStream().use { requireNotNull(instrument.uiAutomation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG,100,it) }
                shell("mkdir -p /sdcard/Download/widget-refinement")
                shell("cp $file /sdcard/Download/widget-refinement/details.png")
                shell("input keyevent KEYCODE_BACK")
                awaitNode("Адреси віджета")
                assertNotNull(awaitNode("Деталі графіка Робота"))
            }
            // The contextual widget headline can route straight to its relevant address.
            ActivityScenario.launch<WidgetAddressesActivity>(Intent(context,WidgetAddressesActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,991)
                .putExtra(WidgetAddressesActivity.EXTRA_ADDRESS_ID,fixture.addresses[1].id)).use {
                awaitNode("Джерело та оновлення")
                assertTrue(nodes().any { it.text?.toString() == "Робота" })
                shell("input keyevent KEYCODE_BACK")
                awaitNode("Адреси віджета")
            }
        } finally { context.app.store.update { original } }
    }
}
