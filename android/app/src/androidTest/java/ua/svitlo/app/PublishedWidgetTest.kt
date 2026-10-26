package ua.svitlo.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
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
class PublishedWidgetTest {
    @Test fun actualKalynovaResponseShowsWholeRangeWithoutClaimingCurrentOutage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrument = InstrumentationRegistry.getInstrumentation()
        val now = Instant.parse("2026-10-26T07:56:40Z")
        val html = instrument.context.assets.open("schedule-kalynova-3-20261026.html").bufferedReader().use { it.readText() }
        val schedule = VoeParser.schedule(html, now)
        val address = SavedAddress("kalynova", "Калинова, 3", AddressItem("520681003", "Вінницькі Хутори"),
            AddressItem("1811", "Калинова"), AddressItem("396498", "3"))
        val app = AppState(addresses = listOf(address), schedules = listOf(CachedSchedule(address.id, schedule)))
        val output = File(context.getExternalFilesDir(null), "published-widget").apply { mkdirs() }
        instrument.runOnMainSync {
            for (dark in listOf(false, true)) for ((width, height) in listOf(180 to 100, 180 to 240, 360 to 240)) {
                val themed = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    fontScale = 1.5f
                })
                val config = WidgetConfig(998, listOf(address.id))
                val root = SvitloWidgets.views(themed, app, config, 998, width, height, now).apply(themed, FrameLayout(themed))
                val density = themed.resources.displayMetrics.density
                root.measure(View.MeasureSpec.makeMeasureSpec((width*density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((height*density).toInt(), View.MeasureSpec.EXACTLY))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                val time = root.findViewById<TextView>(R.id.focus_time)
                assertEquals("16:00–18:00", time.text.toString())
                assertTrue((0 until time.layout.lineCount).none { time.layout.getEllipsisCount(it) > 0 })
                val event = root.findViewById<View>(R.id.focus_event)
                assertTrue(event.contentDescription.contains("Сьогодні"))
                assertFalse(event.contentDescription.contains("Відключення зараз"))
                assertTrue(root.findViewById<View>(R.id.widget_root).bottom <= root.height)
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                File(output, "${if (dark) "dark" else "light"}-$width-$height.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }
}
