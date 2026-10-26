package ua.svitlo.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
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
import ua.svitlo.app.widget.*
import java.time.Instant
import java.io.File

@RunWith(AndroidJUnit4::class)
class WidgetPresetsTest {
    @Test fun newPresetEventLayoutsKeepTextWithinBounds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val now = Instant.parse("2026-10-26T07:00:00Z")
        val addresses = listOf("Дім","Батьки","Робота").mapIndexed { i,title -> SavedAddress("$i",title,AddressItem("1","Вінниця"),AddressItem("2","Вулиця"),AddressItem("3","3")) }
        val files = File(context.filesDir,"widget-presets").apply { mkdirs() }
        fun verify(view: View, message: String) {
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child.visibility == View.VISIBLE) {
                    assertTrue("$message clipped height",child.bottom <= view.height-view.paddingBottom+2)
                    assertTrue("$message clipped width",child.right <= view.width-view.paddingRight+2)
                    verify(child,message)
                }
            }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (provider in WidgetFormats.components(context)) for (manualScale in listOf(.8f,1f,1.4f)) for (font in listOf(1f,1.5f)) for (mode in listOf("plan","emergency","unresolved")) {
                val themed = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = font })
                val style = WidgetFormats.style(provider)
                val (w,h) = WidgetFormats.defaultSize(provider)
                val state = AppState(addresses = addresses,schedules = addresses.mapIndexed { index,address ->
                    val start = 960-index*120
                    val slots = listOf(Slot(0,start,PowerState.NO_SCHEDULED_OUTAGE),Slot(start,start+120,PowerState.SCHEDULED_OUTAGE),Slot(start+120,1440,PowerState.NO_SCHEDULED_OUTAGE))
                    val incident = CurrentOutage("Аварійне відключення",true,now.minusSeconds(3600).toEpochMilli(),now.plusSeconds(if (mode == "unresolved") -1 else 30*3600).toEpochMilli(),"","")
                    CachedSchedule(address.id,Schedule(now.toEpochMilli(),listOf(ScheduleDay("","2026-10-26",slots)),emptyList(),incident.takeUnless { mode == "plan" }))
                })
                for (surface in listOf("round","square","clear")) {
                    val config = WidgetConfig(17,addresses.map { it.id },contentScale = manualScale,hideSettings = manualScale != 1f,transparentBackground = surface == "clear",roundedCorners = surface != "square").forStyle(state,now,style)
                    val root = SvitloWidgets.views(themed,state,config,17,w,h,now,interactive = false).apply(themed,FrameLayout(themed))
                    val d = themed.resources.displayMetrics.density
                    root.measure(View.MeasureSpec.makeMeasureSpec((w*d).toInt(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec((h*d).toInt(),View.MeasureSpec.EXACTLY))
                    root.layout(0,0,root.measuredWidth,root.measuredHeight)
                    verify(root,"$style/$manualScale/$font/$mode/$surface")
                    val menuId = if (style == WidgetStyle.TIMELINE) R.id.widget_configure else R.id.focus_configure
                    assertEquals(if (manualScale != 1f || style == WidgetStyle.SHORT) View.GONE else View.VISIBLE,root.findViewById<View>(menuId).visibility)
                    if (style != WidgetStyle.TIMELINE) {
                        val time = root.findViewById<TextView>(R.id.focus_time)
                        assertTrue(time.text.isNotEmpty())
                        val expectedIcon = when (mode) {
                            "emergency" -> "Зараз повідомлене відключення"
                            "unresolved" -> "Стан уточнюється"
                            else -> "Зараз без відключень за графіком"
                        }
                        assertEquals(expectedIcon,root.findViewById<View>(R.id.focus_power_icon).contentDescription.toString())
                        if (style == WidgetStyle.CARD && mode == "unresolved")
                            assertEquals("Прогноз минув",root.findViewById<TextView>(R.id.focus_caption).text.toString())
                        assertTrue(time.layout.getLineWidth(0) <= time.width+1)
                    }
                    if (font == 1f && mode == "plan" && surface == "round" && manualScale != 1f) {
                        val image = Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
                        root.draw(Canvas(image))
                        File(files,"${style.name.lowercase()}-${(manualScale*100).toInt()}-hidden.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
                    }
                    if (font == 1f && manualScale == 1f) {
                        val image = Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
                        root.draw(Canvas(image))
                        File(files,"${style.name.lowercase()}-$mode-$surface.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
                    }
                }
            }
        }
    }
    @Test fun pickerPresetsInflateAtTheirOwnSizesAndRespectQuietVisibility() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val now = Instant.parse("2026-10-26T07:00:00Z")
        val addresses = (1..3).map { SavedAddress("$it","Локація $it",AddressItem("1","Вінниця"),AddressItem("2","Вулиця"),AddressItem("3","3")) }
        val state = AppState(addresses = addresses,schedules = addresses.map {
            CachedSchedule(it.id,Schedule(now.toEpochMilli(),listOf(ScheduleDay("","2026-10-26",listOf(Slot(0,1440,PowerState.NO_SCHEDULED_OUTAGE)))),emptyList()))
        })
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (provider in WidgetFormats.components(context)) for (font in listOf(1f,1.5f)) {
                val themed = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = font })
                val style = WidgetFormats.style(provider)
                val (w,h) = WidgetFormats.defaultSize(provider)
                val config = WidgetConfig(17,addresses.map { it.id }).forStyle(state,now,style)
                val rv = SvitloWidgets.views(themed,state,config,17,w,h,now,interactive = false)
                val root = rv.apply(themed,FrameLayout(themed))
                val d = themed.resources.displayMetrics.density
                root.measure(View.MeasureSpec.makeMeasureSpec((w*d).toInt(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec((h*d).toInt(),View.MeasureSpec.EXACTLY))
                root.layout(0,0,root.measuredWidth,root.measuredHeight)
                assertEquals(View.VISIBLE,root.findViewById<View>(R.id.widget_root).visibility)
                if (style == WidgetStyle.TIMELINE) {
                    assertNotNull(root.findViewById<ImageView>(R.id.widget_chart).drawable)
                    assertEquals(3,config.addressIds.size)
                } else {
                    assertEquals("Локація 1",root.findViewById<TextView>(R.id.focus_address).text.toString())
                    assertEquals("✓",root.findViewById<TextView>(R.id.focus_time).text.toString())
                    assertEquals("Зараз без відключень за графіком",root.findViewById<View>(R.id.focus_power_icon).contentDescription.toString())
                    assertEquals(View.VISIBLE,root.findViewById<View>(R.id.focus_address).visibility)
                }
                SvitloWidgets.views(themed,state,config.copy(hideWhenQuiet = true),17,w,h,now,interactive = false).reapply(themed,root)
                assertEquals(View.GONE,root.findViewById<View>(R.id.widget_root).visibility)
                rv.reapply(themed,root)
                assertEquals(View.VISIBLE,root.findViewById<View>(R.id.widget_root).visibility)
            }
        }
    }
}
