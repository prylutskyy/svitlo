package ua.svitlo.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ua.svitlo.app.background.NotificationTimeline
import ua.svitlo.app.background.TrackingNotifications
import ua.svitlo.app.data.VoeParser
import ua.svitlo.app.domain.*
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ExpressiveNotificationTest {
    @Test fun mixedStatesFitExpressiveCardsAndSystemThemes(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ui = instrumentation.uiAutomation
        fun shell(command: String) = ui.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8).trim()
        }
        if (Build.VERSION.SDK_INT >= 33) ui.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val previousNight = shell("cmd uimode night")
        val previousFont = shell("settings get system font_scale").takeUnless { it == "null" } ?: "1.0"
        fun expandChart() {
            val info = ui.serviceInfo
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            ui.serviceInfo = info
            fun descendants(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else
                listOf(node) + (0 until node.childCount).flatMap { descendants(node.getChild(it)) }
            Thread.sleep(200)
            val nodes = ui.windows.flatMap { descendants(it.root) }
            var row = nodes.firstOrNull { it.text?.toString()?.startsWith("Дім · Батьки · Робота") == true }
            while (row != null && row.viewIdResourceName != "com.android.systemui:id/expandableNotificationRow") row = row.parent
            val button = descendants(row).firstOrNull { it.viewIdResourceName == "android:id/expand_button" }
                ?: error("Graph expansion control missing: ${nodes.map { it.viewIdResourceName to it.text }}")
            if (button.contentDescription.toString().contains("expand", ignoreCase = true))
                assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            Thread.sleep(800)
            assertTrue(ui.windows.flatMap { descendants(it.root) }.any { it.viewIdResourceName == "ua.svitlo.app:id/notification_chart_image" })
        }
        val now = Instant.now()
        fun asset(name: String) = instrumentation.context.assets.open(name).bufferedReader().use { it.readText() }
        val parsed = VoeParser.schedule(asset("emergency-haidamatska-9.html"), now)
        val incident = parsed.copy(currentOutage = parsed.currentOutage!!.copy(
            startedAt = now.minusSeconds(21600).toEpochMilli(), estimatedEnd = now.plusSeconds(129600).toEpochMilli()))
        // Move fixture calendar dates to the test day; the source HTML is a historical sample.
        val clear = VoeParser.schedule(asset("schedule.html"), now).let { parsedSchedule ->
            parsedSchedule.copy(days = parsedSchedule.days.mapIndexed { index, day ->
                day.copy(date = now.atZone(KYIV).toLocalDate().plusDays(index.toLong()).toString())
            })
        }
        val home = SavedAddress("notification-home", "Дім", AddressItem("1", "Вінниця"), AddressItem("2", "Гайдамацька"), AddressItem("3", "9"))
        val parents = home.copy(id = "notification-parents", title = "Батьки")
        val work = home.copy(id = "notification-work", title = "Робота")
        val minute = now.atZone(KYIV).hour*60+now.atZone(KYIV).minute
        val planned = clear.copy(days = clear.days.map { day -> day.copy(slots = day.slots.map {
            if (it.endMinute > minute && it.startMinute < minute+90) it.copy(state = PowerState.CONFIRMED_OUTAGE) else it
        }) })
        val probable = clear.copy(days = clear.days.map { day -> day.copy(slots = day.slots.map {
            if (it.endMinute > minute+30 && it.startMinute < minute+90) it.copy(state = PowerState.POSSIBLE_OUTAGE) else it
        }) })
        val state = AppState(addresses = listOf(home, parents, work), session = TrackingSession(now.plusSeconds(7200).toEpochMilli()),
            schedules = listOf(CachedSchedule(home.id, incident), CachedSchedule(parents.id, planned), CachedSchedule(work.id, probable)))
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            // Worst case: all rows have long incident details, at each supported font size.
            val worst = state.copy(schedules = state.addresses.map { CachedSchedule(it.id, incident) })
            for (scale in listOf(1f, 1.05f, 1.15f, 1.25f, 1.4f, 1.5f)) {
                val chart = NotificationChart.create(worst, now, NotificationTimeline.maxRows(scale))
                for (dark in listOf(false, true)) {
                    val image = NotificationTimeline.draw(chart, dark, 260f, scale)
                    assertTrue("scale=$scale rows=${chart.rows.size} height=${image.height}", image.height <= 440)
                    assertEquals(520, image.width)
                    image.recycle()
                }
            }
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            for (dark in listOf(false, true)) {
                shell("cmd uimode night ${if (dark) "yes" else "no"}")
                shell("settings put system font_scale 1.0"); Thread.sleep(1000)
                context.app.notifications.render(state, now); Thread.sleep(500)
                val notification = manager.activeNotifications.single { it.tag == TrackingNotifications.TAG }.notification
                assertEquals("Дім · Батьки · Робота", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Аварійне"))
                assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
                assertTrue(notification.actions.isNullOrEmpty())
                shell("cmd statusbar expand-notifications"); Thread.sleep(5000)
                expandChart()
                shell("screencap -p /sdcard/Download/svitlo-notification-semantic-${if (dark) "dark" else "light"}.png")
                shell("cmd statusbar collapse")
            }
            shell("settings put system font_scale 1.4"); Thread.sleep(1000)
            context.app.notifications.render(state.copy(addresses = state.addresses+home.copy(id = "extra", title = "Дуже довга назва адреси для перевірки")), now)
            Thread.sleep(500); shell("cmd statusbar expand-notifications"); Thread.sleep(5000)
            expandChart()
            shell("screencap -p /sdcard/Download/svitlo-notification-semantic-large.png")
        } finally {
            shell("cmd statusbar collapse")
            shell("settings put system font_scale $previousFont")
            shell("cmd uimode night ${when { previousNight.contains("yes") -> "yes"; previousNight.contains("no") -> "no"; else -> "auto" }}")
            context.app.notifications.render(context.app.store.load())
        }
    }
}
