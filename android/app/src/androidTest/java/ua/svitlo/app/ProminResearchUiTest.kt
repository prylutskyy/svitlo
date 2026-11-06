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
class ProminResearchUiTest {
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
 private fun cfg(id:String)=ProminConfig(ProminCities.get(id),listOf("1.2","7"),emptyMap(),id=="vinnytsia",id=="vinnytsia",false)
 private val street=ProminStreet("97577531","Хмельницьке шосе","Вінниця","1")
 @Test fun chooseCityAndQueueThenSave() {
  val saved=AtomicReference<String>()
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{activity->val vm=androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java];activity.setContent{SvitloTheme{ProminSourcePage(vm,null,{},loadConfig={cfg(it)},loadStreets={emptyList()},save={s,_->saved.set("${s.city}:${s.group}")})}}}
   click("provider-selector")
   waitFor { nodes().any { it.isScrollable } }; screenshot("promin-cities.png")
   var scrolls=0
   while(find("Харків")==null && scrolls++<10) {
    nodes().first{it.isScrollable}.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);instrument.waitForIdleSync();Thread.sleep(120)
   }
   click("Харків");click("queue-selector");click("Черга 7")
   screenshot("promin-queue.png");click("Зберегти");waitFor{saved.get()!=null};assertEquals("kharkiv:7",saved.get())
  }
 }
 @Test fun chooseExactAddressWithoutRoundingTheHouseNumber() {
  val saved=AtomicReference<ProminSelection>()
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{activity->val vm=androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java];activity.setContent{SvitloTheme{ProminSourcePage(vm,null,{},loadConfig={cfg(it)},loadStreets={listOf(street)},loadHouses={_,_->listOf(ProminHouse("81/2","",53739067,false))},save={s,_->saved.set(s)})}}}
   click("town-selector");choose("Вінниця");waitFor{find("town-selector")?.text?.toString()?.contains("Вінниця")==true}
   click("street-selector");choose("Хмельницьке шосе");waitFor{find("street-selector")?.text?.toString()?.contains("Хмельницьке")==true}
   click("house-selector");choose("81/2");waitFor{find("house-selector")?.text?.toString()?.contains("81/2")==true}
   screenshot("promin-address.png");click("Зберегти");waitFor{saved.get()!=null}
   assertEquals("81/2",saved.get().house?.number);assertEquals("97577531",saved.get().street?.id)
  }
 }
 @Test fun failedSourceDisplaysErrorAndPreventsSaving() {
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{activity->val vm=androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java];activity.setContent{SvitloTheme{ProminSourcePage(vm,null,{},loadConfig={throw java.io.IOException("Промінь недоступний")},save={_,_->fail("Must not save")})}}}
   waitFor{find("Промінь недоступний")!=null};var n=find("Зберегти")!!;while(!n.isClickable && n.parent!=null)n=n.parent;assertFalse(n.isEnabled);screenshot("promin-error.png")
  }
 }
 @Test fun largeTextKeepsSaveVisibleWhileContentScrolls() {
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{activity->val vm=androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java];activity.setContent{
    val density=androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,1.7f)) {
     SvitloTheme{ProminSourcePage(vm,null,{},loadConfig={cfg(it)},loadStreets={listOf(street)},save={_,_->})}
    }
   }}
   waitFor{nodes().any{it.isScrollable}}
   assertNotNull(find("Зберегти"))
   var tries=0
   while(find("place-name")==null && tries++<5){nodes().first{it.isScrollable}.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);instrument.waitForIdleSync()}
   assertNotNull(find("place-name"));assertNotNull(find("Зберегти"));screenshot("promin-large-font.png")
  }
 }
}
