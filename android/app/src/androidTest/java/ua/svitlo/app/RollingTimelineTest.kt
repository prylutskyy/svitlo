package ua.svitlo.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ua.svitlo.app.background.NotificationTimeline
import ua.svitlo.app.domain.*
import java.time.Instant

/** Synthetic, offline visual fixtures. PNGs document rendering, not real VOE conditions. */
@RunWith(AndroidJUnit4::class)
class RollingTimelineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun state(now: Instant): AppState {
        val homes = listOf("Дім", "Батьки", "Робота").mapIndexed { i, name ->
            SavedAddress("rolling-$i", name, AddressItem("1","Вінниця"), AddressItem("2","Вулиця"), AddressItem("3","9"))
        }
        fun schedule(offset: Long, possible: Boolean, tomorrow: Boolean): Schedule {
            val a = now.plusSeconds(offset)
            val b = a.plusSeconds(7200)
            val days = (0L..if (tomorrow) 1L else 0L).map { day ->
                val date = now.atZone(KYIV).toLocalDate().plusDays(day)
                val cuts = sortedSetOf(0,1440)
                listOf(a,b).map { it.atZone(KYIV) }.filter { it.toLocalDate() == date }.forEach { cuts += it.hour*60+it.minute }
                ScheduleDay("",date.toString(), cuts.toList().zipWithNext().map { (start,end) ->
                    val at = date.atStartOfDay().plusMinutes(start.toLong()).atZone(KYIV).toInstant()
                    Slot(start,end,if (at >= a && at < b) {
                        if (possible) PowerState.POSSIBLE_OUTAGE else PowerState.CONFIRMED_OUTAGE
                    } else PowerState.NO_SCHEDULED_OUTAGE)
                })
            }
            return Schedule(now.toEpochMilli(), days, emptyList())
        }
        return AppState(addresses = homes, currentAddressId = homes.first().id, schedules = listOf(
            CachedSchedule(homes[0].id,schedule(1800,false,false)),
            CachedSchedule(homes[1].id,schedule(0,false,true).copy(currentOutage = CurrentOutage("",true,
                now.minusSeconds(7200).toEpochMilli(),now.plusSeconds(30*3600).toEpochMilli(),"",""))),
            CachedSchedule(homes[2].id,schedule(7200,true,true))))
    }
    private fun save(name: String, chart: NotificationChart, dark: Boolean, mono: Boolean = false, width: Float = 320f, scale: Float = 1f) {
        val image = NotificationTimeline.draw(chart,dark,width,scale,mono,widget = true)
        val preview = Bitmap.createBitmap(image.width,image.height,Bitmap.Config.ARGB_8888)
        Canvas(preview).apply { drawColor(if (mono) Color.BLACK else if (dark) Color.rgb(28,30,34) else Color.rgb(250,249,246)); drawBitmap(image,0f,0f,null) }
        if (mono) for (y in 0 until image.height) for (x in 0 until image.width) {
            val color = image.getPixel(x,y)
            assertEquals(Color.red(color),Color.green(color)); assertEquals(Color.green(color),Color.blue(color))
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,"svitlo-rolling-$name.png")
            put(MediaStore.Images.Media.MIME_TYPE,"image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/Svitlo/Rolling")
        }))
        context.contentResolver.openOutputStream(uri)!!.use { assertTrue(preview.compress(Bitmap.CompressFormat.PNG,100,it)) }
        image.recycle(); preview.recycle()
    }
    @Test fun sharedClockHandlesMidnightAndMonochrome() {
        val now = Instant.parse("2026-10-19T08:53:00Z")
        val chart = NotificationChart.create(state(now),now)
        save("light",chart,false)
        save("dark",chart,true)
        save("mono",chart,true,true)
        listOf("2026-10-19T18:53:00Z","2026-10-19T20:53:00Z","2026-10-19T21:07:00Z").forEachIndexed { i, value ->
            val at = Instant.parse(value)
            save("midnight-$i",NotificationChart.create(state(at),at),false)
        }
        for (scale in listOf(1f,1.05f,1.06f,1.15f,1.25f,1.4f,1.5f)) {
            val model = NotificationChart.create(state(now).copy(addresses = state(now).addresses + state(now).addresses.map { it.copy(id = it.id+"extra") }),now,NotificationTimeline.maxRows(scale))
            val image = NotificationTimeline.draw(model,false,260f,scale)
            assertTrue("scale=$scale height=${image.height}",image.height <= 440)
            image.recycle()
        }
        save("large",NotificationChart.create(state(now),now,NotificationTimeline.maxRows(1.5f)),false,width = 260f,scale = 1.5f)
        save("narrow",NotificationChart.create(state(now),now,1),true,true,128f,1.5f)
    }
}
