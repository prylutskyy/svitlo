package ua.svitlo.app

import android.content.Context
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
import ua.svitlo.app.data.VoeParser
import ua.svitlo.app.domain.*
import java.time.Instant

/** Offline provider fixtures. Exercises day labels, selection, rename and the address sheet. */
@RunWith(AndroidJUnit4::class)
class ExpressiveAppearanceTest {
    @Test fun expressiveScreensAndAddressActions(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ui = instrumentation.uiAutomation
        fun shell(command: String) = ui.executeShellCommand(command).use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8).trim() }
        fun nodes(): List<AccessibilityNodeInfo> {
            fun visit(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList()
                else listOf(node) + (0 until node.childCount).flatMap { visit(node.getChild(it)) }
            return visit(ui.rootInActiveWindow)
        }
        fun find(text: String) = nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
        fun click(text: String) {
            repeat(30) {
                if (find(text) != null) return@repeat
                nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                Thread.sleep(150)
            }
            var node = requireNotNull(find(text)) { "Missing UI: $text; ${nodes().map { it.text }}" }
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            repeat(6) {
                if (node.isClickable) { assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); instrumentation.waitForIdleSync(); Thread.sleep(500); return }
                node = node.parent ?: error("No click action: $text")
            }
            error("No click action: $text")
        }
        fun waitFor(test: () -> Boolean) {
            repeat(60) { if (test()) return; Thread.sleep(100) }
            shell("screencap -p /sdcard/Download/svitlo-expressive-failure.png")
            assertTrue("Visible UI: ${nodes().map { "${it.className}: ${it.text}" }}", test())
        }
        fun screenshot(name: String) { shell("screencap -p /sdcard/Download/svitlo-expressive-$name.png") }
        val previousSize = shell("wm size").lineSequence().firstOrNull { it.startsWith("Override size:") }?.substringAfter(":")?.trim()
        val previousNight = shell("cmd uimode night")
        val previousFont = shell("settings get system font_scale").takeUnless { it == "null" } ?: "1.0"
        val original = context.app.store.load()
        val now = Instant.now()
        fun asset(name: String) = instrumentation.context.assets.open(name).bufferedReader().use { it.readText() }
        val parsed = VoeParser.schedule(asset("emergency-haidamatska-9.html"), now)
        val emergency = parsed.copy(currentOutage = parsed.currentOutage!!.copy(
            startedAt = now.minusSeconds(21600).toEpochMilli(), estimatedEnd = now.plusSeconds(129600).toEpochMilli()))
        val date = now.atZone(KYIV).toLocalDate()
        val hour = now.atZone(KYIV).hour
        val clear = Schedule(now.toEpochMilli(), (0L..1L).map { day ->
            ScheduleDay("Графік", date.plusDays(day).toString(), (0..47).map { i ->
                Slot(i * 30, i * 30 + 30, when ((i - hour * 2).mod(12)) {
                    2, 3 -> PowerState.CONFIRMED_OUTAGE
                    4 -> PowerState.POSSIBLE_OUTAGE
                    else -> PowerState.NO_SCHEDULED_OUTAGE
                })
            })
        }, emptyList())
        val home = SavedAddress("expressive-home", "Дім", AddressItem("1", "Вінниця"), AddressItem("2", "Гайдамацька"), AddressItem("3", "9"))
        val family = home.copy(id = "expressive-family", title = "Батьки", street = AddressItem("4", "Соборна"), house = AddressItem("5", "1"))
        val state = AppState(addresses = listOf(home, family), currentAddressId = home.id, schedules = listOf(CachedSchedule(home.id, emergency), CachedSchedule(family.id, clear)),
            attempts = listOf(home, family).map { FetchAttempt(it.id, now.toEpochMilli(), 0, null) })
        try {
            shell("cmd uimode night no"); shell("settings put system font_scale 1.0")
            context.app.store.update { state }
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor { find("Аварійне відключення") != null }
                screenshot("light")
                click("Інші дії")
                waitFor { find("Про додаток і позначки") != null }
                click("Про додаток і позначки")
                waitFor { find("Про застосунок") != null }
                Thread.sleep(700)
                val sheetTop = android.graphics.Rect().also { find("Про застосунок")!!.getBoundsInScreen(it) }.top
                repeat(4) { shell("input swipe 540 1850 540 1250 350"); Thread.sleep(180) }
                assertTrue(kotlin.math.abs(sheetTop - android.graphics.Rect().also { find("Про застосунок")!!.getBoundsInScreen(it) }.top) <= 2)
                repeat(12) {
                    if (find("Сповіщення Android") == null) {
                        nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        Thread.sleep(100)
                    }
                }
                waitFor { find("Сповіщення Android") != null && find("Про застосунок") != null }
                screenshot("info-scroll")
                repeat(5) { shell("input swipe 540 1250 540 1850 350"); Thread.sleep(180) }
                assertTrue(kotlin.math.abs(sheetTop - android.graphics.Rect().also { find("Про застосунок")!!.getBoundsInScreen(it) }.top) <= 2)
                screenshot("info-return")
                assertTrue("After downward swipes: ${nodes().map { it.text }}", nodes().any { it.text?.toString() == "Як читати графік" })
                assertNull(find("Потягніть вниз, щоб закрити"))
                click("Назад")
                waitFor { find("Про застосунок") == null }
                assertNull(find("Найближчі години"))
                click("Сповіщення")
                waitFor { find("Попереджати про відключення") != null }; screenshot("notifications")
                shell("input keyevent KEYCODE_BACK"); Thread.sleep(500)
                click("Змінити"); waitFor { find("Де ви зараз?") != null }; screenshot("places")
                click("Батьки"); waitFor { context.app.store.state.value.currentAddressId == family.id }
                click("Змінити"); click("Дім"); waitFor { context.app.store.state.value.currentAddressId == home.id }
                click("Дії для Дім"); click("Повний графік"); waitFor { find("Найближчі години") != null }; screenshot("details")
                click("Назад")
                repeat(5) { nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }
                Thread.sleep(300)
                assertNull(find("На добу"))
                click("Дії для Дім"); click("Перейменувати")
                waitFor { nodes().any { it.isEditable } }
                val edit = requireNotNull(nodes().firstOrNull { it.isEditable })
                assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Мій дім")
                }))
                Thread.sleep(300); click("Зберегти")
                waitFor { context.app.store.state.value.addresses.first().title == "Мій дім" }
                click("Мій дім: показувати у сповіщенні")
                waitFor { !context.app.store.state.value.addresses.first().selected }
                shell("cmd uimode night yes"); Thread.sleep(500); scenario.recreate()
                waitFor { find("Аварійне відключення") != null }; screenshot("dark")
                shell("settings put system font_scale 1.3"); Thread.sleep(500); scenario.recreate()
                waitFor { find("Аварійне відключення") != null }; screenshot("large-font")
                shell("wm size 840x1920"); Thread.sleep(500); scenario.recreate()
                waitFor { find("Аварійне відключення") != null }; screenshot("narrow-320")
                context.app.store.update { it.copy(schedules = listOf(CachedSchedule(home.id,
                    emergency.copy(currentOutage = emergency.currentOutage!!.copy(emergency = false))), CachedSchedule(family.id, clear))) }
                waitFor { find("Аварійне відключення") == null && find("Відключення") != null }; screenshot("planned-320")
                shell("wm size ${previousSize ?: "reset"}"); Thread.sleep(300)
                shell("settings put system font_scale 1.0"); shell("cmd uimode night no"); Thread.sleep(500); scenario.recreate()
                val today = now.atZone(KYIV).toLocalDate()
                val tomorrow = today.plusDays(1)
                val nextDay = clear.copy(days = listOf(
                    ScheduleDay("Сьогодні", today.toString(), listOf(Slot(0, 1440, PowerState.NO_SCHEDULED_OUTAGE))),
                    ScheduleDay("Завтра", tomorrow.toString(), listOf(Slot(0, 360, PowerState.NO_SCHEDULED_OUTAGE),
                        Slot(360, 480, PowerState.CONFIRMED_OUTAGE), Slot(480, 1440, PowerState.NO_SCHEDULED_OUTAGE)))))
                context.app.store.update { it.copy(schedules = listOf(CachedSchedule(home.id, nextDay), CachedSchedule(family.id, clear))) }
                waitFor { find("Аварійне відключення") == null }
                screenshot("overview")
                click("Дії для Дім"); click("Повний графік")
                waitFor { find("Завтра 06:00 — 08:00") != null }
                screenshot("tomorrow")
                repeat(5) { nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }
                Thread.sleep(300)
                click("Додати адресу")
                waitFor { find("Де перевіряємо світло?") != null }; screenshot("add")
                click("Назад")
                context.app.store.update { AppState() }
                waitFor { find("Світло під\nвашим контролем") != null }; screenshot("empty")
                click("Додати адресу"); waitFor { find("Де перевіряємо світло?") != null }
            }
        } finally {
            shell("wm size ${previousSize ?: "reset"}")
            shell("settings put system font_scale $previousFont")
            shell("cmd uimode night ${when { previousNight.contains("yes") -> "yes"; previousNight.contains("no") -> "no"; else -> "auto" }}")
            context.app.store.update { original }; context.app.reconcile()
        }
    }
}
