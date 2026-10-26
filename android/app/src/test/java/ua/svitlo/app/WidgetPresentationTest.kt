package ua.svitlo.app

import org.junit.Assert.*
import org.junit.Test
import ua.svitlo.app.domain.*
import java.time.Instant

class WidgetPresentationTest {
    private val now = Instant.parse("2026-10-22T18:00:00Z")
    private val window = notificationWindow(now)
    private val address = SavedAddress("1","Дім",AddressItem("1","Місто"),AddressItem("2","Вулиця"),AddressItem("3","15"))
    private fun row(power: PowerState,status: String) = ChartRow(address,status,power,
        listOf(ChartSpan(window.start,window.end,power)),emergency = power == PowerState.EMERGENCY_OUTAGE)
    private fun chart(vararg rows: ChartRow) = NotificationChart(window.start,window.end,now,rows.toList(),rows.size,rows.map { it.address.title })

    @Test fun `headline leads with current emergency before future events`() {
        val future = row(PowerState.NO_SCHEDULED_OUTAGE,"Відключення о 22:00").copy(nextOutageAt = now.plusSeconds(3600))
        val emergency = row(PowerState.EMERGENCY_OUTAGE,"Аварійне · ще ≈ 6 год")
        assertEquals("Аварійне зараз",WidgetPresentation.headline(chart(future,emergency)))
        assertEquals(emergency,WidgetPresentation.focus(chart(future,emergency)))
    }
    @Test fun `schedule does not claim an observed outage and possibility remains qualified`() {
        val planned = row(PowerState.CONFIRMED_OUTAGE,"За графіком до 23:00")
        assertEquals("За графіком зараз",WidgetPresentation.headline(chart(planned)))
        val possible = row(PowerState.POSSIBLE_OUTAGE,"Можливе до 23:00")
        assertEquals("За графіком зараз",WidgetPresentation.headline(chart(possible)))
        val reported = row(PowerState.CONFIRMED_OUTAGE,"Відключення · ще ≈ 2 год")
        assertEquals("Відключення зараз",WidgetPresentation.headline(chart(reported)))
        assertEquals(reported,WidgetPresentation.focus(chart(planned,reported)))
        assertEquals("За графіком",WidgetPresentation.headline(chart(planned),compact = true))
    }
    @Test fun `unknown unresolved incident is not promoted to a live emergency`() {
        val unresolved = row(PowerState.UNKNOWN,"Аварійне · дані застаріли").copy(emergency = true)
        assertEquals("Стан уточнюється",WidgetPresentation.headline(chart(unresolved)))
        assertEquals(unresolved.status,WidgetPresentation.status(unresolved,chart(unresolved)))
    }
    @Test fun `future headline selects nearest event and indicates tomorrow`() {
        val tomorrow = row(PowerState.NO_SCHEDULED_OUTAGE,"").copy(nextOutageAt = now.plusSeconds(19*3600),nextOutageState = PowerState.POSSIBLE_OUTAGE)
        assertEquals("Завтра · 16:00",WidgetPresentation.headline(chart(tomorrow)))
        val near = tomorrow.copy(nextOutageAt = now.plusSeconds(3600))
        assertEquals("Далі · 22:00",WidgetPresentation.headline(chart(tomorrow,near)))
        assertEquals(near,WidgetPresentation.focus(chart(tomorrow,near)))
    }
    @Test fun `short statuses preserve remaining time approximation and uncertainty`() {
        val outage = row(PowerState.EMERGENCY_OUTAGE,"Аварійне · ще ≈ 1 д 6 год")
        assertEquals("Ще ≈ 1 д 6 год",WidgetPresentation.status(outage,chart(outage)))
        assertEquals("Аварійне · ще ≈ 1 д 6 год",outage.status)
        assertTrue(chart(outage).description.contains(outage.status))
        val unknown = outage.copy(current = PowerState.UNKNOWN,status = "Аварійне · час уточнюється")
        assertEquals(unknown.status,WidgetPresentation.status(unknown,chart(unknown)))
    }
    @Test fun `possible future is never shortened into a confirmed outage`() {
        val possible = row(PowerState.NO_SCHEDULED_OUTAGE,"Можливе о 22:00")
        assertEquals("Можливе о 22:00",WidgetPresentation.status(possible,chart(possible)))
        val planned = possible.copy(status = "Відключення о 22:00")
        assertEquals("З 22:00",WidgetPresentation.status(planned,chart(planned)))
    }
}
