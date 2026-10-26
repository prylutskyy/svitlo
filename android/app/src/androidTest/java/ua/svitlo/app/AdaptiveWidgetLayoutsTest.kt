package ua.svitlo.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ua.svitlo.app.data.VoeParser
import ua.svitlo.app.domain.*
import ua.svitlo.app.widget.SvitloWidgets
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class AdaptiveWidgetLayoutsTest {
    @Test fun realSmallBoundsSelectCompositionsKeepTextInsideAndRespectSurfaceChoices() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val now = Instant.parse("2026-10-26T07:56:40Z")
        val schedule = VoeParser.schedule(instrument.context.assets.open("schedule-kalynova-3-20261026.html").bufferedReader().use { it.readText() },now)
        val address = SavedAddress("a","Калинова, 3",AddressItem("1",""),AddressItem("2",""),AddressItem("3",""))
        val directory = File(context.getExternalFilesDir(null),"adaptive-widgets").apply { mkdirs() }
        fun verify(root: View, message: String) {
            if (root is ViewGroup) for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (child.visibility == View.VISIBLE) {
                    assertTrue("$message: ${child.id} clipped vertically",child.bottom <= root.height-root.paddingBottom+2)
                    assertTrue("$message: ${child.id} clipped horizontally",child.right <= root.width-root.paddingRight+2)
                    verify(child,message)
                }
            }
        }
        instrument.runOnMainSync {
            for ((width,height) in listOf(80 to 56,110 to 110,130 to 117,180 to 160,250 to 110,250 to 180,360 to 240))
                for (manual in listOf(.8f,1f,1.4f)) for (scale in listOf(1f,1.5f)) for (mode in listOf("plan","emergency","unresolved")) {
                    val themed = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = scale })
                    val incident = CurrentOutage("Аварійне відключення",true,now.minusSeconds(3600).toEpochMilli(),
                        now.plusSeconds(if (mode == "unresolved") -1 else 30*3600).toEpochMilli(),"","")
                    val app = AppState(addresses = listOf(address),schedules = listOf(CachedSchedule("a",schedule.copy(currentOutage = incident.takeUnless { mode == "plan" }))))
                    for (surface in listOf("round","square","clear")) {
                        val config = WidgetConfig(999,listOf("a"),contentScale = manual,transparentBackground = surface == "clear",roundedCorners = surface != "square")
                        val root = SvitloWidgets.views(themed,app,config,999,width,height,now).apply(themed,FrameLayout(themed))
                        val density = themed.resources.displayMetrics.density
                        root.measure(View.MeasureSpec.makeMeasureSpec((width*density).toInt(),View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec((height*density).toInt(),View.MeasureSpec.EXACTLY))
                        root.layout(0,0,root.measuredWidth,root.measuredHeight)
                        val message = "$width/$height/$manual/$scale/$mode/$surface"
                        verify(root,message)
                        val time = root.findViewById<TextView>(R.id.focus_time)
                        assertTrue("$message primary time has no line",time.layout.lineCount > 0)
                        assertTrue("$message primary time truncated",time.layout.getLineWidth(0) <= time.width+1)
                        assertTrue("$message primary time ellipsized",time.layout.getEllipsisCount(0) == 0)
                        if (scale == 1f && manual == 1f && mode == "plan") {
                            val image = Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
                            root.draw(Canvas(image))
                            assertEquals("$message corner",if (surface == "square") 255 else 0,Color.alpha(image.getPixel(0,0)))
                            File(directory,"$width-$height-$surface.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
                        }
                    }
                }
        }
        instrument.uiAutomation.executeShellCommand("mkdir -p /sdcard/Download/adaptive-widgets; cp $directory/*.png /sdcard/Download/adaptive-widgets/").use {
            ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
    }
}
