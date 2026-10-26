package ua.svitlo.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import ua.svitlo.app.domain.*
import java.time.Instant

/** Independent, indexed local archive. A refresh is one transaction; snapshots are not accumulated. */
class HistoryStore(context: Context, name: String = "power-history-v1.db") : SQLiteOpenHelper(context, name, null, 1) {
    private val gson = Gson()
    override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE checkpoints (address TEXT PRIMARY KEY, since INTEGER NOT NULL, snapshot TEXT NOT NULL)")
        db.execSQL("CREATE TABLE intervals (id INTEGER PRIMARY KEY, address TEXT NOT NULL, start INTEGER NOT NULL, finish INTEGER NOT NULL, state TEXT NOT NULL)")
        db.execSQL("CREATE INDEX interval_address_time ON intervals(address,finish)")
        db.execSQL("CREATE TABLE incidents (id INTEGER PRIMARY KEY, address TEXT NOT NULL, start INTEGER NOT NULL, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, disappeared INTEGER, emergency INTEGER NOT NULL, reason TEXT NOT NULL, estimated_end INTEGER, source_start INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX incident_address_time ON incidents(address,start)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported history schema")

    @Synchronized fun record(address: SavedAddress, previous: Schedule?, next: Schedule): List<HistoryInterval> {
        val db = writableDatabase
        db.beginTransaction()
        try {
            var since = previous?.fetchedAt ?: next.fetchedAt
            var checkpoint = previous
            db.rawQuery("SELECT since,snapshot FROM checkpoints WHERE address=?", arrayOf(address.id)).use {
                if (it.moveToFirst()) { since = it.getLong(0); checkpoint = gson.fromJson(it.getString(1),Schedule::class.java) }
            }
            if (checkpoint == null || next.fetchedAt >= checkpoint!!.fetchedAt) {
                checkpoint?.let { old ->
                    observeIncident(db,address.id,old)
                    PowerHistory.observed(old,next.fetchedAt).forEach { append(db,address.id,it) }
                }
                observeIncident(db,address.id,next)
                val values = ContentValues().apply {
                    put("address",address.id); put("since",since); put("snapshot",gson.toJson(next))
                }
                db.insertWithOnConflict("checkpoints",null,values,SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return report(address.id,next.fetchedAt - 24 * 3600_000L,next.fetchedAt).intervals.filter { it.state != PowerState.UNKNOWN }
    }

    private fun append(db: SQLiteDatabase, address: String, interval: HistoryInterval) {
        // Adjacent unchanged observations occupy one row, even after thousands of refreshes.
        db.rawQuery("SELECT id,finish,state FROM intervals WHERE address=? ORDER BY finish DESC LIMIT 1",arrayOf(address)).use {
            if (it.moveToFirst() && it.getLong(1) == interval.start && it.getString(2) == interval.state.name) {
                db.execSQL("UPDATE intervals SET finish=? WHERE id=?",arrayOf(interval.end,it.getLong(0)))
                return
            }
        }
        db.insertOrThrow("intervals",null,ContentValues().apply {
            put("address",address); put("start",interval.start); put("finish",interval.end); put("state",interval.state.name)
        })
    }

    private fun observeIncident(db: SQLiteDatabase, address: String, schedule: Schedule) {
        val incident = schedule.currentOutage
        var open: HistoryIncident? = null
        db.rawQuery("SELECT id,start,first_seen,last_seen,disappeared,emergency,reason,estimated_end,source_start FROM incidents WHERE address=? AND disappeared IS NULL ORDER BY id DESC LIMIT 1",arrayOf(address)).use {
            if (it.moveToFirst()) open = readIncident(it)
        }
        val same = incident != null && open != null && incident.emergency == open!!.emergency &&
            (incident.startedAt == null || !open!!.sourceStart || incident.startedAt == open!!.start)
        if (open != null && !same && schedule.fetchedAt > open!!.lastSeen) {
            db.execSQL("UPDATE incidents SET disappeared=? WHERE id=?",arrayOf(schedule.fetchedAt,open!!.id))
        }
        if (incident == null) return
        val values = ContentValues().apply {
            put("last_seen",schedule.fetchedAt); put("reason",incident.reason)
            if (incident.estimatedEnd == null) putNull("estimated_end") else put("estimated_end",incident.estimatedEnd)
        }
        if (same) {
            if (incident.startedAt != null) { values.put("start",incident.startedAt); values.put("source_start",1) }
            db.update("incidents",values,"id=?",arrayOf(open!!.id.toString()))
        } else {
            values.put("address",address); values.put("start",incident.startedAt ?: schedule.fetchedAt)
            values.put("first_seen",schedule.fetchedAt); values.put("emergency",if (incident.emergency) 1 else 0)
            values.put("source_start",if (incident.startedAt != null) 1 else 0)
            db.insertOrThrow("incidents",null,values)
        }
    }

    private fun readIncident(c: android.database.Cursor) = HistoryIncident(c.getLong(0),c.getLong(1),c.getLong(2),c.getLong(3),
        if (c.isNull(4)) null else c.getLong(4),c.getInt(5) != 0,c.getString(6),if (c.isNull(7)) null else c.getLong(7),c.getInt(8) != 0)

    @Synchronized fun report(address: String, from: Long?, until: Long): HistoryReport {
        val db = readableDatabase
        var since: Long? = null
        var latest: Schedule? = null
        db.rawQuery("SELECT since,snapshot FROM checkpoints WHERE address=?",arrayOf(address)).use {
            if (it.moveToFirst()) { since = it.getLong(0); latest = gson.fromJson(it.getString(1),Schedule::class.java) }
        }
        val incidents = mutableListOf<HistoryIncident>()
        val lower = from ?: 0L
        db.rawQuery("SELECT id,start,first_seen,last_seen,disappeared,emergency,reason,estimated_end,source_start FROM incidents WHERE address=? AND start<? AND (disappeared IS NULL OR disappeared>?) ORDER BY start DESC",arrayOf(address,until.toString(),lower.toString())).use {
            while (it.moveToNext()) incidents += readIncident(it)
        }
        val start = from ?: minOf(since ?: until,incidents.minOfOrNull { it.start } ?: until)
        val evidence = mutableListOf<HistoryInterval>()
        db.rawQuery("SELECT start,finish,state FROM intervals WHERE address=? AND finish>? AND start<?",arrayOf(address,start.toString(),until.toString())).use {
            while (it.moveToNext()) evidence += HistoryInterval(it.getLong(0),it.getLong(1),PowerState.valueOf(it.getString(2)))
        }
        latest?.let { evidence += PowerHistory.observed(it,until) }
        // A source-reported start can recover the incident before the first observation.
        incidents.forEach {
            if (it.lastSeen > it.start) evidence += HistoryInterval(it.start,minOf(it.lastSeen,until),
                if (it.emergency) PowerState.EMERGENCY_OUTAGE else PowerState.CONFIRMED_OUTAGE)
        }
        return HistoryReport(start,until,PowerHistory.resolve(evidence,start,until),incidents,since)
    }
}
