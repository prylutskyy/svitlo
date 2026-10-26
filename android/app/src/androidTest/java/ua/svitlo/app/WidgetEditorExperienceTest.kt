package ua.svitlo.app

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
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
class WidgetEditorExperienceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val ui = instrument.uiAutomation
    private fun shell(command: String) = ui.executeShellCommand(command).use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    private fun nodes(): List<AccessibilityNodeInfo> {
        ui.clearCache()
        fun collect(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::collect).orEmpty() }
        return ui.rootInActiveWindow?.let(::collect).orEmpty()
    }
    private fun click(text: String) {
        repeat(35) {
            var node = nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
            if (node != null) {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                instrument.waitForIdleSync(); Thread.sleep(150)
                node = nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
                while (node != null) {
                    if (node.isClickable && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        instrument.waitForIdleSync(); Thread.sleep(250); return
                    }
                    node = node.parent
                }
            }
            nodes().firstOrNull { it.isScrollable && it.rangeInfo == null }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(150)
        }
        error("Missing $text: ${nodes().map { it.text to it.contentDescription }}")
    }

    private fun setScale(percent: Int) {
        repeat(35) {
            val slider = nodes().firstOrNull { it.contentDescription?.toString() == "Масштаб вмісту віджета" }
            if (slider != null) {
                slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                instrument.waitForIdleSync()
                val visible = nodes().first { it.rangeInfo != null }
                val args = android.os.Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,percent.toFloat()) }
                assertTrue("Slider actions: ${visible.actionList}; range: ${visible.rangeInfo}",visible.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,args))
                instrument.waitForIdleSync()
                return
            }
            nodes().firstOrNull { it.isScrollable && it.rangeInfo == null }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(100)
        }
        error("Scale slider missing")
    }

    @Test fun fourPickerStylesKeepSelectionsAndOtherWidgetsIndependent(): Unit = runBlocking {
        val original = context.app.store.load()
        val manager = AppWidgetManager.getInstance(context)
        val host = AppWidgetHost(context, 746)
        val ids = WidgetFormats.components(context).map { provider ->
            val id = host.allocateAppWidgetId()
            ui.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            try { assertTrue(manager.bindAppWidgetIdIfAllowed(id, provider)) } finally { ui.dropShellPermissionIdentity() }
            val info = manager.getAppWidgetInfo(id)
            val density = context.resources.displayMetrics.density
            assertTrue(info.minHeight/density <= (if (info.targetCellHeight == 2) 110 else 180)+1)
            id
        }
        val now = Instant.now()
        val addresses = listOf("Дім", "Робота").mapIndexed { i, title -> SavedAddress("editor-$i", title, AddressItem("1", "Вінниця"), AddressItem("2", "Вулиця"), AddressItem("3", "15")) }
        val incident = CurrentOutage("Синтетична аварія",true,now.minusSeconds(5400).toEpochMilli(),now.plusSeconds(8*3600).toEpochMilli(),"","")
        val fixture = AppState(addresses = addresses,currentAddressId = addresses.first().id,
            schedules = addresses.map { CachedSchedule(it.id,Schedule(now.toEpochMilli(),emptyList(),emptyList(),incident)) },
            widgets = ids.map { WidgetConfig(it,addresses.map { address -> address.id }) })
        try {
            context.app.store.update { fixture }
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            shell("mkdir -p /sdcard/Download/widget-editor")
            for ((index,id) in ids.withIndex()) {
                val before = context.app.store.state.value.widgets.orEmpty().filterNot { it.widgetId == id }
                ActivityScenario.launch<WidgetConfigurationActivity>(Intent(context,WidgetConfigurationActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id)).use {
                    val kind = WidgetFormats.style(manager.getAppWidgetInfo(id).provider)
                    if (kind == WidgetStyle.TIMELINE) click("Дім") else click("Робота")
                    assertTrue(nodes().none { it.text?.toString() in listOf("Розмір","Вигляд","Масштаб","Прозорий") })
                    if (kind == WidgetStyle.MONO) click("Інверсія кольорів") else click("Світла")
                    if (index == 0) shell("screencap -p /sdcard/Download/widget-editor/simple.png")
                    if (index == 0) click("Прозорий фон")
                    if (index == 1) click("Заокруглені кути")
                    setScale(120)
                    if (kind != WidgetStyle.SHORT) click("Показувати кнопку ⋯")
                    click("Зберегти")
                }
                instrument.waitForIdleSync()
                val saved = context.app.store.state.value.widgets.orEmpty()
                val config = requireNotNull(saved.firstOrNull { it.widgetId == id })
                assertEquals(listOf(addresses[1].id),config.addressIds)
                val kind = WidgetFormats.style(manager.getAppWidgetInfo(id).provider)
                assertEquals(kind == WidgetStyle.TIMELINE,config.alwaysShowGraphs)
                assertEquals(kind,config.style)
                assertEquals(before,saved.filterNot { it.widgetId == id })
                assertEquals(index == 0,config.transparentBackground)
                assertEquals(if (index == 1) false else null,config.roundedCorners)
                assertEquals(1.2f,config.sizeMultiplier,.001f)
                assertEquals(kind != WidgetStyle.SHORT,config.hideSettings)
                assertEquals(0,config.verticalDensity)
                assertEquals("light",config.surfaceStyle)
                assertEquals(kind == WidgetStyle.MONO,config.inverted == true)
                assertEquals(android.appwidget.AppWidgetProviderInfo.RESIZE_BOTH,manager.getAppWidgetInfo(id).resizeMode)
                assertEquals(saved,ua.svitlo.app.data.StateStore(context).load().widgets)
            }
            val saved = context.app.store.state.value
            SvitloWidgets.renderAll(context,saved)
            assertEquals(4,WidgetFormats.components(context).sumOf { manager.getAppWidgetIds(it).count { id -> id in ids } })
            val dark = WidgetPalette.resolve(context,WidgetConfig(1,emptyList(),surfaceStyle = "dark"))
            val light = WidgetPalette.resolve(context,WidgetConfig(1,emptyList(),surfaceStyle = "light"))
            val tinted = WidgetPalette.resolve(context,WidgetConfig(1,emptyList(),surfaceStyle = "tinted"))
            assertTrue(dark.dark); assertFalse(light.dark); assertNotNull(tinted.accent)
            assertNotEquals(light.background,tinted.background)
        } finally {
            host.deleteHost(); Thread.sleep(500)
            context.app.store.update { original }
        }
    }
}
