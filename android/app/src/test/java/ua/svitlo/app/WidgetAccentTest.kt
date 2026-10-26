package ua.svitlo.app

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import ua.svitlo.app.domain.*

class WidgetAccentTest {
    @Test fun `hex supports opaque black white mixed case and surrounding whitespace`() {
        assertEquals(0xFF000000.toInt(), WidgetAccent.parseHex("#000000"))
        assertEquals(0xFFFFFFFF.toInt(), WidgetAccent.parseHex("ffffff"))
        assertEquals(0xFF12ABEF.toInt(), WidgetAccent.parseHex(" #12abEF "))
        assertEquals("#00000A", WidgetAccent.hex(0xFF00000A.toInt()))
    }

    @Test fun `invalid and ambiguous hex never silently chooses a colour`() {
        listOf("", "#", "#123", "#12345", "#1234567", "#FF123456", "red", "#GGFF00").forEach {
            assertNull(it, WidgetAccent.parseHex(it))
        }
    }

    @Test fun `old snapshots retain opaque default appearance`() {
        val old = Gson().fromJson("""{"widgetId":1,"addressIds":["home"],"monochrome":true}""", WidgetConfig::class.java)
        assertFalse(old.transparentBackground)
        assertNull(old.accentSource)
        assertNull(old.accentColor)
        assertNull(old.transparentText)
        assertTrue(old.monochrome)
    }

    @Test fun `transparency and wallpaper source persist independently for every widget`() {
        val first = WidgetConfig(1, listOf("home"), transparentBackground = true, accentSource = "wallpaper2", transparentText = "light")
        val second = WidgetConfig(2, listOf("work"), accentSource = "custom", accentColor = 0xFF123456.toInt())
        val state = AppState(widgets = listOf(first, second))
        val saved = Gson().fromJson(Gson().toJson(state), AppState::class.java)
        assertEquals(listOf(first, second), saved.widgets)
        assertEquals(second.accentColor, second.copy(monochrome = true).copy(monochrome = false).accentColor)
    }
}
