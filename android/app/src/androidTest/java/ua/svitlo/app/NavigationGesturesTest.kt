package ua.svitlo.app

import android.content.Context
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.BackEventCompat
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
class NavigationGesturesTest {
    @Test fun predictiveCancelCommitAndNativeSheetDrag(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ui = instrumentation.uiAutomation
        fun shell(command: String) = ui.executeShellCommand(command).use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        fun nodes(): List<AccessibilityNodeInfo> {
            if (android.os.Build.VERSION.SDK_INT >= 33) ui.clearCache()
            fun visit(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else
                listOf(node) + (0 until node.childCount).flatMap { visit(node.getChild(it)) }
            return visit(ui.rootInActiveWindow)
        }
        fun find(text: String) = nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
        fun waitFor(test: () -> Boolean) {
            repeat(80) { if (test()) return; Thread.sleep(75) }
            error("Gesture timeout: ${nodes().map { it.text ?: it.contentDescription }}")
        }
        fun bounds(text: String) = Rect().also { requireNotNull(find(text)).getBoundsInScreen(it) }
        fun click(text: String) {
            waitFor { find(text) != null }
            var node = requireNotNull(find(text))
            repeat(8) {
                if (node.isClickable) { assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); Thread.sleep(300); return }
                node = node.parent ?: error("Not clickable: $text")
            }
        }
        val original = context.app.store.load()
        val now = Instant.now()
        val addresses = listOf("Дім","Батьки","Робота").mapIndexed { index,title -> SavedAddress("gesture-$index",title,
            AddressItem("1","Вінниця"),AddressItem("2","Гайдамацька"),AddressItem(index.toString(),"${index+1}")) }
        val schedule = Schedule(now.toEpochMilli(),listOf(ScheduleDay("",now.atZone(KYIV).toLocalDate().toString(),
            listOf(Slot(0,1440,PowerState.NO_SCHEDULED_OUTAGE)))),emptyList())
        try {
            context.app.store.update { AppState(addresses = addresses,currentAddressId = addresses.first().id,
                notificationsEnabled = false,schedules = addresses.map { CachedSchedule(it.id,schedule) },
                attempts = addresses.map { FetchAttempt(it.id,now.toEpochMilli(),0,null) }) }
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                click("Інші дії")
                waitFor { find("Про додаток і позначки") != null }
                click("Про додаток і позначки")
                waitFor { find("Назад") != null }
                val originalLeft = bounds("Назад").left
                scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f,400f,0f,BackEventCompat.EDGE_LEFT)) }
                Thread.sleep(100)
                scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(300f,400f,0.55f,BackEventCompat.EDGE_LEFT)) }
                Thread.sleep(200)
                shell("screencap -p /sdcard/Download/svitlo-back-preview.png")
                waitFor { find("Назад") != null && bounds("Назад").left > originalLeft + 100 }
                shell("screencap -p /sdcard/Download/svitlo-back-preview.png")
                scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
                waitFor { find("Назад") != null && kotlin.math.abs(bounds("Назад").left-originalLeft) < 3 }
                assertNotNull(find("Як читати графік"))
                scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(900f,400f,0f,BackEventCompat.EDGE_RIGHT)) }
                Thread.sleep(100)
                scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(600f,400f,0.4f,BackEventCompat.EDGE_RIGHT)) }
                Thread.sleep(100)
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                waitFor { find("Інші дії") != null }
                click("Змінити")
                waitFor { find("Де ви зараз?") != null }
                Thread.sleep(600) // Measure the fully settled anchor, not the entrance animation.
                val handle = bounds("Потягніть вниз, щоб закрити")
                val titleTop = bounds("Де ви зараз?").top
                val title = bounds("Де ви зараз?")
                val screenHeight = requireNotNull(ui.takeScreenshot()).let { val height = it.height; it.recycle(); height }
                val drag = ui.executeShellCommand("input -d 0 swipe ${title.centerX()} ${title.centerY()} ${title.centerX()} ${screenHeight-40} 1800")
                Thread.sleep(800)
                val draggedTop = bounds("Де ви зараз?").top
                shell("screencap -p /sdcard/Download/svitlo-sheet-drag.png")
                drag.use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
                assertTrue("Sheet should follow the finger: $titleTop -> $draggedTop; handle=$handle title=$title screen=$screenHeight", draggedTop > titleTop + 40)
                // A short slow drag may settle back; a long swipe must dismiss it.
                if (find("Де ви зараз?") != null) {
                    val y = bounds("Потягніть вниз, щоб закрити").centerY()
                    shell("input swipe ${handle.centerX()} $y ${handle.centerX()} ${context.resources.displayMetrics.heightPixels-30} 350")
                }
                waitFor { find("Де ви зараз?") == null }
                // Selection also awaits native hide(), rather than abruptly removing the sheet.
                click("Змінити"); waitFor { find("Батьки") != null }
                click("Батьки")
                waitFor { find("Де ви зараз?") == null && context.app.store.state.value.currentAddressId == addresses[1].id }
            }
        } finally { context.app.store.update { original }; context.app.reconcile() }
    }
}
