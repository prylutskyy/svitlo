package ua.svitlo.app

import android.content.Context
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
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class HistoryAppearanceTest {
    @Test fun historyFiltersAndReadableJournal(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ui = instrumentation.uiAutomation
        fun shell(command: String) = ui.executeShellCommand(command).use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8).trim() }
        fun nodes(): List<AccessibilityNodeInfo> {
            fun visit(n: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (n == null) emptyList() else listOf(n) + (0 until n.childCount).flatMap { visit(n.getChild(it)) }
            return visit(ui.rootInActiveWindow)
        }
        fun find(text: String) = nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
        fun waitFor(test: () -> Boolean) {
            repeat(80) { if (test()) return; Thread.sleep(100) }
            error("History UI timeout: ${nodes().map { it.text }}")
        }
        fun click(text: String) {
            waitFor { find(text) != null }
            var node = requireNotNull(find(text))
            repeat(8) {
                if (node.isClickable) { assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); Thread.sleep(300); return }
                node = node.parent ?: error("Missing click action: $text")
            }
        }
        val original = context.app.store.load()
        val previousNight = shell("cmd uimode night")
        val previousFont = shell("settings get system font_scale").takeUnless { it == "null" } ?: "1.0"
        val now = Instant.now()
        val address = SavedAddress("history-preview-${System.nanoTime()}","Дім",AddressItem("1","Вінниця"),AddressItem("2","Гайдамацька"),AddressItem("3","9"))
        fun graph(at: Instant, outage: Boolean): Schedule {
            val incident = if (outage) CurrentOutage("Аварійні ремонтні роботи",true,now.minusSeconds(5*3600).toEpochMilli(),now.minusSeconds(2*3600).toEpochMilli(),"","") else null
            return Schedule(at.toEpochMilli(),listOf(ScheduleDay("",at.atZone(KYIV).toLocalDate().toString(),listOf(
                Slot(0,360,PowerState.CONFIRMED_OUTAGE),Slot(360,420,PowerState.POSSIBLE_OUTAGE),Slot(420,1440,PowerState.NO_SCHEDULED_OUTAGE)))),emptyList(),incident)
        }
        try {
            (96 downTo 0).forEach { step -> context.app.history.record(address,null,graph(now.minusSeconds(step*1800L),step in 6..10)) }
            val schedule = graph(now,false)
            context.app.store.update { AppState(addresses = listOf(address),schedules = listOf(CachedSchedule(address.id,schedule)),
                attempts = listOf(FetchAttempt(address.id,now.toEpochMilli(),0,null)),notificationsEnabled = false) }
            shell("cmd uimode night no"); shell("settings put system font_scale 1.0")
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor { find("Історія та статистика Дім") != null }
                click("Історія та статистика Дім")
                waitFor { find("Позначені відключення") != null && find("Аварійні") != null }
                assertNotNull(find("Назад"))
                assertNull(find("Потягніть вниз, щоб закрити"))
                shell("screencap -p /sdcard/Download/svitlo-history-week.png")
                click("Місяць")
                waitFor { find("Позначені відключення") != null }
                click("Тиждень")
                repeat(6) { nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); Thread.sleep(150) }
                waitFor { find("Аварійне відключення") != null }
                shell("screencap -p /sdcard/Download/svitlo-history-journal.png")
                shell("cmd uimode night yes"); shell("settings put system font_scale 1.3"); scenario.recreate()
                waitFor { find("Історія та статистика") != null }
                Thread.sleep(600)
                shell("screencap -p /sdcard/Download/svitlo-history-dark-large.png")
                click("Назад")
                waitFor { find("Інші дії") != null }
                click("Інші дії")
                waitFor { find("Про додаток і позначки") != null }
                click("Про додаток і позначки")
                waitFor { find("Як читати графік") != null }
                assertNull(find("Потягніть вниз, щоб закрити"))
                shell("screencap -p /sdcard/Download/svitlo-info-page.png")
                shell("input keyevent KEYCODE_BACK")
                waitFor { find("Інші дії") != null }
                click("Сповіщення")
                waitFor { find("Попереджати про відключення") != null }
                assertNotNull(find("Назад"))
                shell("screencap -p /sdcard/Download/svitlo-notifications-page.png")
                click("Назад")
                waitFor { find("Додати адресу") != null }
                click("Додати адресу")
                waitFor { find("Де перевіряємо світло?") != null }
                assertNull(find("Потягніть вниз, щоб закрити"))
                shell("screencap -p /sdcard/Download/svitlo-add-page.png")
                click("Назад")
                waitFor { find("Інші дії") != null }
            }
        } finally {
            context.app.store.update { original }
            listOf("intervals","incidents","checkpoints").forEach { context.app.history.writableDatabase.delete(it,"address=?",arrayOf(address.id)) }
            shell("settings put system font_scale $previousFont")
            shell("cmd uimode night ${if (previousNight.contains("yes")) "yes" else "no"}")
        }
    }
}
