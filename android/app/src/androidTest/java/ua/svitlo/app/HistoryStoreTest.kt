package ua.svitlo.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ua.svitlo.app.data.HistoryStore
import ua.svitlo.app.domain.*
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class HistoryStoreTest {
    private val at = Instant.parse("2026-10-19T10:00:00Z").toEpochMilli()
    private val address = SavedAddress("archive-home","Дім",AddressItem("1","Вінниця"),AddressItem("2","Вулиця"),AddressItem("3","9"))
    private fun graph(time: Long, incident: CurrentOutage? = null) = Schedule(time,listOf(
        ScheduleDay("","2026-10-19",listOf(Slot(0,1440,PowerState.NO_SCHEDULED_OUTAGE)))),emptyList(),incident)
    @Test fun emergencyPersistsAfterDisappearanceReopenAndForecastChanges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "history-test-${System.nanoTime()}.db"
        var db = HistoryStore(context,name)
        try {
            val incident = CurrentOutage("Пошкодження",true,at-3_600_000,at+600_000,"","")
            val first = graph(at,incident)
            db.record(address,null,first)
            val extended = graph(at+900_000,incident.copy(estimatedEnd = at+7_200_000))
            db.record(address,first,extended)
            db.record(address,first,extended) // idempotent replay
            val clear = graph(at+1_200_000)
            val tail = db.record(address,extended,clear)
            assertTrue(tail.any { it.state == PowerState.EMERGENCY_OUTAGE })
            db.close(); db = HistoryStore(context,name)
            val report = db.report(address.id,at-7_200_000,at+1_200_000)
            val archived = report.incidents.single()
            assertEquals(at+1_200_000,archived.disappearedAt)
            assertEquals(at+900_000,archived.lastSeen)
            assertEquals(at+7_200_000,archived.estimatedEnd)
            assertTrue(report.millis(PowerState.EMERGENCY_OUTAGE) > 3_600_000)
            assertEquals(0L,db.report("other-address",at,at+1_200_000).outageMillis)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun offlineGapIsUnknownAndStableObservationsAreCompacted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "history-gap-${System.nanoTime()}.db"
        val db = HistoryStore(context,name)
        try {
            val first = graph(at)
            db.record(address,null,first)
            repeat(4) { index -> db.record(address,null,graph(at+(index+1)*600_000L)) }
            db.readableDatabase.rawQuery("SELECT COUNT(*) FROM intervals",null).use {
                assertTrue(it.moveToFirst()); assertEquals(1,it.getInt(0))
            }
            db.record(address,null,graph(at+4*3_600_000L))
            val report = db.report(address.id,at,at+4*3_600_000L)
            assertTrue(report.millis(PowerState.UNKNOWN) > 2*3_600_000L)
            assertEquals(report.totalMillis,report.intervals.sumOf { it.end-it.start })
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
