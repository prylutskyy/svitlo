package ua.svitlo.app

import android.appwidget.*
import android.content.*
import android.graphics.Rect
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
import ua.svitlo.app.widget.*
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class InterfaceRedesignTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun shell(command: String) = ui.executeShellCommand(command).use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    private fun nodes(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 33) ui.clearCache()
        fun visit(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else
            listOf(node)+(0 until node.childCount).flatMap { visit(node.getChild(it)) }
        return visit(ui.rootInActiveWindow)
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun click(text: String) {
        repeat(35) {
            val match = find(text)
            if (match != null) {
                var node: AccessibilityNodeInfo = match
                repeat(8) {
                    if (node.isClickable) { assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); Thread.sleep(300); return }
                    node = node.parent ?: error("Not clickable: $text")
                }
            }
            nodes().firstOrNull { it.isScrollable && it.rangeInfo == null }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(150)
        }
        error("Missing $text: ${nodes().map { it.text ?: it.contentDescription }}")
    }
    private fun bounds(text: String) = Rect().also { requireNotNull(find(text)).getBoundsInScreen(it) }
    private fun screenshot(name: String) { shell("screencap -p /sdcard/Download/svitlo-redesign-$name.png") }
    private fun fixture(now: Instant): AppState {
        val names = listOf("Дім","Робота","Батьки","Дача","Гараж","ВНТУ","Офіс","Квартира")
        val addresses = names.mapIndexed { index,name -> SavedAddress("design-$index",name,AddressItem("1","Вінниця"),AddressItem("2","Гайдамацька"),AddressItem("$index","${index+1}")) }
        val day = now.atZone(KYIV).toLocalDate()
        val schedule = Schedule(now.toEpochMilli(),listOf(ScheduleDay("",day.toString(),listOf(Slot(0,1440,PowerState.NO_SCHEDULED_OUTAGE)))),emptyList())
        return AppState(addresses = addresses,currentAddressId = addresses.first().id,notificationsEnabled = false,
            schedules = addresses.map { CachedSchedule(it.id,schedule) },attempts = addresses.map { FetchAttempt(it.id,now.toEpochMilli(),0,null) })
    }
    @Test fun pinnedPreviewSurvivesScrollAndTabsAndOnlySavePersists(): Unit = runBlocking {
        val original = context.app.store.load()
        val host = AppWidgetHost(context,810)
        val manager = AppWidgetManager.getInstance(context)
        val id = host.allocateAppWidgetId()
        try {
            ui.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            try { assertTrue(manager.bindAppWidgetIdIfAllowed(id,ComponentName(context,SvitloWidgetProvider::class.java))) }
            finally { ui.dropShellPermissionIdentity() }
            manager.updateAppWidgetOptions(id,Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,320)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,360)
            })
            val value = fixture(Instant.now())
            val config = WidgetConfig(id,value.addresses.map { it.id })
            context.app.store.update { value.copy(widgets = listOf(config)) }
            val intent = Intent(context,WidgetConfigurationActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id)
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            ActivityScenario.launch<WidgetConfigurationActivity>(intent).use { scenario ->
                Thread.sleep(1200)
                click("Показати приклад")
                assertTrue(bounds("Попередній перегляд віджета").height() > 0)
                click("Темна")
                assertTrue(nodes().none { it.rangeInfo != null })
                screenshot("widget-simple")
                click("Попередній перегляд віджета")
                assertNotNull(find("Розгорнутий перегляд віджета"))
                click("Закрити перегляд")
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                Thread.sleep(1000)
                assertTrue(bounds("Попередній перегляд віджета").height() > 0)
                screenshot("widget-landscape")
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                Thread.sleep(800)
                assertEquals(config,context.app.store.state.value.widgets!!.single())
                click("Зберегти")
                Thread.sleep(500)
            }
            val saved = context.app.store.state.value.widgets!!.single()
            assertFalse(saved.monochrome)
            assertEquals("dark",saved.surfaceStyle)
            assertEquals(1f,saved.sizeMultiplier,.001f)
            assertEquals(listOf(config.addressIds.first()),saved.addressIds)
            assertEquals(value.schedules,context.app.store.state.value.schedules)
        } finally { host.deleteHost(); context.app.updateControl({ original }) }
    }
    @Test fun homeDetailsVisualGuideAndAppearanceRemainNavigable(): Unit = runBlocking {
        val original = context.app.store.load()
        try {
            context.app.store.update { fixture(Instant.now()) }
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            ActivityScenario.launch(MainActivity::class.java).use {
                Thread.sleep(1000)
                screenshot("home")
                click("Дії для Дім"); click("Повний графік")
                screenshot("details")
                assertNotNull(find("Найближчі години"))
                click("Назад")
                click("Інші дії"); click("Про додаток і позначки")
                click("Аварійне")
                assertNotNull(find("Аварія. Час відновлення орієнтовний."))
                screenshot("guide")
                click("Назад")
                click("Інші дії"); click("Налаштування")
                screenshot("appearance")
                click("Назад")
                click("Сповіщення")
                screenshot("notifications")
                assertNotNull(find("Події поточного місця"))
            }
        } finally { context.app.updateControl({ original }) }
    }
}
