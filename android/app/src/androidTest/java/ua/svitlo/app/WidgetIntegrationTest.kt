package ua.svitlo.app

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ua.svitlo.app.background.NotificationTimeline
import ua.svitlo.app.domain.*
import ua.svitlo.app.widget.*
import java.time.Instant

/** Offline, synthetic schedules. Uses a real bound AppWidgetHost, not Samsung's AOD host. */
@RunWith(AndroidJUnit4::class)
class WidgetIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui = instrumentation.uiAutomation
    private fun shell(command: String) = ui.executeShellCommand(command).use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    private fun nodes(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 33) ui.clearCache()
        fun collect(n: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(n) + (0 until n.childCount).flatMap { n.getChild(it)?.let(::collect).orEmpty() }
        return ui.rootInActiveWindow?.let(::collect).orEmpty()
    }
    private fun click(text: String) {
        repeat(30) {
            var node = nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
            if (node != null) {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                instrumentation.waitForIdleSync(); Thread.sleep(200)
                node = nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
                var attempts = 0
                while (node != null && attempts++ < 8) {
                    if (node.isClickable && node.isEnabled) {
                        // Preview height can change when a setting is toggled; semantic activation
                        // targets the control rather than coordinates that move during recomposition.
                        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            instrumentation.waitForIdleSync(); Thread.sleep(400)
                            return
                        }
                        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                        Thread.sleep(400)
                        return
                    }
                    node = node.parent
                }
            }
            nodes().firstOrNull { it.isScrollable && it.rangeInfo == null }?.let { scroll ->
                val bounds = android.graphics.Rect().also(scroll::getBoundsInScreen)
                val x = bounds.centerX()
                val from = bounds.top + bounds.height() * 3 / 4
                val to = bounds.top + bounds.height() / 2
                shell("input swipe $x $from $x $to 250")
            }
            Thread.sleep(200)
        }
        error("Missing clickable text $text; ${nodes().map { it.text to it.contentDescription }}")
    }
    @Test fun widgetRowsFitSmallHostsAndNeverStretch() {
        val now = Instant.now()
        val state = fixture(now)
        instrumentation.runOnMainSync {
            for (density in 0..2) for (header in listOf(false, true)) for (height in listOf(160, 220, 320, 460)) {
                val config = WidgetConfig(99, state.addresses.map { it.id }, hideTitle = !header,
                    hideSettings = !header, verticalDensity = density, alwaysShowGraphs = true)
                val dp = context.resources.displayMetrics.density
                val remote = SvitloWidgets.views(context, state, config, 99, 320, height, now)
                val root = remote.apply(context, FrameLayout(context))
                root.measure(android.view.View.MeasureSpec.makeMeasureSpec((320 * dp).toInt(), android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec((height * dp).toInt(), android.view.View.MeasureSpec.EXACTLY))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                val image = root.findViewById<android.widget.ImageView>(R.id.widget_chart)
                val bitmap = (image.drawable as android.graphics.drawable.BitmapDrawable).bitmap
                val scale = config.effectiveFontScale(context.resources.configuration.fontScale)
                val chart = NotificationTimeline.fitWidget(config.chart(state,now),296f,
                    (height-24-if (header) 40 else 0).toFloat(),scale,density)
                val count = chart.rows.size
                assertTrue(image.height > 0)
                // Measured content fits without scaling text down or stretching it to fill the host.
                assertTrue("height=$height density=$density header=$header",bitmap.height/2f <= image.height/dp+1f)
                assertTrue("Widget must keep usable text width",bitmap.width/2f >= 295f)
                if (density == 0 && !header && height in listOf(160, 220)) {
                    val capture = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
                    root.draw(android.graphics.Canvas(capture))
                    val directory = java.io.File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
                    java.io.File(directory, "widget-compact-$height.png").outputStream().use { capture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
                assertEquals(state.addresses.take(count).map { it.id }, config.chart(state, now, count).rows.map { it.address.id })
            }
        }
        for (height in listOf(160, 220)) shell("cp ${context.getExternalFilesDir(null)}/verification/widget-compact-$height.png /sdcard/Download/svitlo-widget-compact-$height.png")
    }

    @Test fun appearanceKeepsTransparentPixelsAndSemanticColours() {
        val now = Instant.now()
        val fixture = fixture(now)
        instrumentation.runOnMainSync {
            for (mono in listOf(false, true)) for (transparent in listOf(false, true)) for (tone in listOf("light", "dark")) {
                val base = WidgetConfig(99, fixture.addresses.map { it.id }, monochrome = mono, hideTitle = true,
                    hideSettings = true, transparentBackground = transparent, transparentText = tone)
                val referenceColors = WidgetPalette.resolve(context, base)
                val chart = base.chart(fixture, now)
                val reference = NotificationTimeline.draw(chart, referenceColors.dark, widget = true, monochrome = mono, widgetColors = referenceColors)
                for (source in listOf(null, "custom", "wallpaper1", "wallpaper2", "wallpaper3")) {
                    val config = base.copy(accentSource = source, accentColor = 0xFF7E4DD2.toInt())
                    val colors = WidgetPalette.resolve(context, config)
                    if (source?.startsWith("wallpaper") == true && android.os.Build.VERSION.SDK_INT >= 31 && !mono)
                        assertNotNull(colors.accent)
                    if (mono) assertNull(colors.accent)
                    val bitmap = NotificationTimeline.draw(chart, colors.dark, widget = true, monochrome = mono, widgetColors = colors)
                    // The emergency's red/white core cannot become the chosen decorative accent.
                    assertEquals(reference.getPixel(320, 144), bitmap.getPixel(320, 144))
                    assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
                    if (mono) for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                        val pixel = bitmap.getPixel(x, y)
                        if (Color.alpha(pixel) > 0) {
                            assertEquals(Color.red(pixel), Color.green(pixel)); assertEquals(Color.green(pixel), Color.blue(pixel))
                        }
                    }
                    val root = SvitloWidgets.views(context, fixture, config, 99, 320, 280, now).apply(context, FrameLayout(context))
                    val dp = context.resources.displayMetrics.density
                    root.measure(android.view.View.MeasureSpec.makeMeasureSpec((320 * dp).toInt(), android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec((280 * dp).toInt(), android.view.View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                    val capture = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
                    root.draw(android.graphics.Canvas(capture))
                    val panel = root.findViewById<android.view.View>(R.id.widget_root)
                    assertEquals(if (transparent) 0 else 255, Color.alpha(capture.getPixel(root.width / 2, panel.bottom - (6 * dp).toInt())))
                    if (source == "custom" && tone == "light") {
                        val directory = java.io.File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
                        java.io.File(directory, "widget-${if (mono) "mono" else "accent"}-${if (transparent) "transparent" else "opaque"}.png")
                            .outputStream().use { capture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
            }
        }
        for (mode in listOf("mono", "accent")) for (backdrop in listOf("transparent", "opaque")) {
            val name = "widget-$mode-$backdrop.png"
            shell("cp ${context.getExternalFilesDir(null)}/verification/$name /sdcard/Download/svitlo-$name")
        }
    }

    private fun fixture(now: Instant): AppState {
        val addresses = listOf("Дім", "Батьки", "Робота").mapIndexed { i, title ->
            SavedAddress("widget-$i", title, AddressItem("1", "Вінниця"), AddressItem("2", "Гайдамацька"), AddressItem("3", "${i+1}"), selected = i == 1)
        }
        val date = now.atZone(KYIV).toLocalDate()
        val hour = now.atZone(KYIV).hour
        val days = (0L..1L).map { offset -> ScheduleDay("Графік", date.plusDays(offset).toString(), (0..47).map { i ->
            Slot(i*30, i*30+30, when ((i-hour*2).mod(8)) {
                1,2 -> PowerState.CONFIRMED_OUTAGE
                3,4 -> PowerState.POSSIBLE_OUTAGE
                7 -> PowerState.UNKNOWN
                else -> PowerState.NO_SCHEDULED_OUTAGE
            })
        }) }
        return AppState(addresses = addresses, notificationsEnabled = false, schedules = addresses.mapIndexed { i, address ->
            CachedSchedule(address.id, Schedule(now.toEpochMilli(), days, emptyList(), if (i == 0) CurrentOutage(
                "Аварійне відключення", true, now.minusSeconds(3600).toEpochMilli(), now.plusSeconds(30*3600).toEpochMilli(), "", "Синтетична аварія") else null))
        }, attempts = addresses.map { FetchAttempt(it.id, now.toEpochMilli(),0,null) })
    }

    @Test fun boundWidgetConfiguresIndependentlyAndRendersBothModes(): Unit = runBlocking {
        val original = context.app.store.load()
        val host = AppWidgetHost(context, 742)
        val manager = AppWidgetManager.getInstance(context)
        val first = host.allocateAppWidgetId()
        val second = host.allocateAppWidgetId()
        try {
            ui.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            try {
                listOf(first, second).forEach { assertTrue(manager.bindAppWidgetIdIfAllowed(it, ComponentName(context, SvitloWidgetProvider::class.java))) }
            } finally { ui.dropShellPermissionIdentity() }
            val now = Instant.now()
            val fixture = fixture(now)
            val config = WidgetConfig(first, fixture.addresses.map { it.id }, alwaysShowGraphs = true)
            val other = WidgetConfig(second, listOf(fixture.addresses.last().id), true)
            context.app.updateControl({ fixture.copy(widgets = listOf(config, other)) })
            val intent = Intent(context, WidgetConfigurationActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, first)
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            ActivityScenario.launch<WidgetConfigurationActivity>(intent).use {
                Thread.sleep(1500)
                click("Робота")
                click("Темна")
                click("Зберегти")
                Thread.sleep(700)
            }
            val saved = context.app.store.state.value
            assertEquals(WidgetConfig(first,listOf(fixture.addresses[2].id),surfaceStyle = "dark"),saved.widgets!!.first { it.widgetId == first })
            assertEquals(other, saved.widgets!!.first { it.widgetId == second })
            assertEquals(fixture.addresses, saved.addresses)
            assertEquals(saved.widgets, ua.svitlo.app.data.StateStore(context).load().widgets)
            ActivityScenario.launch<WidgetConfigurationActivity>(intent).use { activity ->
                Thread.sleep(500)
                click("Світла")
                activity.onActivity { it.finish() }
            }
            assertEquals(saved.widgets, context.app.store.state.value.widgets)

            val chart = config.chart(fixture, now)
            val mono = NotificationTimeline.draw(chart, true, 320f, 1f, monochrome = true, widget = true)
            val color = NotificationTimeline.draw(chart, false, 320f, 1f, widget = true)
            var colored = 0
            for (y in 0 until mono.height) for (x in 0 until mono.width) {
                val pixel = mono.getPixel(x,y)
                if (Color.alpha(pixel) > 0) { assertEquals(Color.red(pixel), Color.green(pixel)); assertEquals(Color.green(pixel),Color.blue(pixel)) }
                val c = color.getPixel(x,y)
                if (Color.alpha(c) > 0 && Color.red(c) > Color.blue(c)+30) colored++
            }
            assertTrue(colored > 0)

            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            val enlarged = config.copy(contentScale = 1.4f)
            val widgetHeight = WidgetConfig.recommendedHeight(3, enlarged.effectiveFontScale(context.resources.configuration.fontScale), headerVisible = false)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val hostView = host.createView(activity, first, manager.getAppWidgetInfo(first))
                    val root = FrameLayout(activity).apply { setBackgroundColor(Color.rgb(30,30,30)); setPadding(24,72,24,24) }
                    val dp = activity.resources.displayMetrics.density
                    root.addView(hostView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (widgetHeight*dp).toInt()))
                    activity.setContentView(root)
                    hostView.updateAppWidget(SvitloWidgets.views(context, fixture, enlarged.copy(hideTitle = true, hideSettings = true), first, (activity.resources.displayMetrics.widthPixels/dp-48/dp).toInt(), widgetHeight, now))
                    assertEquals(android.view.View.INVISIBLE, hostView.findViewById<android.view.View>(R.id.widget_title).visibility)
                    assertEquals(android.view.View.GONE, hostView.findViewById<android.view.View>(R.id.widget_configure).visibility)
                    assertEquals(android.view.View.GONE, hostView.findViewById<android.view.View>(R.id.widget_header).visibility)
                    assertEquals(android.view.View.GONE, hostView.findViewById<android.view.View>(R.id.widget_empty).visibility)

                }
                Thread.sleep(1800)
                shell("screencap -p /sdcard/Download/svitlo-widget-color.png")
                scenario.onActivity { activity ->
                    val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as FrameLayout
                    val view = root.getChildAt(0) as android.appwidget.AppWidgetHostView
                    val dp = activity.resources.displayMetrics.density
                    root.setBackgroundColor(Color.BLACK)
                    view.updateAppWidget(SvitloWidgets.views(context, fixture, enlarged.copy(monochrome = true, hideTitle = true, hideSettings = true), first,
                        (activity.resources.displayMetrics.widthPixels/dp-48/dp).toInt(), widgetHeight, now))
                }
                Thread.sleep(800)
                shell("screencap -p /sdcard/Download/svitlo-widget-monochrome.png")
            }
            host.deleteAppWidgetId(first)
            repeat(30) { if (context.app.store.state.value.widgets.orEmpty().any { it.widgetId == first }) Thread.sleep(100) }
            assertFalse(context.app.store.state.value.widgets.orEmpty().any { it.widgetId == first })
            assertTrue(context.app.store.state.value.widgets.orEmpty().any { it.widgetId == second })
        } finally {
            host.deleteHost()
            // Let deletion broadcasts complete before restoring the user's original state.
            Thread.sleep(500)
            context.app.updateControl({ original })
        }
    }
}
