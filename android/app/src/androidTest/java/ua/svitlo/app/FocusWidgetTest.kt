package ua.svitlo.app

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
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

@RunWith(AndroidJUnit4::class)
class FocusWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val now = Instant.parse("2026-10-22T09:00:00Z")
    private val addresses = listOf("Дім", "Робота", "Батьки").mapIndexed { i, name ->
        SavedAddress("focus-$i", name, AddressItem("1", "Вінниця"), AddressItem("2", "Вулиця"), AddressItem("3", "15")) }
    private val config = WidgetConfig(997, addresses.map { it.id },hideWhenQuiet = true)
    private fun state(mode: String = "active"): AppState {
        val date = now.atZone(KYIV).toLocalDate()
        return AppState(addresses = addresses, schedules = addresses.mapIndexed { i, address ->
            val days = (0L..1L).map { offset -> ScheduleDay("", date.plusDays(offset).toString(), when {
                i == 1 && mode != "quiet" && (offset == 0L || mode == "tomorrow") -> {
                    val start = if (mode == "tomorrow") 960 else 840
                    if (mode == "tomorrow" && offset == 0L) listOf(Slot(0, 1440, PowerState.NO_SCHEDULED_OUTAGE)) else
                        listOf(Slot(0, start, PowerState.NO_SCHEDULED_OUTAGE), Slot(start, start + 120, if (mode == "tomorrow") PowerState.POSSIBLE_OUTAGE else PowerState.CONFIRMED_OUTAGE), Slot(start + 120, 1440, PowerState.NO_SCHEDULED_OUTAGE))
                }
                else -> listOf(Slot(0, 1440, PowerState.NO_SCHEDULED_OUTAGE))
            }) }
            val incident = if (i == 2 && mode in listOf("active", "unresolved", "unknown-end"))
                CurrentOutage("Аварія", true, now.minusSeconds(3600).toEpochMilli(), if (mode == "unknown-end") null else now.plusSeconds(7200).toEpochMilli(), "", "") else null
            CachedSchedule(address.id, Schedule((if (mode == "unresolved" && i == 2) now.minusSeconds(7200) else now).toEpochMilli(), days, emptyList(), incident))
        })
    }
    private fun measure(root: View, themed: Context, width: Int, height: Int) {
        val d = themed.resources.displayMetrics.density
        root.measure(View.MeasureSpec.makeMeasureSpec((width * d).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((height * d).toInt(), View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }
    private fun capture(root: View) = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
    private fun shell(cmd: String) = instrument.uiAutomation.executeShellCommand(cmd).use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }

    @Test fun singleFocusKeepsAllOtherEventsDiscoverableAndReapplyDoesNotLeakOldState() {
        instrument.runOnMainSync {
            val root = SvitloWidgets.views(context, state(), config, 997, 360, 260, now).apply(context, FrameLayout(context))
            measure(root, context, 360, 260)
            assertEquals("Батьки", root.findViewById<TextView>(R.id.focus_address).text.toString())
            assertEquals("14:00", root.findViewById<TextView>(R.id.focus_time).text.toString())
            assertEquals("Ще 1 адреса з подіями  ›", root.findViewById<TextView>(R.id.focus_footer).text.toString())
            assertEquals(View.GONE, root.findViewById<View>(R.id.widget_transition).visibility)
            for (mode in listOf("quiet", "tomorrow", "unresolved", "unknown-end", "active")) {
                SvitloWidgets.views(context, state(mode), config, 997, 360, 260, now).reapply(context, root)
                measure(root, context, 360, 260)
                if (mode == "quiet") {
                    assertEquals(View.GONE, root.findViewById<View>(R.id.widget_root).visibility)
                    val image = capture(root)
                    for (y in 0 until image.height) for (x in 0 until image.width) assertEquals(0, Color.alpha(image.getPixel(x, y)))
                } else {
                    assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_root).visibility)
                    assertEquals(when (mode) { "tomorrow" -> "16:00"; "unresolved", "unknown-end" -> "—"; else -> "14:00" }, root.findViewById<TextView>(R.id.focus_time).text.toString())
                }
            }
            SvitloWidgets.views(context, state(), config.copy(alwaysShowGraphs = true), 997, 360, 400, now).reapply(context, root)
            assertEquals(View.GONE, root.findViewById<View>(R.id.widget_focus).visibility)
            SvitloWidgets.views(context, state(), config, 997, 360, 260, now).reapply(context, root)
            assertEquals(View.GONE, root.findViewById<View>(R.id.widget_transition).visibility)
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_focus).visibility)
        }
    }

    @Test fun nativeTextFitsCompactAndLargeHostsAndRendersReviewFixtures() {
        val directory = File(context.getExternalFilesDir(null), "focus-widget").apply { mkdirs() }
        shell("mkdir -p /sdcard/Download/focus-widget")
        instrument.runOnMainSync {
            for (mode in listOf("active", "future", "tomorrow", "unresolved", "unknown-end", "dark", "mono", "transparent", "narrow", "large", "tiny", "quiet")) {
                val themed = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (mode in listOf("dark", "mono", "transparent")) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    fontScale = if (mode == "large") 1.5f else 1f
                })
                val width = if (mode in listOf("narrow", "tiny")) 180 else 360
                val height = when (mode) { "tiny" -> 100; "large" -> 500; "narrow" -> 260; else -> 240 }
                val appearance = config.copy(monochrome = mode == "mono", transparentBackground = mode == "transparent", transparentText = "light", contentScale = if (mode == "large") 1.4f else 1f)
                val fixture = state(if (mode in listOf("future", "tomorrow", "unresolved", "unknown-end", "quiet")) mode else "active")
                val root = SvitloWidgets.views(themed, fixture, appearance, 997, width, height, now).apply(themed, FrameLayout(themed))
                measure(root, themed, width, height)
                val panel = root.findViewById<View>(R.id.widget_root)
                assertTrue("$mode exceeds host: ${panel.bottom} > ${root.height}", panel.bottom <= root.height)
                val time = root.findViewById<TextView>(R.id.focus_time)
                if (mode != "quiet") {
                    assertTrue("$mode time has ellipsis", (0 until time.layout.lineCount).none { time.layout.getEllipsisCount(it) > 0 })
                    assertTrue("$mode no readable event", root.findViewById<View>(R.id.focus_event).height > 0)
                    assertTrue(root.findViewById<View>(R.id.focus_event).contentDescription.contains("Відкрити деталі"))
                }
                val bitmap = capture(root)
                if (mode == "mono") for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(x,y)
                    if (Color.alpha(pixel) > 0) { assertEquals(Color.red(pixel), Color.green(pixel)); assertEquals(Color.green(pixel), Color.blue(pixel)) }
                }
                val output = if (mode == "transparent") Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
                    Canvas(it).apply { drawColor(Color.rgb(35, 46, 60)); drawBitmap(bitmap, 0f, 0f, null) }
                } else bitmap
                File(directory, "$mode.png").outputStream().use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
                shell("cp $directory/$mode.png /sdcard/Download/focus-widget/$mode.png")
            }
        }
    }

    @Test fun actualCardTapsOpenFocusedDetailsOrAllSelectedAddresses(): Unit = runBlocking {
        val original = context.app.store.load()
        try {
            context.app.store.update { state().copy(widgets = listOf(config)) }
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            for (target in listOf(R.id.focus_event, R.id.focus_footer, R.id.focus_address)) {
                val monitor = instrument.addMonitor(WidgetAddressesActivity::class.java.name, null, false)
                try {
                    instrument.runOnMainSync {
                        val root = SvitloWidgets.views(context, state(), config, 997, 360, 260, now).apply(context, FrameLayout(context))
                        assertTrue(root.findViewById<View>(target).performClick())
                    }
                    val activity = requireNotNull(instrument.waitForMonitorWithTimeout(monitor, 6000)) { "Tap did not launch details" }
                    assertEquals(997, activity.intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
                    assertEquals(if (target == R.id.focus_event) addresses[2].id else null, activity.intent.getStringExtra(WidgetAddressesActivity.EXTRA_ADDRESS_ID))
                    instrument.runOnMainSync { activity.finish() }
                    instrument.waitForIdleSync()
                } finally { instrument.removeMonitor(monitor) }
            }
        } finally { context.app.store.update { original } }
    }

    @Test fun largeFontsAndLowHostsDoNotClipChildrenOrThePrimaryTime() {
        instrument.runOnMainSync {
            val themed = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = 1.5f })
            fun verify(view: View, label: String) {
                if (view.visibility != View.VISIBLE) return
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i)
                    if (child.visibility == View.VISIBLE) {
                        assertTrue("$label child ${child.id} is clipped: ${child.bottom} > ${view.height - view.paddingBottom}", child.bottom <= view.height - view.paddingBottom + 2)
                        verify(child, label)
                    }
                }
            }
            for (width in listOf(160, 180, 320, 600)) for (height in listOf(100, 140, 180, 240, 500))
                for (mode in listOf("active", "tomorrow", "unresolved", "unknown-end")) {
                    val root = SvitloWidgets.views(themed, state(mode), config.copy(contentScale = 1.4f), 997, width, height, now)
                        .apply(themed, FrameLayout(themed))
                    measure(root, themed, width, height)
                    verify(root, "$width/$height/$mode")
                    val time = root.findViewById<TextView>(R.id.focus_time)
                    assertTrue("$width/$height/$mode primary time clipped", (0 until time.layout.lineCount).none { time.layout.getEllipsisCount(it) > 0 })
                }
        }
    }

    @Test fun currentEmergencyAndTomorrowRestorationHaveSeparateVisibleMeanings() {
        val time = Instant.parse("2026-10-22T18:53:00Z")
        val end = Instant.parse("2026-10-23T03:25:00Z")
        val fixture = AppState(addresses = addresses.take(1),schedules = listOf(CachedSchedule(addresses.first().id,
            Schedule(time.toEpochMilli(),emptyList(),emptyList(),CurrentOutage("Синтетична аварія",true,
                time.minusSeconds(5400).toEpochMilli(),end.toEpochMilli(),"","")))))
        val directory = java.io.File(context.getExternalFilesDir(null),"widget-current").apply { mkdirs() }
        shell("mkdir -p /sdcard/Download/widget-current")
        instrument.runOnMainSync {
            for (mode in listOf("compact","standard","detailed","tinted","dark","clear")) {
                val width = if (mode == "compact") 180 else 360
                val height = when (mode) { "compact" -> 180; "standard" -> 220; else -> 320 }
                val config = WidgetConfig(998,listOf(addresses.first().id),transparentBackground = mode == "clear",
                    transparentText = "light",surfaceStyle = mode.takeIf { it in listOf("tinted","dark") })
                val root = SvitloWidgets.views(context,fixture,config,998,width,height,time).apply(context,FrameLayout(context))
                measure(root,context,width,height)
                assertEquals("Аварійне зараз",root.findViewById<TextView>(R.id.focus_state).text.toString())
                assertEquals(View.VISIBLE,root.findViewById<View>(R.id.focus_state).visibility)
                assertEquals("Відновлення завтра, орієнтовно",root.findViewById<TextView>(R.id.focus_label).text.toString())
                if (mode != "compact") {
                    assertEquals("Почалося сьогодні о 20:23",root.findViewById<TextView>(R.id.focus_caption).text.toString())
                    assertEquals(View.VISIBLE,root.findViewById<View>(R.id.focus_state).visibility)
                    assertEquals("06:25",root.findViewById<TextView>(R.id.focus_time).text.toString())
                }
                val capture = capture(root)
                val bitmap = if (mode == "clear") Bitmap.createBitmap(capture.width,capture.height,Bitmap.Config.ARGB_8888).also {
                    Canvas(it).apply { drawColor(Color.rgb(35,46,60)); drawBitmap(capture,0f,0f,null) }
                } else capture
                java.io.File(directory,"$mode.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                shell("cp $directory/$mode.png /sdcard/Download/widget-current/$mode.png")
            }
        }
    }
}
