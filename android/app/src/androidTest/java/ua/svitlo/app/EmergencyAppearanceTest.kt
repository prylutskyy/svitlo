package ua.svitlo.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
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

/** Real saved provider response, replayed offline; no live network calls. */
@RunWith(AndroidJUnit4::class)
class EmergencyAppearanceTest {
    @Test fun emergencyIsVisibleInAppAndCollapsedNotification(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ui = instrumentation.uiAutomation
        fun shell(command: String) = ui.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        if (Build.VERSION.SDK_INT >= 33) ui.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val original = context.app.store.load()
        val now = Instant.now()
        val html = instrumentation.context.assets.open("emergency-haidamatska-9.html").bufferedReader().use { it.readText() }
        val schedule = VoeParser.schedule(html, now)
        val address = SavedAddress("emergency-preview", "Дім", AddressItem("510100000", "Вінниця"),
            AddressItem("1587", "Гайдамацька"), AddressItem("41668", "9"))
        val state = AppState(addresses = listOf(address), schedules = listOf(CachedSchedule(address.id, schedule)),
            attempts = listOf(FetchAttempt(address.id, now.toEpochMilli(), 0, null)))
        try {
            context.app.store.update { state }
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            ActivityScenario.launch(MainActivity::class.java).use {
                instrumentation.waitForIdleSync()
                fun visibleText(): String {
                    fun collect(node: android.view.accessibility.AccessibilityNodeInfo?): String {
                        if (node == null) return ""
                        return "${node.text?.toString().orEmpty()} ${node.contentDescription?.toString().orEmpty()} " +
                            (0 until node.childCount).joinToString(" ") { collect(node.getChild(it)) }
                    }
                    return collect(ui.rootInActiveWindow)
                }
                var found = false
                repeat(40) {
                    if (visibleText().contains("Аварійне відключення")) found = true
                    if (!found) Thread.sleep(100)
                }
                shell("screencap -p /sdcard/Download/svitlo-emergency-app.png")
                assertTrue("Incident banner must be visible: ${visibleText()}", found)
                assertTrue(visibleText().contains("19.10 17:56"))
                val tracked = state.copy(session = TrackingSession(now.plusSeconds(7200).toEpochMilli()))
                context.app.notifications.render(tracked, now)
                Thread.sleep(500)
                val notification = context.getSystemService(NotificationManager::class.java).activeNotifications
                    .single { it.tag == TrackingNotifications.TAG }.notification
                assertEquals("Дім", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Аварія"))
                assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("ще ≈"))
                shell("cmd statusbar expand-notifications"); Thread.sleep(2000)
                shell("screencap -p /sdcard/Download/svitlo-emergency-notification.png")
                val large = tracked.copy(addresses = (1..2).map { address.copy(id = "$it") },
                    schedules = (1..2).map { CachedSchedule("$it", schedule) })
                val bitmap = NotificationTimeline.draw(NotificationChart.create(large, now, 2), false, 280f, 1.5f)
                assertTrue("Two large incident rows must fit the notification", bitmap.height <= 440)
                bitmap.recycle()
            }
        } finally {
            shell("cmd statusbar collapse")
            context.app.store.update { original }
            context.app.reconcile()
        }
    }
}
