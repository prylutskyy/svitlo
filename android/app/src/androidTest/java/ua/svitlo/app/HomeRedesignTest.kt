package ua.svitlo.app

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ua.svitlo.app.domain.*
import ua.svitlo.app.ui.*
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class HomeRedesignTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val ui=instrument.uiAutomation
    private fun nodes():List<AccessibilityNodeInfo> {
        ui.clearCache()
        fun visit(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null) emptyList() else listOf(n)+(0 until n.childCount).flatMap {visit(n.getChild(it))}
        return visit(ui.rootInActiveWindow)
    }
    private fun waitFor(check:()->Boolean) {
        repeat(80) {if(check())return;Thread.sleep(75)}
        error("Home state missing: ${nodes().map {it.text ?: it.contentDescription}}")
    }
    private fun capture(name:String) {
        instrument.waitForIdleSync()
        val folder=File(instrument.targetContext.filesDir,"home-redesign").apply {mkdirs()}
        requireNotNull(ui.takeScreenshot()).let {bitmap -> File(folder,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
    }
    private fun fixture():AppState {
        val now=Instant.now();val date=now.atZone(KYIV).toLocalDate();val minute=now.atZone(KYIV).hour*60+now.atZone(KYIV).minute
        val places=listOf("Черга 1.2","Батьки").mapIndexed {i,title -> SavedAddress("home-demo-$i",title,
            AddressItem("feed","Вінницька · Вінницяобленерго"),AddressItem("queue","Черга"),AddressItem("GPV3.2","3.2"),
            feedId="voe/Vinnytsiaoblenerho",feedGroup="GPV3.2")}
        return AppState(dataSourceVersion=2,addresses=places,currentAddressId=places.first().id,notificationsEnabled=false,
            schedules=places.mapIndexed {i,place ->
                val start=if(i==0) (minute-60).coerceAtLeast(0) else (minute+120).coerceAtMost(1380)
                val end=(start+180).coerceAtMost(1440)
                val slots=listOf(Slot(0,start,PowerState.NO_SCHEDULED_OUTAGE),Slot(start,end,PowerState.SCHEDULED_OUTAGE),Slot(end,1440,PowerState.NO_SCHEDULED_OUTAGE)).filter {it.endMinute>it.startMinute}
                CachedSchedule(place.id,Schedule(now.toEpochMilli(),listOf(ScheduleDay("",date.toString(),slots)),emptyList(),queue="3.2",sourceUrl="https://raw.githubusercontent.com/example/feed"))
            },attempts=places.map {FetchAttempt(it.id,now.toEpochMilli(),0,null)})
    }
    @Test fun homeUsesCurrentQueueWithoutDuplicatePlanBadgeInBothThemes():Unit=runBlocking {
        val store=instrument.targetContext.app.store;val before=store.load()
        try {
            store.update {fixture()}
            ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                for(dark in listOf(false,true)) {
                    scenario.onActivity {a -> a.setContent {SvitloTheme(dark=dark) {SvitloScreen({})}}}
                    waitFor {nodes().any {it.text?.toString()=="Черга 3.2"}}
                    assertFalse(nodes().any {it.text?.toString()=="Черга 1.2"})
                    assertFalse(nodes().any {it.text?.toString()?.startsWith("План за чергою")==true})
                    assertFalse(nodes().any {it.isClickable && it.contentDescription?.toString()=="Оновити графіки"})
                    assertTrue(nodes().any {it.contentDescription?.toString()=="Зараз відключення за планом"})
                    capture(if(dark) "home-dark" else "home-light")
                }
            }
        } finally {store.update {before}}
    }
    @Test fun homeRemainsReadableWithLargeText():Unit=runBlocking {
        val store=instrument.targetContext.app.store;val before=store.load()
        try {
            store.update {fixture()}
            ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                scenario.onActivity {a -> a.setContent {SvitloTheme(dark=false) {
                    val density=LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density,1.7f)) {SvitloScreen({})}
                }}}
                waitFor {nodes().any {it.text?.toString()=="Черга 3.2"}}
                assertTrue(nodes().any {it.text?.toString()?.startsWith("За планом до")==true})
                capture("home-large-text")
            }
        } finally {store.update {before}}
    }
    @Test fun pullingListDownTriggersOneRefresh() {
        val calls=AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            scenario.onActivity {a -> a.setContent {SvitloTheme {
                Surface(Modifier.fillMaxSize()) {
                    HomeRefreshContainer(false,{calls.incrementAndGet()},Modifier.fillMaxSize().safeDrawingPadding()) {
                        LazyColumn(Modifier.fillMaxSize()) {items(20) {Text("Місце $it",Modifier.fillMaxWidth().height(60.dp).padding(16.dp))}}
                    }
                }
            }}}
            waitFor {nodes().any {it.text?.toString()=="Місце 0"}}
            val bounds=android.graphics.Rect().also {ui.rootInActiveWindow.getBoundsInScreen(it)}
            val x=bounds.exactCenterX();val from=bounds.top+bounds.height()*.15f;val to=bounds.top+bounds.height()*.75f
            val down=SystemClock.uptimeMillis()
            fun event(action:Int,y:Float) {
                val value=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0).apply {source=InputDevice.SOURCE_TOUCHSCREEN}
                try {assertTrue(ui.injectInputEvent(value,true))} finally {value.recycle()}
            }
            event(MotionEvent.ACTION_DOWN,from)
            for(i in 1..20) {event(MotionEvent.ACTION_MOVE,from+(to-from)*i/20);Thread.sleep(16)}
            event(MotionEvent.ACTION_UP,to)
            waitFor {calls.get()>0};assertEquals(1,calls.get())
        }
    }
}
