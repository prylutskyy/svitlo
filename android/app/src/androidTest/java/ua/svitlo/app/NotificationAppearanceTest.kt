package ua.svitlo.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
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
import ua.svitlo.app.domain.*
import java.io.File
import java.time.Instant

/** Offline fixtures only. Screenshots are not evidence of real VOE outage parsing. */
@RunWith(AndroidJUnit4::class)
class NotificationAppearanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun shell(command: String) = ui.executeShellCommand(command).use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    private fun fixture(now: Instant): AppState {
        val addresses = listOf(
            SavedAddress("preview-1", "Дім", AddressItem("1", "Вінниця"), AddressItem("11", "Соборна"), AddressItem("111", "1")),
            SavedAddress("preview-2", "Батьки", AddressItem("2", "Гопчиця"), AddressItem("22", "Шевченка"), AddressItem("222", "15")),
            SavedAddress("preview-3", "Робота", AddressItem("3", "Погребище"), AddressItem("33", "Вишнева"), AddressItem("333", "7")))
        val hour = now.atZone(KYIV).hour
        fun schedule(family: Boolean) = Schedule(now.toEpochMilli(), listOf(ScheduleDay("Сьогодні", now.atZone(KYIV).toLocalDate().toString(),
            (0..47).map { i -> Slot(i*30, (i+1)*30, when {
                family && i < hour*2+3 -> PowerState.CONFIRMED_OUTAGE
                !family && i in hour*2+4 until hour*2+8 -> PowerState.CONFIRMED_OUTAGE
                !family && i in hour*2+8 until hour*2+10 -> PowerState.POSSIBLE_OUTAGE
                else -> PowerState.NO_SCHEDULED_OUTAGE
            }) })), emptyList())
        return AppState(addresses = addresses, session = TrackingSession(now.plusSeconds(7200).toEpochMilli()), schedules = listOf(
            CachedSchedule(addresses[0].id, schedule(false)), CachedSchedule(addresses[1].id, schedule(true))))
    }
    private fun save(name: String, bitmap: Bitmap) {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Svitlo")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
            requireNotNull(resolver.openOutputStream(uri)).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } else {
            File(context.getExternalFilesDir(null), "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
    @Test fun thinChartsRemainOneOngoingNotification(): Unit = runBlocking {
        if (Build.VERSION.SDK_INT >= 33) ui.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val now = Instant.now()
        val state = fixture(now)
        val notifications = context.app.notifications
        val manager = context.getSystemService(NotificationManager::class.java)
        fun active() = manager.activeNotifications.filter { it.tag == TrackingNotifications.TAG || it.tag?.startsWith(TrackingNotifications.PREFIX) == true }
        fun settle(count: Int, title: String? = null) {
            repeat(50) {
                val list = active()
                if (list.size == count && list.all { it.tag == TrackingNotifications.TAG } && (title == null || list.single().notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains(title))) return
                Thread.sleep(100)
            }
            assertEquals(count, active().size)
            if (title != null) assertTrue(active().single().notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains(title))
        }
        try {
            manager.notify(TrackingNotifications.PREFIX+"legacy", TrackingNotifications.ID,
                Notification.Builder(context, TrackingNotifications.CHANNEL).setSmallIcon(R.drawable.ic_bolt).setContentTitle("Legacy").build())
            Thread.sleep(300)
            notifications.render(state, now)
            settle(1, "Дім · Батьки · Робота")
            val notification = active().single().notification
            assertEquals("Дім · Батьки · Робота", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Батьки"))
            assertTrue(notification.extras.getString(Notification.EXTRA_TEMPLATE)!!.endsWith("DecoratedCustomViewStyle"))
            assertNotNull(notification.bigContentView)
            assertNotNull(notification.deleteIntent)
            assertTrue(notification.actions.isNullOrEmpty())
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL == 0)
            if (Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) assertFalse(notification.hasPromotableCharacteristics())
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            val info = ui.serviceInfo
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            ui.serviceInfo = info
            shell("cmd statusbar expand-notifications")
            Thread.sleep(5000)
            fun setExpanded(expand: Boolean) {
            val matches = ui.rootInActiveWindow?.findAccessibilityNodeInfosByText("Світло").orEmpty()
            var expanded = false
            for (match in matches) {
                var node: AccessibilityNodeInfo? = match
                repeat(7) {
                    if (!expanded) {
                        val button = node?.findAccessibilityNodeInfosByViewId("com.android.systemui:id/expand_button")?.firstOrNull()
                        if (button != null) {
                            if (button.contentDescription?.toString()?.contains(if (expand) "expand" else "collapse", ignoreCase = true) == true) button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            expanded = true
                        }
                        node = node?.parent
                    }
                }
            }
            }
            setExpanded(true)
            Thread.sleep(1500)
            shell("screencap -p /sdcard/Download/svitlo-minimal-expanded.png")
            val extra = (4..5).map { state.addresses[0].copy(id = "extra-$it") }
            notifications.render(state.copy(addresses = state.addresses + extra), now); settle(1, "Дім · Батьки · Робота · Дім · Дім")
            notifications.render(state.copy(addresses = state.addresses.take(2)), now); settle(1, "Дім · Батьки")
            notifications.render(state.copy(addresses = state.addresses.take(1)), now); settle(1, "Дім")
            notifications.render(state.copy(addresses = emptyList()), now); settle(0)
        } finally {
            shell("cmd statusbar collapse")
            notifications.render(context.app.store.load())
        }
    }
    @Test fun rendererFitsNarrowAndDarkSurfaces() {
        val state = fixture(Instant.parse("2026-10-18T13:20:00Z"))
        val model = NotificationChart.create(state, Instant.parse("2026-10-18T13:20:00Z"))
        listOf(false, true).forEach { dark ->
            val image = NotificationTimeline.draw(model, dark, widthDp = 280f)
            assertEquals(560, image.width)
            assertTrue(image.height <= 440)
            val preview = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            Canvas(preview).apply { drawColor(if (dark) Color.rgb(37,44,41) else Color.rgb(240,243,240)); drawBitmap(image, 0f, 0f, null) }
            save(if (dark) "svitlo-thin-dark" else "svitlo-thin-narrow", preview)
            image.recycle(); preview.recycle()
        }
        val large = NotificationChart.create(state, model.now, maxRows = 2)
        val image = NotificationTimeline.draw(large, false, widthDp = 280f, fontScale = 1.4f)
        assertTrue(image.height <= 440)
        save("svitlo-thin-large-font", image)
        image.recycle()
    }
}
