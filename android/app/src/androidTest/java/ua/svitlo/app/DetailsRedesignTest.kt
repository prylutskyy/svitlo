package ua.svitlo.app

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ua.svitlo.app.domain.*
import ua.svitlo.app.ui.*
import ua.svitlo.app.widget.WidgetAddressesActivity
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class DetailsRedesignTest {
 private val instrument=InstrumentationRegistry.getInstrumentation();private val ui=instrument.uiAutomation
 private val now=Instant.parse("2026-11-06T11:00:00Z")
 private val address=SavedAddress("details-demo","Черга 1.2",AddressItem("feed","Вінницька · Вінницяобленерго"),AddressItem("queue","Черга"),AddressItem("GPV3.2","3.2"),feedId="voe/Vinnytsiaoblenerho",feedGroup="GPV3.2")
 private fun schedule(at:Instant=now):Schedule {
  val date=at.atZone(KYIV).toLocalDate()
  fun day(offset:Long,a:Int,b:Int)=ScheduleDay("",date.plusDays(offset).toString(),listOf(Slot(0,a,PowerState.NO_SCHEDULED_OUTAGE),Slot(a,b,PowerState.SCHEDULED_OUTAGE),Slot(b,1440,PowerState.NO_SCHEDULED_OUTAGE)))
  return Schedule(at.toEpochMilli(),listOf(day(0,840,1080),day(1,480,600)),emptyList(),queue="3.2",sourceUrl="https://raw.githubusercontent.com/example/feed")
 }
 private fun nodes():List<AccessibilityNodeInfo> {
  ui.clearCache();fun collect(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null)emptyList() else listOf(n)+(0 until n.childCount).flatMap{collect(n.getChild(it))}
  val info=ui.serviceInfo;info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.serviceInfo=info
  return ui.windows.sortedByDescending{it.layer}.flatMap{collect(it.root)}.filter{it.isVisibleToUser}
 }
 private fun node(text:String)=nodes().firstOrNull{it.text?.toString()==text || it.contentDescription?.toString()==text}
 private fun waitFor(check:()->Boolean) {repeat(100){if(check())return;Thread.sleep(60)};error("Details state missing: ${nodes().map{it.text ?: it.contentDescription}}")}
 private fun click(text:String) {waitFor{node(text)!=null};var n=node(text)!!;while(!n.isClickable)n=n.parent;assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK));instrument.waitForIdleSync()}
 private fun drag(x1:Float,y1:Float,x2:Float,y2:Float) {
  val down=SystemClock.uptimeMillis()
  fun event(action:Int,x:Float,y:Float) {val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0).apply{source=InputDevice.SOURCE_TOUCHSCREEN};try{assertTrue(ui.injectInputEvent(e,true))}finally{e.recycle()}}
  event(MotionEvent.ACTION_DOWN,x1,y1);for(i in 1..20){event(MotionEvent.ACTION_MOVE,x1+(x2-x1)*i/20,y1+(y2-y1)*i/20);Thread.sleep(16)};event(MotionEvent.ACTION_UP,x2,y2);instrument.waitForIdleSync()
 }
 private fun capture(name:String) {val folder=File(instrument.targetContext.filesDir,"details-redesign").apply{mkdirs()};requireNotNull(ui.takeScreenshot()).let{b->File(folder,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}}
 @Test fun tabsSwipeAndCurrentIdentityAreReadableWithoutExplanatoryClutter() {
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{a->a.setContent{SvitloTheme(dark=true){AddressDetailsPage(address,schedule(),null,now,null,{},{})}}}
   waitFor{node("14:00–18:00")!=null};assertNotNull(node("Черга 3.2"));assertNull(node("Черга 1.2"))
   assertNull(node("Джерело та оновлення"));assertTrue(nodes().none{it.text?.toString()?.startsWith("Синя лінія")==true})
   assertTrue(nodes().none{it.text?.toString()?.startsWith("Зараз 13:")==true})
   capture("today")
   click("Завтра");waitFor{node("08:00–10:00")!=null};assertNotNull(node("2 год"));capture("tomorrow")
   val rect=Rect().also{requireNotNull(node("Розклад за днями")).getBoundsInScreen(it)}
   drag(rect.left+rect.width()*.15f,rect.exactCenterY(),rect.left+rect.width()*.85f,rect.exactCenterY())
   waitFor{node("14:00–18:00")!=null}
   assertNotNull(node("Історія та статистика"))
  }
 }
 @Test fun settingsRenameAndPullToRefreshHaveWorkingActions() {
  val renamed=AtomicReference<String>();val edit=AtomicInteger();val refresh=AtomicInteger()
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{a->a.setContent{SvitloTheme(dark=false){AddressDetailsPage(address,schedule(),null,now,null,{},{},onFeed={edit.incrementAndGet()},onRename={renamed.set(it)},onRefresh={refresh.incrementAndGet()})}}}
   waitFor{node("Налаштування місця")!=null};click("Налаштування місця");click("Оператор і черга");assertEquals(1,edit.get())
   click("Налаштування місця");click("Перейменувати");waitFor{nodes().any{it.isEditable}}
   val args=android.os.Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"Мій дім")
   assertTrue(nodes().first{it.isEditable}.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args));instrument.waitForIdleSync();click("Зберегти");waitFor{renamed.get()=="Мій дім"}
   waitFor{nodes().none{it.isEditable} && node("Вінницяобленерго")!=null}
   val rect=Rect().also{requireNotNull(node("Вінницяобленерго")).getBoundsInScreen(it)}
   drag(rect.left+20f,rect.exactCenterY(),rect.left+20f,rect.exactCenterY()+750f)
   waitFor{refresh.get()>0};assertEquals(1,refresh.get());capture("light")
  }
 }
 @Test fun widgetDetailsOpenRealProviderEditor():Unit=runBlocking {
  val context=instrument.targetContext;val before=context.app.store.load();val at=Instant.now()
  try {
   context.app.store.update{AppState(dataSourceVersion=2,addresses=listOf(address),notificationsEnabled=false,
    schedules=listOf(CachedSchedule(address.id,schedule(at))),attempts=listOf(FetchAttempt(address.id,at.toEpochMilli(),0,null)),widgets=listOf(WidgetConfig(994,listOf(address.id))))}
   val intent=Intent(context,WidgetAddressesActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,994).putExtra(WidgetAddressesActivity.EXTRA_ADDRESS_ID,address.id)
   ActivityScenario.launch<WidgetAddressesActivity>(intent).use{
    waitFor{node("Налаштування місця")!=null};click("Налаштування місця");click("Оператор і черга")
    waitFor{node("Джерело та аварії")!=null};capture("widget-editor")
   }
  } finally {context.app.store.update{before}}
 }
 @Test fun widgetBackArrowAndSystemBackOpenMainScreenWithoutAddressList():Unit=runBlocking {
  val context=instrument.targetContext;val before=context.app.store.load()
  try {
   for(systemBack in listOf(false,true)) {
    val at=Instant.now()
    context.app.store.update{AppState(dataSourceVersion=2,addresses=listOf(address),notificationsEnabled=false,
     schedules=listOf(CachedSchedule(address.id,schedule(at))),attempts=listOf(FetchAttempt(address.id,at.toEpochMilli(),0,null)),widgets=listOf(WidgetConfig(994,listOf(address.id))))}
    val intent=Intent(context,WidgetAddressesActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,994).putExtra(WidgetAddressesActivity.EXTRA_ADDRESS_ID,address.id)
    ActivityScenario.launch<WidgetAddressesActivity>(intent).use {
     waitFor{node("Налаштування місця")!=null};assertNull(node("Адреси віджета"))
     if(systemBack) ui.executeShellCommand("input keyevent KEYCODE_BACK").use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
     else click("Назад")
     waitFor{node("Світло")!=null && node("Налаштування місця")==null}
     assertNull(node("Адреси віджета"));capture(if(systemBack)"widget-system-back" else "widget-arrow-back")
    }
   }
  } finally {context.app.store.update{before}}
 }
}
