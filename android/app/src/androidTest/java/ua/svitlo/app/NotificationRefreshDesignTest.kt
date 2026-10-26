package ua.svitlo.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
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
import ua.svitlo.app.background.*
import ua.svitlo.app.domain.*
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class NotificationRefreshDesignTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val manager = context.getSystemService(NotificationManager::class.java)
    private fun shell(command: String) = ui.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8).trim()
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) ui.clearCache()
        fun visit(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else listOf(node)+(0 until node.childCount).flatMap { visit(node.getChild(it)) }
        return (ui.windows.flatMap { visit(it.root) }+visit(ui.rootInActiveWindow))
    }
    private fun shot(name: String) { Thread.sleep(600); shell("screencap -p /sdcard/Download/svitlo-notify-$name.png") }
    private fun await(check: () -> Boolean) { repeat(80) { if (check()) return; Thread.sleep(75) }; error("Timeout: ${nodes().map { it.text to it.contentDescription }}") }
    private fun fixture(now: Instant,possible: Boolean = false): AppState {
        val addresses = listOf("Дім","Робота","Батьки").mapIndexed { index,title -> SavedAddress("notify-$index",title,AddressItem("1","Вінниця"),AddressItem("2","Гайдамацька"),AddressItem("3","9")) }
        val target = now.plusSeconds(600).atZone(KYIV).withSecond(0).withNano(0).toInstant()
        val days = (0..1).map { offset ->
            val date = now.atZone(KYIV).toLocalDate().plusDays(offset.toLong())
            val midnight = date.atStartOfDay(KYIV).toInstant()
            val a = java.time.Duration.between(midnight,target).toMinutes().toInt().coerceIn(0,1440)
            val b = java.time.Duration.between(midnight,target.plusSeconds(7200)).toMinutes().toInt().coerceIn(0,1440)
            ScheduleDay("",date.toString(),listOf(Slot(0,a,PowerState.NO_SCHEDULED_OUTAGE),Slot(a,b,if (possible) PowerState.POSSIBLE_OUTAGE else PowerState.CONFIRMED_OUTAGE),Slot(b,1440,PowerState.NO_SCHEDULED_OUTAGE)).filter { it.endMinute > it.startMinute })
        }
        return AppState(addresses = addresses,currentAddressId = addresses.first().id,eventMonitoring = true,notificationsEnabled = true,
            schedules = addresses.mapIndexed { index,address -> CachedSchedule(address.id,Schedule(now.toEpochMilli(),
                if (index == 2) days.map { day -> day.copy(slots = day.slots.map { if (it.state == PowerState.CONFIRMED_OUTAGE) it.copy(state = PowerState.POSSIBLE_OUTAGE) else it }) } else days,emptyList(),
                if (index == 1) CurrentOutage("Ремонт",true,now.minusSeconds(3600).toEpochMilli(),now.plusSeconds(36*3600).toEpochMilli(),"","") else null)) },
            attempts = addresses.map { FetchAttempt(it.id,now.toEpochMilli(),0,null) })
    }
    @Test fun measuredPanelKeepsOrderOverflowAndReadableFonts() {
        val now = Instant.now(); val state = fixture(now)
        val all = NotificationChart.create(state,now,Int.MAX_VALUE)
        for (width in listOf(216f,260f,320f)) for (scale in listOf(1f,1.15f,1.4f,1.5f)) {
            val fitted = NotificationPanel.fit(all,width,scale)
            assertTrue(fitted.rows.isNotEmpty())
            assertEquals(3,fitted.rows.size+fitted.hidden)
            assertEquals(all.rows.filter { row -> fitted.rows.any { it.address.id == row.address.id } },fitted.rows)
            assertEquals("Робота",fitted.focus!!.address.title)
            for (dark in listOf(false,true)) {
                val image = NotificationPanel.draw(fitted,dark,width,scale)
                assertTrue("$width/$scale: ${image.height}",image.height <= 440)
                assertEquals(0,Color.alpha(image.getPixel(0,0)))
            }
        }
    }
    @Test fun nativePillAndGraphsRemainCompactAndExpireAtStart(): Unit = runBlocking {
        val original = context.app.store.load()
        val notices = context.getSharedPreferences("restoration-notices",Context.MODE_PRIVATE)
        val posted = notices.getString("posted",null)
        val eventSounds = context.getSharedPreferences("event-sounds",Context.MODE_PRIVATE)
        val previousEvent = eventSounds.getString("last-event",null)
        val night = shell("cmd uimode night"); val font = shell("settings get system font_scale").takeUnless { it == "null" } ?: "1.0"
        if (Build.VERSION.SDK_INT >= 33) ui.grantRuntimePermission(context.packageName,Manifest.permission.POST_NOTIFICATIONS)
        ui.serviceInfo = ui.serviceInfo.apply { flags = flags or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val now = Instant.now(); val state = fixture(now)
        try {
            shell("cmd uimode night no"); shell("settings put system font_scale 1.0")
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard"); shell("input keyevent KEYCODE_HOME")
            context.app.store.update { state }
            EventNotifications(context).render(state,now)
            await { manager.activeNotifications.any { it.tag == EventNotifications.TAG } }
            val event = manager.activeNotifications.single { it.tag == EventNotifications.TAG }.notification
            assertEquals("Дім · Скоро вимкнення",event.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertTrue(event.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().split('\n').size <= 2)
            assertNull(event.getLargeIcon()); assertNull(event.bigContentView)
            assertEquals(R.drawable.ic_bolt_off,event.smallIcon.resId)
            assertTrue(event.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
            if (Build.VERSION.SDK_INT >= 36) assertTrue(event.hasPromotableCharacteristics())
            Thread.sleep(2000); shot("pill-home")
            val screen = ui.takeScreenshot()!!
            val x = (screen.width*.24f).toInt(); val y = (screen.height*.013f).toInt(); screen.recycle()
            shell("input tap $x $y")
            Thread.sleep(1200)
            shot("pill-expanded")
            shell("input keyevent KEYCODE_BACK"); shell("cmd statusbar collapse")
            EventNotifications(context).render(state,FocusEvent.create(state,now)!!.target!!)
            await { manager.activeNotifications.none { it.tag == EventNotifications.TAG } }
            val graphs = state.copy(eventMonitoring = false)
            context.app.store.update { graphs }
            context.app.notifications.render(graphs,now)
            shell("cmd statusbar expand-notifications"); Thread.sleep(1500)
            fun expand() {
                val row = nodes().firstOrNull { it.text?.toString() == "Дім · Робота · Батьки" }
                var parent = row
                while (parent != null && parent.viewIdResourceName != "com.android.systemui:id/expandableNotificationRow") parent = parent.parent
                fun visit(n: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (n == null) emptyList() else listOf(n)+(0 until n.childCount).flatMap { visit(n.getChild(it)) }
                val button = visit(parent).firstOrNull { it.viewIdResourceName == "android:id/expand_button" }
                if (button?.contentDescription?.toString()?.contains("expand",true) == true) button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            expand(); await { nodes().any { it.viewIdResourceName == "ua.svitlo.app:id/notification_chart_image" } }; shot("graphs-light")
            shell("cmd statusbar collapse"); shell("cmd uimode night yes"); shell("settings put system font_scale 1.4"); Thread.sleep(1000)
            context.app.notifications.render(graphs,now)
            shell("cmd statusbar expand-notifications"); Thread.sleep(1000); expand(); shot("graphs-dark-large")
            shell("cmd statusbar collapse")
            val clear = graphs.copy(schedules = graphs.addresses.map { CachedSchedule(it.id,Schedule(now.toEpochMilli(),listOf(ScheduleDay("",now.atZone(KYIV).toLocalDate().toString(),listOf(Slot(0,1440,PowerState.NO_SCHEDULED_OUTAGE)))),emptyList())) })
            context.app.notifications.render(clear,now)
            await { manager.activeNotifications.any { it.tag == TrackingNotifications.TAG &&
                it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "Відключень не позначено" } }
            val calm = manager.activeNotifications.single { it.tag == TrackingNotifications.TAG }.notification
            assertNull(calm.bigContentView)
            assertEquals("Відключень не позначено",calm.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            shell("cmd statusbar expand-notifications"); Thread.sleep(1000); shot("calm")
            shell("cmd statusbar collapse")
            val restored = clear.copy(eventMonitoring = true,restorationNotifiedKey = "notification-ui-fixture:${System.nanoTime()}")
            context.app.store.update { restored }
            context.app.publish()
            await { manager.activeNotifications.any { it.tag == "restoration" } }
            val restoration = manager.activeNotifications.single { it.tag == "restoration" }.notification
            assertEquals("Дім · Відключення знято",restoration.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertTrue(restoration.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("VOE"))
            shell("cmd statusbar expand-notifications"); Thread.sleep(1000); shot("restoration")
        } finally {
            shell("cmd statusbar collapse"); manager.cancel(EventNotifications.TAG,EventNotifications.ID); manager.cancel(TrackingNotifications.TAG,TrackingNotifications.ID)
            shell("settings put system font_scale $font"); shell("cmd uimode night ${when { night.contains("yes") -> "yes"; night.contains("no") -> "no"; else -> "auto" }}")
            manager.cancel("restoration",3)
            notices.edit().apply { if (posted == null) remove("posted") else putString("posted",posted) }.apply()
            eventSounds.edit().apply { if (previousEvent == null) remove("last-event") else putString("last-event",previousEvent) }.apply()
            context.app.updateControl({ original })
        }
    }
}
