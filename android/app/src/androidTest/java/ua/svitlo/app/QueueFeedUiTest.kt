package ua.svitlo.app
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ua.svitlo.app.ui.*
import ua.svitlo.app.data.*
import java.util.concurrent.atomic.AtomicReference
@RunWith(AndroidJUnit4::class)
class QueueFeedUiTest {
 private val instrument=InstrumentationRegistry.getInstrumentation()
 private fun nodes():List<AccessibilityNodeInfo> {
  instrument.uiAutomation.clearCache()
  fun collect(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null) emptyList() else listOf(n)+(0 until n.childCount).flatMap{collect(n.getChild(it))}
  val info=instrument.uiAutomation.serviceInfo
  info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
  instrument.uiAutomation.serviceInfo=info
  val windows=instrument.uiAutomation.windows.sortedByDescending {it.layer}
  return if(windows.isEmpty()) collect(instrument.uiAutomation.rootInActiveWindow) else windows.flatMap {collect(it.root)}
 }
 private fun find(text:String)=nodes().firstOrNull{it.isVisibleToUser && (it.text?.toString()==text || it.contentDescription?.toString()==text || it.viewIdResourceName==text)}
 private fun waitFor(check:()->Boolean) {repeat(100){if(check())return;Thread.sleep(50)};screenshot("promin-failure.png");java.io.File(instrument.targetContext.filesDir,"promin-ui-failure.txt").writeText(nodes().joinToString("\n"){"${it.viewIdResourceName} | ${it.text} | ${it.contentDescription} | enabled=${it.isEnabled} | clickable=${it.isClickable}"});error("UI state missing")}
 private fun click(text:String) {waitFor{find(text)!=null};var n=find(text)!!;while(!n.isClickable)n=n.parent;assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK));instrument.waitForIdleSync()}
 private fun choose(text:String) {
  fun option()=nodes().firstOrNull {it.isVisibleToUser && it.text?.toString()==text && it.viewIdResourceName==null}
  waitFor{option()!=null};var n=option()!!;while(!n.isClickable)n=n.parent
  assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK));instrument.waitForIdleSync()
 }
 private fun screenshot(name:String) {val context=instrument.targetContext;instrument.uiAutomation.takeScreenshot().let{b->java.io.File(context.filesDir,name).outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}}
 private fun scrollTo(tag:String) {
  repeat(10){if(find(tag)!=null)return;nodes().firstOrNull{it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);instrument.waitForIdleSync();Thread.sleep(100)}
  error("Missing $tag")
 }
 private fun fill(tag:String,value:String) {scrollTo(tag);val b=android.os.Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);assertTrue(find(tag)!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b));instrument.waitForIdleSync()}
 @Test fun chooseGitHubOperatorAndQueueWithoutAddressLookup() {
  val saved=AtomicReference<String>()
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{a->val vm=androidx.lifecycle.ViewModelProvider(a)[MainViewModel::class.java];a.setContent{SvitloTheme{QueueFeedPage(vm,null,{},loadGroups={listOf("GPV1.2","GPV7")},loadRegions={fail("Address lookup must be opt-in");emptyList()},save={p,g,_,_,_->saved.set("$p:$g")})}}}
   click("queue-selector");choose("Черга 7");screenshot("official-queue.png");click("Зберегти");waitFor{saved.get()!=null}
   assertEquals("voe/Vinnytsiaoblenerho:GPV7",saved.get())
  }
 }
 @Test fun anAddressInGopchytsiaCanBeAddedWithoutPromin() {
  val saved=AtomicReference<ua.svitlo.app.domain.VoeIncidentAddress>()
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{a->val vm=androidx.lifecycle.ViewModelProvider(a)[MainViewModel::class.java];a.setContent{SvitloTheme{QueueFeedPage(vm,null,{},loadGroups={listOf("GPV1.2")},loadRegions={listOf(ua.svitlo.app.domain.AddressItem("33","Погребищенський р-н"))},save={_,_,_,_,address->saved.set(address)})}}}
   click("queue-selector");choose("Черга 1.2");click("incident-switch")
   scrollTo("incident-region");click("incident-region");choose("Погребищенський р-н")
   fill("incident-city","Гопчиця");fill("incident-street","вулиця Веселівка");fill("incident-house","1")
   screenshot("official-gopchytsia.png");click("Зберегти");waitFor{saved.get()!=null}
   assertEquals("33",saved.get().regionId);assertEquals("Гопчиця",saved.get().city);assertEquals("1",saved.get().house)
  }
 }
 @Test fun failedQueueSourceCannotBeSaved() {
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{a->val vm=androidx.lifecycle.ViewModelProvider(a)[MainViewModel::class.java];a.setContent{SvitloTheme{QueueFeedPage(vm,null,{},loadGroups={throw java.io.IOException("Джерело недоступне")},save={_,_,_,_,_->fail("Must not save")})}}}
   waitFor{find("Джерело недоступне")!=null};var n=find("Зберегти")!!;while(!n.isClickable)n=n.parent;assertFalse(n.isEnabled)
  }
 }
 @Test fun largeTextKeepsSaveVisible() {
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{a->val vm=androidx.lifecycle.ViewModelProvider(a)[MainViewModel::class.java];a.setContent{
    val d=androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density,1.7f)){
     SvitloTheme{QueueFeedPage(vm,null,{},loadGroups={listOf("GPV1.2")},save={_,_,_,_,_->})}
    }
   }}
   waitFor{find("Зберегти")!=null};screenshot("official-large-font.png")
  }
 }
}
