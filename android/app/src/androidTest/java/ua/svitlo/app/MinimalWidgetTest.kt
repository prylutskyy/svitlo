package ua.svitlo.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ua.svitlo.app.domain.*
import ua.svitlo.app.widget.SvitloWidgets
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class MinimalWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val now = Instant.parse("2026-10-22T18:53:00Z")
    private val addresses = listOf("Дім","Робота","Батьки").mapIndexed { i,title ->
        SavedAddress("minimal-$i",title,AddressItem("1","Вінниця"),AddressItem("2","Вулиця"),AddressItem("3","15")) }
    private fun state(mode: String): AppState {
        val date = now.atZone(KYIV).toLocalDate()
        val days = (0L..1L).map { ScheduleDay("",date.plusDays(it).toString(),listOf(Slot(0,1440,PowerState.NO_SCHEDULED_OUTAGE))) }
        if (mode == "loading") return AppState(addresses = addresses)
        return AppState(addresses = addresses,schedules = addresses.mapIndexed { i,address ->
            val scheduleDays = when {
                mode == "past" -> days.map { it.copy(slots = listOf(Slot(0,1200,PowerState.NO_SCHEDULED_OUTAGE),
                    Slot(1200,1300,PowerState.CONFIRMED_OUTAGE),Slot(1300,1440,PowerState.NO_SCHEDULED_OUTAGE))) }
                    .take(1) // today's event has ended; no future event
                mode == "tomorrow" && i == 1 -> days.mapIndexed { index,day ->
                    if (index == 0) day else day.copy(slots = listOf(Slot(0,960,PowerState.NO_SCHEDULED_OUTAGE),
                        Slot(960,1020,PowerState.POSSIBLE_OUTAGE),Slot(1020,1440,PowerState.NO_SCHEDULED_OUTAGE))) }
                mode !in listOf("quiet","stale","unpublished","unresolved") && i == 1 -> days.mapIndexed { index,day ->
                    if (index == 0) day.copy(slots = listOf(Slot(0,1320,PowerState.NO_SCHEDULED_OUTAGE),Slot(1320,1440,PowerState.CONFIRMED_OUTAGE))) else day }
                mode == "unpublished" -> days.take(1)
                else -> days
            }
            val active = mode !in listOf("quiet","loading","past","stale","unpublished","tomorrow") && i == 2
            CachedSchedule(address.id,Schedule((if (mode in listOf("stale","unresolved")) now.minusSeconds(7200) else now).toEpochMilli(),
                scheduleDays,emptyList(),if (active) CurrentOutage("Аварійне",true,now.minusSeconds(3600).toEpochMilli(),
                    now.plusSeconds(30*3600).toEpochMilli(),"","") else null))
        })
    }
    private fun config(always: Boolean = false) = WidgetConfig(99,addresses.map { it.id },alwaysShowGraphs = always)
    private fun measure(root: View,context: Context,width: Int,height: Int) {
        val d = context.resources.displayMetrics.density
        root.measure(View.MeasureSpec.makeMeasureSpec((width*d).toInt(),View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((height*d).toInt(),View.MeasureSpec.EXACTLY))
        root.layout(0,0,root.measuredWidth,root.measuredHeight)
    }
    private fun capture(root: View) = Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
    private fun invisible(root: View) {
        assertEquals(View.GONE,root.findViewById<View>(R.id.widget_root).visibility)
        val image = capture(root)
        for (y in 0 until image.height) for (x in 0 until image.width) assertEquals(0,Color.alpha(image.getPixel(x,y)))
    }
    @Test fun quietLoadingStaleUnpublishedAndPastStatesHaveNoVisiblePixels() {
        instrument.runOnMainSync {
            for (mode in listOf("quiet","loading","stale","unpublished","past")) {
                val root = SvitloWidgets.views(context,state(mode),config(),99,360,300,now).apply(context,FrameLayout(context))
                measure(root,context,360,300)
                invisible(root)
                assertTrue(root.findViewById<View>(R.id.widget_host).contentDescription.contains("Немає відомих"))
            }
        }
    }
    @Test fun repeatedHostReapplyHidesWakesAndRecoversConfigurationWithoutStaleFrames() {
        instrument.runOnMainSync {
            val root = SvitloWidgets.views(context,state("quiet"),config(),99,360,300,now).apply(context,FrameLayout(context))
            measure(root,context,360,300); invisible(root)
            for (mode in listOf("active","quiet","tomorrow","loading","unresolved","quiet")) {
                SvitloWidgets.views(context,state(mode),config(),99,360,300,now).reapply(context,root)
                measure(root,context,360,300)
                if (mode in listOf("quiet","loading")) invisible(root) else {
                    assertEquals(View.VISIBLE,root.findViewById<View>(R.id.widget_root).visibility)
                    assertTrue(root.findViewById<View>(R.id.focus_event).height > 0)
                    assertEquals(when (mode) { "active" -> "Аварійне зараз"; "tomorrow" -> "Наступна подія"; else -> "Стан уточнюється" },
                        root.findViewById<TextView>(R.id.focus_state).text.toString())
                }
            }
            SvitloWidgets.views(context,state("quiet"),null,99,360,300,now).reapply(context,root)
            measure(root,context,360,300)
            assertEquals(View.VISIBLE,root.findViewById<View>(R.id.widget_root).visibility)
            assertEquals(View.VISIBLE,root.findViewById<View>(R.id.widget_empty).visibility)
        }
    }
    @Test fun explicitAlwaysVisibleOptionStillShowsKnownCalmData() {
        instrument.runOnMainSync {
            val root = SvitloWidgets.views(context,state("quiet"),config(true),99,360,340,now).apply(context,FrameLayout(context))
            measure(root,context,360,340)
            assertEquals(View.VISIBLE,root.findViewById<View>(R.id.widget_root).visibility)
            assertTrue(root.findViewById<ImageView>(R.id.widget_chart).height > 0)
        }
    }
    @Test fun renderMinimalFixturesAcrossAppearancesAndUpcomingEventDistances() {
        val directory = File(context.getExternalFilesDir(null),"minimal-widget").apply { mkdirs() }
        shell("mkdir -p /sdcard/Download/minimal-widget")
        instrument.runOnMainSync {
            for (mode in listOf("quiet","active","dark","mono","transparent","narrow","large","tomorrow","unresolved")) {
                val themed = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (mode in listOf("dark","mono","transparent")) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    fontScale = if (mode == "large") 1.5f else 1f
                })
                val width = if (mode == "narrow") 180 else 360
                val height = if (mode == "large") 500 else 300
                val appearance = config().copy(monochrome = mode == "mono",transparentBackground = mode == "transparent",transparentText = "light",
                    contentScale = if (mode == "large") 1.4f else 1f)
                val root = SvitloWidgets.views(themed,state(mode),appearance,99,width,height,now).apply(themed,FrameLayout(themed))
                measure(root,themed,width,height)
                assertTrue(root.findViewById<View>(R.id.widget_root).bottom <= root.height)
                val bitmap = capture(root)
                if (mode == "quiet") invisible(root)
                if (mode == "tomorrow") {
                    assertEquals("16:00",root.findViewById<TextView>(R.id.focus_time).text.toString())
                    assertEquals(View.GONE,root.findViewById<View>(R.id.widget_transition).visibility)
                }
                if (mode == "transparent") {
                    val backdrop = Bitmap.createBitmap(bitmap.width,bitmap.height,Bitmap.Config.ARGB_8888)
                    Canvas(backdrop).apply { drawColor(Color.rgb(35,46,60)); drawBitmap(bitmap,0f,0f,null) }
                    File(directory,"$mode.png").outputStream().use { backdrop.compress(Bitmap.CompressFormat.PNG,100,it) }
                } else File(directory,"$mode.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                shell("cp $directory/$mode.png /sdcard/Download/minimal-widget/$mode.png")
            }
        }
    }
    private fun shell(command: String) = instrument.uiAutomation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
    }
}
