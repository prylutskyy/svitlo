package ua.svitlo.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ua.svitlo.app.background.*
import ua.svitlo.app.data.StateStore
import ua.svitlo.app.domain.*
import java.time.Instant

/** Offline: durable dismissal, explicit restore, focus selection and native countdown/promotion request. */
@RunWith(AndroidJUnit4::class)
class PersistentNotificationsTest {
    @Test fun upcomingWarningPresentation(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (Build.VERSION.SDK_INT >= 33) ui.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        val now = Instant.now()
        val home = SavedAddress("warning-preview", "Дім", AddressItem("1", "Вінниця"), AddressItem("2", "Гайдамацька"), AddressItem("3", "9"))
        val target = now.plusSeconds(600)
        val days = (0L..1L).map { offset ->
            val date = now.atZone(KYIV).toLocalDate().plusDays(offset)
            val minute = if (target.atZone(KYIV).toLocalDate() == date)
                target.atZone(KYIV).hour * 60 + target.atZone(KYIV).minute else 1440
            ScheduleDay(date.toString(), date.toString(), listOf(
                Slot(0, minute, PowerState.NO_SCHEDULED_OUTAGE),
                Slot(minute, 1440, PowerState.CONFIRMED_OUTAGE)))
        }
        val state = AppState(addresses = listOf(home), currentAddressId = home.id, eventMonitoring = true,
            schedules = listOf(CachedSchedule(home.id, Schedule(now.toEpochMilli(), days, emptyList()))))
        val notifications = EventNotifications(context)
        try {
            assertNotNull("Fixture must contain an upcoming outage", FocusEvent.create(state, now))
            notifications.render(state, now)
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (manager.activeNotifications.none { it.tag == EventNotifications.TAG } &&
                android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            val notification = manager.activeNotifications.single { it.tag == EventNotifications.TAG }.notification
            assertEquals(R.drawable.ic_bolt_off, notification.smallIcon.resId)
            assertNull(notification.getLargeIcon())
            assertEquals("Незабаром відключення", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().startsWith("О "))
            assertTrue(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("графіком"))
            assertTrue(notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
            if (Build.VERSION.SDK_INT >= 36) assertTrue(notification.hasPromotableCharacteristics())
            fun shell(command: String) = ui.executeShellCommand(command).use {
                ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            shell("cmd statusbar expand-notifications")
            Thread.sleep(1500)
            shell("screencap -p /sdcard/Download/svitlo-warning-redesign.png")
            shell("cmd statusbar collapse")
            shell("input keyevent KEYCODE_HOME")
            Thread.sleep(2000)
            shell("screencap -p /sdcard/Download/svitlo-warning-home-pill.png")
            notifications.render(state, target)
            val cancelDeadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (manager.activeNotifications.any { it.tag == EventNotifications.TAG } &&
                android.os.SystemClock.elapsedRealtime() < cancelDeadline) Thread.sleep(50)
            assertFalse(manager.activeNotifications.any { it.tag == EventNotifications.TAG })
        } finally {
            notifications.render(context.app.store.load(), Instant.now())
        }
    }
    @Test fun durableGraphsAndCurrentPlace(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ui = instrumentation.uiAutomation
        val manager = context.getSystemService(NotificationManager::class.java)
        fun shell(command: String) = ui.executeShellCommand(command).use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8).trim() }
        fun nodes(): List<AccessibilityNodeInfo> {
            fun visit(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList()
                else listOf(node) + (0 until node.childCount).flatMap { visit(node.getChild(it)) }
            return visit(ui.rootInActiveWindow)
        }
        fun find(text: String) = nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
        fun waitFor(test: () -> Boolean) {
            repeat(80) { if (test()) return; Thread.sleep(100) }
            error("UI/state timeout: ${nodes().map { it.text ?: it.contentDescription }}")
        }
        fun click(text: String) {
            waitFor { find(text) != null }
            var node = requireNotNull(find(text))
            repeat(8) {
                if (node.isClickable) { assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); Thread.sleep(400); return }
                node = node.parent ?: error("No click for $text")
            }
            error("No clickable parent: $text")
        }
        fun graph() = manager.activeNotifications.firstOrNull { it.tag == TrackingNotifications.TAG }
        fun event() = manager.activeNotifications.firstOrNull { it.tag == EventNotifications.TAG }
        val original = context.app.store.load()
        val previousNight = shell("cmd uimode night")
        val previousFont = shell("settings get system font_scale").takeUnless { it == "null" } ?: "1.0"
        val now = Instant.now()
        val start = now.plusSeconds(600).atZone(KYIV).withSecond(0).withNano(0).toInstant()
        val finish = start.plusSeconds(3600)
        val days = (0L..1L).map { offset ->
            val date = now.atZone(KYIV).toLocalDate().plusDays(offset)
            val bounds = sortedSetOf(0,1440)
            listOf(start, finish).map { it.atZone(KYIV) }.filter { it.toLocalDate() == date }.forEach { bounds += it.hour * 60 + it.minute }
            ScheduleDay(date.toString(), date.toString(), bounds.toList().zipWithNext().map { (a,b) ->
                val at = date.atStartOfDay().plusMinutes(a.toLong()).atZone(KYIV).toInstant()
                Slot(a,b, if (at >= start && at < finish) PowerState.CONFIRMED_OUTAGE else PowerState.NO_SCHEDULED_OUTAGE)
            })
        }
        val home = SavedAddress("persistent-home", "Дім", AddressItem("1", "Вінниця"), AddressItem("2", "Гайдамацька"), AddressItem("3", "9"))
        val state = AppState(addresses = listOf(home), schedules = listOf(CachedSchedule(home.id, Schedule(now.toEpochMilli(), days, emptyList()))),
            attempts = listOf(FetchAttempt(home.id, now.toEpochMilli(),0,null)), notificationsEnabled = true)
        try {
            if (Build.VERSION.SDK_INT >= 33) ui.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            shell("cmd uimode night no"); shell("settings put system font_scale 1.0")
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            context.app.updateControl({ state })
            assertNotNull(graph())
            // Android 17 may inject its own 3-day timeout even when the app requests none.
            val systemTimeout = graph()!!.notification.timeoutAfter
            assertTrue(systemTimeout == 0L || systemTimeout >= 86400000L)
            graph()!!.notification.deleteIntent.send()
            waitFor { context.app.store.state.value.graphDismissed && graph() == null }
            assertTrue(StateStore(context).load().graphDismissed)
            context.app.reconcile()
            assertNull(graph())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor { find("Показати знову") != null }
                shell("screencap -p /sdcard/Download/svitlo-persistent-hidden.png")
                click("Показати знову")
                waitFor { graph() != null && !context.app.store.state.value.graphDismissed }
                assertNull(find("Відстежувати"))
                click("Дії для Дім"); click("Я зараз тут")
                waitFor { context.app.store.state.value.currentAddressId == home.id }
                click("Події поточного місця")
                waitFor { event() != null }
                val notification = event()!!.notification
                assertEquals(R.drawable.ic_bolt_off, notification.smallIcon.resId)
                assertEquals("Незабаром відключення", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().startsWith("О "))
                assertTrue(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("графіком"))
                assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
                assertTrue(notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
                assertNull(notification.bigContentView)
                assertEquals(start.toEpochMilli(), notification.`when`)
                if (Build.VERSION.SDK_INT >= 36) assertTrue(notification.hasPromotableCharacteristics())
                shell("screencap -p /sdcard/Download/svitlo-persistent-app.png")
                shell("cmd statusbar expand-notifications"); Thread.sleep(3000)
                shell("screencap -p /sdcard/Download/svitlo-persistent-event.png")
                shell("cmd statusbar collapse")
                event()!!.notification.deleteIntent.send()
                waitFor { event() == null && context.app.store.state.value.dismissedEventKey != null }
                context.app.reconcile()
                assertNull(event())
                assertEquals(context.app.store.state.value.dismissedEventKey, StateStore(context).load().dismissedEventKey)
                scenario.recreate(); waitFor { find("Я зараз тут: Дім") != null }
                assertNull(event())
                shell("cmd uimode night yes"); Thread.sleep(500); scenario.recreate()
                waitFor { find("Я зараз тут: Дім") != null }
                shell("screencap -p /sdcard/Download/svitlo-persistent-dark.png")
                shell("settings put system font_scale 1.3"); Thread.sleep(500); scenario.recreate()
                waitFor { find("Я зараз тут: Дім") != null }
                shell("screencap -p /sdcard/Download/svitlo-persistent-large.png")
                click("Показувати графіки у сповіщенні")
                waitFor { !context.app.store.state.value.graphsEnabled(Instant.now()) && graph() == null }
                assertTrue(context.app.store.state.value.active(Instant.now())) // current-place monitoring remains on
                click("Події поточного місця")
                waitFor { !context.app.store.state.value.active(Instant.now()) }
                shell("cmd uimode night no"); shell("settings put system font_scale 1.0"); scenario.recreate()
                waitFor { find("Я зараз тут: Дім") != null }
                val outageNow = Instant.now()
                val localNow = outageNow.atZone(KYIV)
                val minuteNow = localNow.hour * 60 + localNow.minute
                val currentDay = ScheduleDay(localNow.toLocalDate().toString(), localNow.toLocalDate().toString(), listOf(
                    Slot(0, minuteNow, PowerState.CONFIRMED_OUTAGE), Slot(minuteNow, 1440, PowerState.NO_SCHEDULED_OUTAGE)))
                val currentSchedule = Schedule(outageNow.toEpochMilli(), listOf(currentDay), emptyList())
                context.app.updateControl({ current -> current.copy(notificationsEnabled = true, graphDismissed = false,
                    eventMonitoring = true, dismissedEventKey = null,
                    schedules = listOf(CachedSchedule(home.id, currentSchedule))) })
                waitFor { graph() != null && event() == null }
                shell("screencap -p /sdcard/Download/svitlo-outage-no-pill-app.png")
                shell("cmd statusbar expand-notifications"); Thread.sleep(2500)
                shell("screencap -p /sdcard/Download/svitlo-outage-no-pill-shade.png")
                shell("cmd statusbar collapse")
                val warningState = state.copy(notificationsEnabled = true, graphDismissed = false, eventMonitoring = true,
                    currentAddressId = home.id, dismissedEventKey = null,
                    schedules = listOf(CachedSchedule(home.id, state.schedules.single().schedule.copy(fetchedAt = System.currentTimeMillis()))))
                context.app.updateControl({ warningState })
                waitFor { event() != null && FocusEvent.create(context.app.store.state.value, Instant.now()) != null }
                shell("cmd statusbar expand-notifications"); Thread.sleep(2500)
                shell("screencap -p /sdcard/Download/svitlo-warning-expanded.png")
                shell("cmd statusbar collapse")
                shell("input keyevent KEYCODE_HOME"); Thread.sleep(2500)
                shell("screencap -p /sdcard/Download/svitlo-warning-collapsed.png")
                shell("input tap 220 40"); Thread.sleep(1500)
                shell("screencap -p /sdcard/Download/svitlo-warning-pill-expanded.png")
            }
        } finally {
            shell("cmd statusbar collapse")
            shell("settings put system font_scale $previousFont")
            shell("cmd uimode night ${when { previousNight.contains("yes") -> "yes"; previousNight.contains("no") -> "no"; else -> "auto" }}")
            context.app.updateControl({ original })
        }
    }
    @Test fun legacySessionsMigrateDurably(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = java.io.File(context.cacheDir, "migration-${System.nanoTime()}").apply { mkdirs() }
        val scoped = object : android.content.ContextWrapper(context) { override fun getFilesDir() = directory }
        val file = java.io.File(directory, "state-v1.json")
        file.writeText("{\"session\":{\"until\":${Instant.now().plusSeconds(3600).toEpochMilli()}}}")
        val migrated = StateStore(scoped).load()
        assertEquals(true, migrated.notificationsEnabled)
        assertNull(migrated.session)
        assertEquals(migrated, StateStore(scoped).load())
        file.writeText("{\"session\":{\"until\":1}}")
        assertEquals(false, StateStore(scoped).load().notificationsEnabled)
        assertEquals(false, StateStore(scoped).load().notificationsEnabled)
    }
}
