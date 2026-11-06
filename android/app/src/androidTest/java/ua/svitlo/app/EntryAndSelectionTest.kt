package ua.svitlo.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ua.svitlo.app.background.refreshSchedulesOnOpen
import ua.svitlo.app.domain.*
import ua.svitlo.app.ui.*
import ua.svitlo.app.widget.WidgetAddressesActivity
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class EntryAndSelectionTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val ui=instrument.uiAutomation
    private fun nodes():List<AccessibilityNodeInfo> {
        ui.clearCache()
        fun collect(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null) emptyList() else listOf(n)+(0 until n.childCount).flatMap {collect(n.getChild(it))}
        return collect(ui.rootInActiveWindow)
    }
    private fun waitFor(check:()->Boolean) {repeat(100) {if(check())return;Thread.sleep(50)};error("Missing entry or selection state")}
    private fun find(text:String)=nodes().firstOrNull {it.text?.toString()==text || it.contentDescription?.toString()==text}
    private fun bounds(node:AccessibilityNodeInfo)=Rect().also {node.getBoundsInScreen(it)}
    private fun tap(node:AccessibilityNodeInfo,fraction:Float=.5f) {
        val rect=bounds(node);val down=SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,rect.left+rect.width()*fraction,rect.exactCenterY(),0).apply {source=InputDevice.SOURCE_TOUCHSCREEN}
            try {assertTrue(ui.injectInputEvent(event,true))} finally {event.recycle()}
        }
        instrument.waitForIdleSync()
    }
    private fun capture(name:String) {
        val folder=File(instrument.targetContext.filesDir,"entry-selection").apply {mkdirs()}
        requireNotNull(ui.takeScreenshot()).let {bitmap -> File(folder,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
    }
    @Test fun selectionAppearsBelowFooterWithoutMovingItAndOutsideTapDismisses() {
        val now=Instant.parse("2026-11-06T11:00:00Z")
        val address=SavedAddress("selection-demo","Дім",AddressItem("feed","Вінницька · Вінницяобленерго"),AddressItem("queue","Черга"),
            AddressItem("GPV3.2","3.2"),feedId="voe/Vinnytsiaoblenerho",feedGroup="GPV3.2")
        val schedule=Schedule(now.toEpochMilli(),listOf(ScheduleDay("","2026-11-06",listOf(
            Slot(0,840,PowerState.NO_SCHEDULED_OUTAGE),Slot(840,1080,PowerState.SCHEDULED_OUTAGE),Slot(1080,1440,PowerState.NO_SCHEDULED_OUTAGE)))),emptyList())
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            scenario.onActivity {a -> a.setContent {SvitloTheme(dark=true) {
                Surface(Modifier.fillMaxSize()) {Column(Modifier.safeDrawingPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    ExpressiveAddressCard(address,schedule,null,now,{}, {}, {},isHere=true)
                    Button(onClick={}) {Text("Поза карткою")}
                }}
            }}}
            waitFor {find("Дані 13:00")!=null}
            val footer=bounds(requireNotNull(find("Дані 13:00")))
            capture("collapsed")
            val graph=nodes().first {it.contentDescription?.toString()?.startsWith("Часова шкала.")==true}
            tap(graph,.25f)
            waitFor {find("Сьогодні 14:00 — 18:00")!=null}
            assertEquals(footer,bounds(requireNotNull(find("Дані 13:00"))))
            Thread.sleep(300)
            assertEquals(footer,bounds(requireNotNull(find("Дані 13:00"))))
            assertTrue(bounds(requireNotNull(find("Сьогодні 14:00 — 18:00"))).top>footer.bottom)
            capture("selected")
            tap(requireNotNull(find("Поза карткою")))
            waitFor {find("Сьогодні 14:00 — 18:00")==null}
            assertEquals(footer,bounds(requireNotNull(find("Дані 13:00"))))
        }
    }
    @Test fun bothLauncherAndWidgetEntryRefreshOnEveryResume():Unit=runBlocking {
        val context=instrument.targetContext;val store=context.app.store;val before=store.load()
        try {
            val at=Instant.now()
            val address=SavedAddress("entry-test","Дім",AddressItem("1","Місто"),AddressItem("2","Вулиця"),AddressItem("3","1"))
            val plan=Schedule(at.toEpochMilli(),listOf(ScheduleDay("",at.atZone(KYIV).toLocalDate().toString(),listOf(Slot(0,1440,PowerState.NO_SCHEDULED_OUTAGE)))),emptyList())
            store.update {AppState(dataSourceVersion=2,notificationsEnabled=false,addresses=listOf(address),
                schedules=listOf(CachedSchedule(address.id,plan)),attempts=listOf(FetchAttempt(address.id,at.toEpochMilli(),0,null)),widgets=listOf(WidgetConfig(733,listOf(address.id))))}
            for(type in listOf(MainActivity::class.java,WidgetAddressesActivity::class.java)) {
                val calls=AtomicInteger()
                ActivityScenario.launch<ComponentActivity>(Intent(context,type).putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID,733)).use {scenario ->
                    scenario.onActivity {activity -> activity.refreshSchedulesOnOpen {opened ->if(opened)calls.incrementAndGet()}}
                    waitFor {calls.get()==1}
                    scenario.moveToState(Lifecycle.State.CREATED)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    waitFor {calls.get()==2}
                }
            }
        } finally {store.update {before}}
    }
}
