package ua.svitlo.app

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ua.svitlo.app.ui.*

@RunWith(AndroidJUnit4::class)
class AppAppearanceSettingsTest {
    @Test fun themeAndAccentApplyImmediatelyAndSurviveRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AppearancePreferences(context)
        val original = store.read()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun find(text: String): AccessibilityNodeInfo? {
            if (android.os.Build.VERSION.SDK_INT >= 33) ui.clearCache()
            fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                node ?: return null
                if (node.isVisibleToUser && (node.text?.toString() == text || node.contentDescription?.toString() == text)) return node
                for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
                return null
            }
            return visit(ui.rootInActiveWindow)
        }
        fun await(text: String): AccessibilityNodeInfo {
            repeat(100) { find(text)?.let { return it }; Thread.sleep(60) }
            repeat(8) {
                ui.executeShellCommand("input swipe 500 1900 500 800 350").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
                Thread.sleep(200)
                find(text)?.let { return it }
            }
            error("Missing $text")
        }
        fun click(text: String) {
            repeat(12) {
                find(text)?.let { found ->
                    var n=found
                    while(!n.isClickable && n.parent!=null)n=n.parent
                    assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    Thread.sleep(150)
                    return
                }
                val upwards=text in listOf("Світла","Темна","Як у системі","Назад")
                ui.executeShellCommand(if(upwards)"input swipe 500 800 500 1900 350" else "input swipe 500 1900 500 800 350").use {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
                }
                Thread.sleep(200)
            }
            error("Cannot click $text")
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                click("Інші дії"); click("Налаштування")
                await("Тема")
                click("Темна")
                assertEquals(AppThemeMode.DARK, store.read().mode)
                click("Синій")
                assertEquals(AppAccent.BLUE, store.read().accent)
                scenario.recreate()
                await("Тема")
                assertTrue(await("Темна").isChecked || await("Темна").parent.isChecked)
                assertEquals(AppAppearance(AppThemeMode.DARK, AppAccent.BLUE), AppearancePreferences(context).read())
                click("Світла")
                assertEquals(AppThemeMode.LIGHT, store.read().mode)
                click("Як у системі")
                assertEquals(AppThemeMode.SYSTEM, store.read().mode)
                click("Назад"); await("Інші дії")
            }
        } finally { store.theme(original.mode); store.accent(original.accent) }
    }
}
