package ua.svitlo.app

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import ua.svitlo.app.domain.*
import java.time.Instant

class WidgetPolicyTest {
    private val now = Instant.parse("2026-10-18T10:07:00Z")
    private fun address(id: String, selected: Boolean) = SavedAddress(id, id, AddressItem("1","Вінниця"), AddressItem("2","Вулиця"), AddressItem("3","1"), selected)
    private val state = AppState(addresses = listOf(address("a", false), address("b", true), address("c", false)),
        notificationsEnabled = false, widgets = listOf(WidgetConfig(10, listOf("a")), WidgetConfig(11, listOf("c"))))

    @Test fun `widget selections remain independent of notification selections`() {
        assertTrue(state.active(now))
        assertEquals(listOf("a", "c"), state.monitoredAddresses(now).map { it.id })
        assertEquals(listOf("b"), state.addresses.filter { it.selected }.map { it.id })
        assertEquals(listOf("a"), state.widgets!![0].chart(state, now).rows.map { it.address.id })
        assertEquals(listOf("c"), state.widgets[1].chart(state, now).rows.map { it.address.id })
    }
    @Test fun `network targets are deduplicated across widgets and notifications`() {
        val shared = state.copy(notificationsEnabled = true, widgets = state.widgets!! + WidgetConfig(12, listOf("a", "b")))
        assertEquals(listOf("a", "b", "c"), shared.monitoredAddresses(now).map { it.id })
    }
    @Test fun `removing last widget stops widget only tracking`() {
        assertFalse(state.copy(widgets = emptyList()).active(now))
        assertTrue(state.copy(widgets = state.widgets!!.drop(1)).active(now))
        assertFalse(state.copy(addresses = emptyList()).active(now))
    }
    @Test fun `widget marker shares bounded alarm even when graphs are off`() {
        val schedule = Schedule(now.toEpochMilli(), listOf(ScheduleDay("Сьогодні", "2026-10-18", listOf(Slot(0,1440,PowerState.NO_SCHEDULED_OUTAGE)))), emptyList())
        assertEquals(Instant.parse("2026-10-18T10:15:00Z"), EnergyPolicy.nextBoundary(state.copy(schedules = listOf(CachedSchedule("a", schedule))), now))
    }
    @Test fun `old snapshots need no widget migration and new selections survive reload`() {
        val gson = Gson()
        val old = gson.fromJson("{\"addresses\":[],\"notificationsEnabled\":false}", AppState::class.java)
        assertTrue(old.widgetAddressIds().isEmpty())
        val copy = gson.fromJson(gson.toJson(state), AppState::class.java)
        assertEquals(state.widgets, copy.widgets)
        assertEquals(state.monitoredAddresses(now), copy.monitoredAddresses(now))
    }
    @Test fun `small widget reports hidden addresses instead of silently losing them`() {
        val config = WidgetConfig(10, listOf("a","b","c"))
        val chart = config.chart(state, now, WidgetConfig.maxRows(194, 1f))
        assertEquals(1, chart.rows.size)
        assertEquals(2, chart.hidden)
        assertEquals(3, chart.total)
        assertTrue(WidgetConfig.recommendedHeight(3) > WidgetConfig.recommendedHeight(1))
        assertEquals(3, WidgetConfig.maxRows(WidgetConfig.recommendedHeight(3),1f, total = 3))
    }

    @Test fun `widget order is saved and low height respects that order`() {
        val config = WidgetConfig(10, listOf("c", "a", "b"))
        val chart = config.chart(state, now, maxRows = 1)
        assertEquals(listOf("c"), chart.rows.map { it.address.id })
        assertEquals(2, chart.hidden)
        assertEquals(listOf("c", "a", "b"), config.chart(state, now).rows.map { it.address.id })
    }

    @Test fun `hiding widget header makes its height available to chart rows`() {
        val height = 180
        assertTrue(WidgetConfig.maxRows(height, 1f, headerVisible = false) > WidgetConfig.maxRows(height, 1f, headerVisible = true))
        assertEquals(2, WidgetConfig.maxRows(height, 1f, headerVisible = false))
        assertEquals(3, WidgetConfig.maxRows(WidgetConfig.recommendedHeight(3, 1f, headerVisible = false), 1f, false, total = 3))
    }

    @Test fun `five selected addresses preserve order without notification three row limit`() {
        val five = state.copy(addresses = (1..5).map { address("$it", false) })
        val config = WidgetConfig(10, listOf("5", "2", "4", "1", "3"))
        assertEquals(config.addressIds, config.chart(five, now).rows.map { it.address.id })
        assertEquals(0, config.chart(five, now).hidden)
        assertEquals(config.addressIds.take(4), config.chart(five, now, 4).rows.map { it.address.id })
    }

    @Test fun `row capacity matches actual chart geometry for every scale density and header`() {
        for (scale in listOf(0.8f, 1f, 1.4f, 2.1f)) for (density in 0..2) for (header in listOf(false, true)) {
            val height = WidgetConfig.recommendedHeight(5, scale, header, density)
            assertEquals(5, WidgetConfig.maxRows(height, scale, header, density, 5))
            val below = height - 2
            assertTrue(WidgetConfig.maxRows(below, scale, header, density, 5) < 5)
        }
        assertTrue(WidgetConfig.maxRows(250, 1f, false, 0, 8) > WidgetConfig.maxRows(250, 1f, false, 2, 8))
    }

    @Test fun `legacy widget scale remains normal and scale persists per widget`() {
        val gson = Gson()
        val old = gson.fromJson("""{"widgetId":10,"addressIds":["a"]}""", WidgetConfig::class.java)
        assertEquals(1f, old.sizeMultiplier, 0f)
        assertEquals(1, old.densityMode)
        val first = old.copy(contentScale = 1.4f)
        val saved = gson.fromJson(gson.toJson(state.copy(widgets = listOf(first, old.copy(widgetId = 11)))), AppState::class.java)
        assertEquals(1.4f, saved.widgets!![0].sizeMultiplier, 0.001f)
        assertEquals(1f, saved.widgets!![1].sizeMultiplier, 0f)
        val scale = first.effectiveFontScale(1.5f)
        assertEquals(2.1f, scale, 0.001f)
        assertEquals(3, WidgetConfig.maxRows(WidgetConfig.recommendedHeight(3,scale),scale, total = 3))
        assertTrue(WidgetConfig.maxRows(300,old.copy(contentScale = 0.8f).effectiveFontScale(1f)) > WidgetConfig.maxRows(300,scale))
    }
}
