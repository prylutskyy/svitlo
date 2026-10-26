package ua.svitlo.app

import android.appwidget.*
import android.content.*
import android.graphics.Bitmap
import android.os.Bundle
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
import ua.svitlo.app.domain.*
import ua.svitlo.app.ui.*
import ua.svitlo.app.widget.*
import java.io.File
import java.time.Instant

/** Reproducible product screenshots with fictional locations; no live address requests. */
@RunWith(AndroidJUnit4::class)
class ReadmeScreenshotsTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val ui = instrument.uiAutomation
    private fun nodes(): List<AccessibilityNodeInfo> {
        ui.clearCache()
        fun visit(n: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (n == null) emptyList() else listOf(n)+(0 until n.childCount).flatMap { visit(n.getChild(it)) }
        return visit(ui.rootInActiveWindow)
    }
    private fun click(label: String) {
        repeat(50) {
            var n = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label) }
            while (n != null) {
                if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { instrument.waitForIdleSync(); Thread.sleep(350); return }
                n = n.parent
            }
            Thread.sleep(100)
        }
        error("Missing $label")
    }
    @Test fun captureProductScreens(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val original = context.app.store.load()
        val appearance = AppearancePreferences(context)
        val originalAppearance = appearance.read()
        val host = AppWidgetHost(context,918)
        val output = File(context.filesDir,"readme-screenshots").apply { mkdirs() }
        fun capture(name: String) {
            instrument.waitForIdleSync(); Thread.sleep(350)
            val image = requireNotNull(ui.takeScreenshot())
            File(output,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        try {
            val now = Instant.now()
            val local = now.atZone(KYIV)
            val start = ((local.hour+3)*60).coerceAtMost(1260)
            val addresses = listOf("Дім","Робота","Батьки").mapIndexed { i,title -> SavedAddress("readme-$i",title,AddressItem("demo","Вінниця"),AddressItem("demo","Демонстраційна"),AddressItem("demo","${i+1}"),selected = false) }
            val fixture = AppState(addresses = addresses,currentAddressId = addresses.first().id,notificationsEnabled = false,
                schedules = addresses.mapIndexed { index,address ->
                    val a = if (index == 1) local.hour*60 else start
                    val b = (a+120).coerceAtMost(1440)
                    val slots = listOf(Slot(0,a,PowerState.NO_SCHEDULED_OUTAGE),Slot(a,b,PowerState.SCHEDULED_OUTAGE),Slot(b,1440,PowerState.NO_SCHEDULED_OUTAGE)).filter { it.endMinute > it.startMinute }
                    CachedSchedule(address.id,Schedule(now.toEpochMilli(),listOf(ScheduleDay("",local.toLocalDate().toString(),slots),ScheduleDay("",local.toLocalDate().plusDays(1).toString(),listOf(Slot(0,1440,PowerState.NO_SCHEDULED_OUTAGE)))),emptyList()))
                },attempts = addresses.map { FetchAttempt(it.id,now.toEpochMilli(),0,null) })
            context.app.store.update { fixture }
            appearance.theme(AppThemeMode.LIGHT); appearance.accent(AppAccent.GREEN)
            ui.executeShellCommand("input keyevent KEYCODE_WAKEUP").use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            ui.executeShellCommand("wm dismiss-keyguard").use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            ActivityScenario.launch(MainActivity::class.java).use {
                Thread.sleep(800); capture("home-light")
                appearance.theme(AppThemeMode.DARK); Thread.sleep(300); capture("home-dark")
                appearance.theme(AppThemeMode.LIGHT); Thread.sleep(300)
                click("Дім"); capture("details")
            }
            val manager = AppWidgetManager.getInstance(context)
            val id = host.allocateAppWidgetId()
            ui.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            try { assertTrue(manager.bindAppWidgetIdIfAllowed(id,ComponentName(context,SvitloWidgetProvider::class.java))) }
            finally { ui.dropShellPermissionIdentity() }
            manager.updateAppWidgetOptions(id,Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,250); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,110) })
            context.app.store.update { fixture.copy(widgets = listOf(WidgetConfig(id,listOf(addresses.first().id),contentScale = 1.2f,hideSettings = true,surfaceStyle = "light"))) }
            ActivityScenario.launch<WidgetConfigurationActivity>(Intent(context,WidgetConfigurationActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id)).use {
                Thread.sleep(600)
                nodes().firstOrNull { it.rangeInfo != null }?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                Thread.sleep(350); capture("widget-editor")
            }
            assertEquals(4,output.listFiles()?.count { it.extension == "png" })
        } finally {
            host.deleteHost()
            context.app.store.update { original }
            appearance.theme(originalAppearance.mode); appearance.accent(originalAppearance.accent)
        }
    }
}
