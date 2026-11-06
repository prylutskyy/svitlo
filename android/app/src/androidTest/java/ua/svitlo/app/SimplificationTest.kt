package ua.svitlo.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ua.svitlo.app.domain.*
import ua.svitlo.app.ui.*
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class SimplificationTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val ui=instrument.uiAutomation
    private fun nodes():List<AccessibilityNodeInfo> {
        ui.clearCache()
        fun collect(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null)emptyList() else listOf(n)+(0 until n.childCount).flatMap{collect(n.getChild(it))}
        return collect(ui.rootInActiveWindow).filter{it.isVisibleToUser}
    }
    private fun node(text:String)=nodes().firstOrNull{it.text?.toString()==text || it.contentDescription?.toString()==text || it.viewIdResourceName==text}
    private fun waitFor(check:()->Boolean) {repeat(100){if(check())return;Thread.sleep(60)};error("Missing UI: ${nodes().map{it.text ?: it.contentDescription}}")}
    private fun click(text:String) {waitFor{node(text)!=null};var n=node(text)!!;while(!n.isClickable)n=n.parent;assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK));instrument.waitForIdleSync()}
    private fun scrollTo(text:String) {
        repeat(12) {if(node(text)!=null)return;nodes().firstOrNull{it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);Thread.sleep(200)}
        error("Missing $text")
    }
    private fun screenshot(name:String) {
        instrument.waitForIdleSync();Thread.sleep(300)
        val dir=File(instrument.targetContext.filesDir,"simplification").apply{mkdirs()}
        requireNotNull(ui.takeScreenshot()).let{b->File(dir,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
    }
    @Test fun plannedHistoryShowsDurationAndCollapsedJournal() {
        val context=instrument.targetContext
        val now=Instant.parse("2026-11-06T10:00:00Z")
        val home=SavedAddress("history-ui-05","Черга 1.2",AddressItem("feed","Вінницька · Вінницяобленерго"),AddressItem("queue","Черга"),AddressItem("GPV3.2","3.2"),feedId="voe/Vinnytsiaoblenerho",feedGroup="GPV3.2")
        val before=now.minusSeconds(1800)
        val graph=Schedule(before.toEpochMilli(),listOf(ScheduleDay("","2026-11-06",listOf(Slot(0,720,PowerState.SCHEDULED_OUTAGE),Slot(720,1440,PowerState.NO_SCHEDULED_OUTAGE)))),emptyList(),sourceUrl="https://raw.githubusercontent.com/example/plan")
        context.app.history.record(home,null,graph)
        val latest=graph.copy(fetchedAt=now.toEpochMilli())
        context.app.history.record(home,graph,latest)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity{a->a.setContent{SvitloTheme(dark=true){HistoryPage(home,latest,now,{})}}}
            waitFor{node("30 хв")!=null}
            assertNotNull(node("Черга 3.2"));assertNull(node("Черга 1.2"))
            assertNull(node("Як рахуємо"));assertNull(node("Журнал подій ВОЕ"))
            scrollTo("Сьогодні");assertNull(node("Планове"));screenshot("history-collapsed")
            click("Сьогодні");waitFor{node("Планове")!=null};screenshot("history-expanded")
            scenario.onActivity{a->a.setContent{SvitloTheme(dark=true){GuidePage(AppState(),{},{})}}}
            waitFor{node("Позначки")!=null};assertNull(node("Дані й оновлення"));screenshot("about")
        }
    }
    @Test fun wallpaperPaletteAndCustomColorAreAvailableAndPersist() {
        val context=instrument.targetContext
        val prefs=AppearancePreferences(context)
        val saved=java.util.HashMap(prefs.preferences.all)
        try {
            prefs.theme(AppThemeMode.DARK);prefs.accent(AppAccent.GREEN)
            assertEquals(39,wallpaperPalette(context).size)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity{a->a.setContent{SvitloTheme{AppearancePage({})}}}
                waitFor{node("Кольори шпалер")!=null};screenshot("appearance-wallpaper")
                click("Автоматично");assertEquals(AppAccent.WALLPAPER,prefs.read().accent)
                val tone=wallpaperPalette(context)[6]
                click("Колір шпалер #${"%06X".format(tone and 0xFFFFFF)}")
                assertEquals(tone,prefs.read().wallpaperColor)
                scrollTo("HEX")
                val args=android.os.Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"FF3366")}
                assertTrue(nodes().first{it.isEditable}.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args));instrument.waitForIdleSync()
                click("Обрати");waitFor{node("Обрано")!=null && prefs.read().accent==AppAccent.CUSTOM}
                assertEquals(0xFFFF3366,prefs.read().customColor)
                screenshot("appearance-custom")
                scenario.recreate();scenario.onActivity{a->a.setContent{SvitloTheme{AppearancePage({})}}};waitFor{node("Налаштування")!=null}
                assertEquals(0xFFFF3366,AppearancePreferences(context).read().customColor)
            }
        } finally {
            val edit=prefs.preferences.edit().clear()
            saved.forEach{(k,v)->when(v){is String->edit.putString(k,v);is Long->edit.putLong(k,v)}}
            edit.commit()
        }
    }
    @Test fun restorationNotificationUsesPlanWordingAndIsPostedOnce() {
        val context=instrument.targetContext
        if(Build.VERSION.SDK_INT>=33)ui.grantRuntimePermission(context.packageName,Manifest.permission.POST_NOTIFICATIONS)
        val manager=context.getSystemService(NotificationManager::class.java)
        val home=SavedAddress("restore-ui","Дім",AddressItem("1","Вінниця"),AddressItem("2","Шосе"),AddressItem("3","81/2"))
        val key="restore-ui:${System.nanoTime()}:plan-ended"
        val state=AppState(addresses=listOf(home),currentAddressId=home.id,eventMonitoring=true,restorationNotifiedKey=key)
        val method=SvitloApp::class.java.getDeclaredMethod("postRestoration",AppState::class.java).apply{isAccessible=true}
        try {
            method.invoke(context.app,state)
            waitFor{manager.activeNotifications.any{it.tag=="restoration"}}
            val first=manager.activeNotifications.single{it.tag=="restoration"}
            assertEquals("Світло за графіком",first.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals("Дім",first.notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
            assertTrue(first.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("інтервал завершився"))
            Thread.sleep(100);method.invoke(context.app,state)
            assertEquals(first.postTime,manager.activeNotifications.single{it.tag=="restoration"}.postTime)
            fun shell(command:String)=ui.executeShellCommand(command).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
            shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard");shell("cmd statusbar expand-notifications")
            waitFor{node("Світло за графіком")!=null};screenshot("restoration")
        } finally {manager.cancel("restoration",3);ui.executeShellCommand("cmd statusbar collapse").close()}
    }
}
