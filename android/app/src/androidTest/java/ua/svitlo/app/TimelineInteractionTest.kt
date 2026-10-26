package ua.svitlo.app

import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ua.svitlo.app.domain.*
import ua.svitlo.app.ui.*
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class TimelineInteractionTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val ui = instrument.uiAutomation
    private fun nodes(): List<AccessibilityNodeInfo> {
        ui.clearCache()
        fun collect(n: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (n == null) emptyList() else
            listOf(n)+(0 until n.childCount).flatMap { collect(n.getChild(it)) }
        return collect(ui.rootInActiveWindow)
    }
    private fun waitFor(check: () -> Boolean) {
        repeat(60) { if (check()) return; Thread.sleep(75) }
        error("Timeline state missing: ${nodes().map { it.text ?: it.contentDescription }}")
    }
    private fun node(label: String) = nodes().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
    private fun click(label: String) {
        waitFor { node(label) != null }
        var n = requireNotNull(node(label))
        while (!n.isClickable) n = requireNotNull(n.parent)
        assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrument.waitForIdleSync()
    }
    private fun tap(n: AccessibilityNodeInfo, fraction: Float = .5f) {
        val bounds = Rect().also(n::getBoundsInScreen)
        val x = bounds.left+bounds.width()*fraction; val y = bounds.exactCenterY()
        val at = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP).forEachIndexed { i,action ->
            val event = MotionEvent.obtain(at,at+i*60L,action,x,y,0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(ui.injectInputEvent(event,true)) } finally { event.recycle() }
        }
        instrument.waitForIdleSync()
    }
    @Test fun tapSelectsLongOutageWithoutChangingScaleAndContinuationPagesTwelveHours() {
        val now = Instant.parse("2026-10-26T07:00:00Z")
        val incident = CurrentOutage("Аварійне відключення",true,now.minusSeconds(3*3600).toEpochMilli(),now.plusSeconds(30*3600).toEpochMilli(),"","")
        val schedule = Schedule(now.toEpochMilli(),emptyList(),emptyList(),incident)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { SvitloTheme {
                Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding().padding(20.dp)) {
                    ScheduleTimeline(schedule,now,notificationWindow(now),"Графік")
                    Button(onClick = {}) { Text("Поза графіком") }
                } }
            } } }
            waitFor { nodes().any { it.contentDescription?.startsWith("Часова шкала.") == true } }
            assertNotNull(node("Відключення триває далі →"))
            tap(nodes().first { it.contentDescription?.startsWith("Часова шкала.") == true })
            waitFor { node("12 год · сьогодні") != null }
            assertNotNull(node("Сьогодні 06:00 — завтра 15:00"))
            assertNotNull(node("Відключення триває далі →"))
            click("Відключення триває далі →")
            waitFor { node("Зараз") != null }
            assertTrue(nodes().none { it.text?.toString()?.startsWith("35 год") == true })
            waitFor { node("Поза графіком") != null }
            tap(requireNotNull(node("Поза графіком")))
            waitFor { node("12 год · сьогодні") != null }
        }
    }
    @Test fun homeAndDetailsSupportExactIntervalsFutureJumpAndOutsideTapWithoutStealingButtons() {
        val now = Instant.parse("2026-10-26T07:00:00Z")
        val date = now.atZone(KYIV).toLocalDate()
        val days = (0L..1L).map { offset -> ScheduleDay("",date.plusDays(offset).toString(),listOf(
            Slot(0,960,PowerState.NO_SCHEDULED_OUTAGE),Slot(960,1080,PowerState.SCHEDULED_OUTAGE),Slot(1080,1440,PowerState.NO_SCHEDULED_OUTAGE))) }
        val schedule = Schedule(now.toEpochMilli(),days,emptyList())
        val address = SavedAddress("a","Дім",AddressItem("1",""),AddressItem("2",""),AddressItem("3",""))
        val chart = NotificationChart.create(AppState(addresses = listOf(address),schedules = listOf(CachedSchedule("a",schedule))),now)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (details in listOf(false,true)) {
                scenario.onActivity { activity -> activity.setContent { SvitloTheme { key(details) {
                    var clicked by remember { mutableStateOf(false) }
                    Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding().padding(20.dp)) {
                        Text(if (details) "Деталі графіка" else "Графік локації",style = MaterialTheme.typography.titleLarge)
                        if (details) ScheduleTimeline(schedule,now,notificationWindow(now),"Найближчі години") else AddressTimeline(chart,schedule)
                        Button(onClick = { clicked = true }) { Text(if (clicked) "Дія виконана" else "Інша дія") }
                    } }
                } } } }
                waitFor { nodes().any { it.contentDescription?.startsWith("Часова шкала.") == true } }
                val canvas = nodes().first { it.contentDescription?.startsWith("Часова шкала.") == true }
                tap(canvas,.75f) // 17:00 Kyiv within the published 16:00–18:00 event.
                waitFor { node("Сьогодні 16:00 — 18:00") != null }
                ui.executeShellCommand("screencap -p /sdcard/Download/timeline-before.png").use {
                    ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
                }
                click("Далі · завтра 16:00 →")
                waitFor { node("Завтра 16:00 — 18:00") != null }
                Thread.sleep(400)
                tap(nodes().first { it.contentDescription?.startsWith("Часова шкала.") == true },.15f)
                assertNotNull(node("Зараз")) // A graph tap chooses a point without returning to the present.
                ui.executeShellCommand("screencap -p /sdcard/Download/timeline-${if (details) "details" else "home"}.png").use {
                    ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
                }
                waitFor { node("Інша дія") != null }
                tap(requireNotNull(node("Інша дія")))
                waitFor { node("Дія виконана") != null && node("Зараз") == null }
                assertNotNull(node("Далі · завтра 16:00 →"))
                assertNull(node("Завтра 16:00 — 18:00"))
            }
        }
    }
}
