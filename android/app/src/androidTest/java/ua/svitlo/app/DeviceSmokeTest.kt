package ua.svitlo.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ua.svitlo.app.background.TrackingNotifications
import ua.svitlo.app.data.VoeClient
import ua.svitlo.app.domain.*
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @Test fun directVoePersistenceAndNotificationLifecycle() = runBlocking {
        // This is an explicit device integration check; it accesses public VOE data.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val services = context.app
        val address = SavedAddress("510100000:1652:26590", "Тест · Соборна, 1", AddressItem("510100000", "Вінниця"),
            AddressItem("1652", "Соборна"), AddressItem("26590", "1"))
        val schedule = VoeClient().Batch().schedule(address.copy(feedId="voe/Vinnytsiaoblenerho",feedGroup="GPV1.2"))
        assertTrue("Expected a live VOE day", schedule.days.isNotEmpty())
        assertTrue(schedule.days.all { it.slots.size == 48 })
        val original = services.store.load()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        try {
            services.store.update { AppState(addresses = listOf(address), schedules = listOf(CachedSchedule(address.id, schedule)),
                session = TrackingSession(Instant.now().plusSeconds(7200).toEpochMilli())) }
            services.reconcile()
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            assertTrue(notificationManager.activeNotifications.any { it.tag == TrackingNotifications.TAG })
            ActivityScenario.launch(MainActivity::class.java).use {
                instrumentation.waitForIdleSync()
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                if (screenshot != null) {
                    File(context.getExternalFilesDir(null), "device-smoke.png").outputStream().use { out -> screenshot.compress(Bitmap.CompressFormat.PNG, 100, out) }
                    screenshot.recycle()
                }
                instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/svitlo-device-smoke.png").use {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
                }
                instrumentation.uiAutomation.executeShellCommand("cmd statusbar expand-notifications").close()
                Thread.sleep(800)
                instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/svitlo-notification.png").use {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
                }
                instrumentation.uiAutomation.executeShellCommand("cmd statusbar collapse").close()
            }
            services.store.update { it.copy(addresses = listOf(address.copy(selected = false)), session = null) }
            services.reconcile()
            assertFalse(notificationManager.activeNotifications.any { it.tag == TrackingNotifications.TAG })
        } finally {
            services.store.update { original }
            services.reconcile()
        }
    }
}
