package ua.svitlo.app.background

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.PowerManager
import androidx.work.*
import ua.svitlo.app.domain.*
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

fun Context.lowPower(): Boolean {
    val battery = getSystemService(BatteryManager::class.java)
    val capacity = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    return getSystemService(PowerManager::class.java).isPowerSaveMode || (!battery.isCharging && capacity in 0..15)
}

class TrackingScheduler(private val context: Context) {
    private val work get() = WorkManager.getInstance(context)
    private val alarm get() = context.getSystemService(AlarmManager::class.java)
    private fun boundaryIntent() = PendingIntent.getBroadcast(context, 1,
        Intent(context, TrackingReceiver::class.java).setAction(ACTION_BOUNDARY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun network() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun requestRefresh(reconnected: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(network())
            .setInputData(workDataOf(RefreshWorker.RECONNECTED to reconnected)).build()
        work.enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP, request)
    }

    fun refreshIfDue(state: AppState) {
        if (RefreshPolicy.needsRefresh(state,Instant.now(),context.lowPower())) requestRefresh()
    }

    fun reconcile(state: AppState, refreshSoon: Boolean = false) {
        val migration = context.getSharedPreferences("refresh-policy-version",Context.MODE_PRIVATE)
        if (migration.getInt("version",0) < 2) {
            work.cancelUniqueWork(IMMEDIATE) // Retire queued work with the old BatteryNotLow constraint.
            migration.edit().putInt("version",2).apply()
        }
        val now = Instant.now()
        val addresses = state.monitoredAddresses(now)
        work.cancelUniqueWork(EVENT_BOUNDARY)
        if (!state.active(now) || addresses.isEmpty()) {
            listOf(PERIODIC,IMMEDIATE,PLANNED,EVENT_BOUNDARY).forEach { work.cancelUniqueWork(it) }
            alarm.cancel(boundaryIntent())
            return
        }
        // Fallback opportunities; the shared policy only fetches addresses whose deadline is due.
        val recurring = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES, 5, TimeUnit.MINUTES)
            .setConstraints(network()).build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, recurring)
        val lowPower = context.lowPower()
        if (refreshSoon || RefreshPolicy.needsRefresh(state,now,lowPower)) requestRefresh()
        val due = addresses.minOf { RefreshPolicy.nextDue(state,it.id,now,lowPower = lowPower) }
        // One persisted deadline, including the precise predicted end. The trigger never performs HTTP
        // itself, so replacing it cannot cancel an in-flight network request.
        val trigger = OneTimeWorkRequestBuilder<RefreshDueWorker>()
            .setInitialDelay(Duration.between(now,due).toMillis().coerceAtLeast(0),TimeUnit.MILLISECONDS).build()
        work.enqueueUniqueWork(PLANNED,ExistingWorkPolicy.REPLACE,trigger)
        EnergyPolicy.nextBoundary(state,now)?.let { alarm.set(AlarmManager.RTC,it.toEpochMilli(),boundaryIntent()) }
            ?: alarm.cancel(boundaryIntent())
    }

    companion object {
        const val PERIODIC = "voe-all-addresses-refresh"
        const val IMMEDIATE = "voe-requested-refresh"
        const val PLANNED = "voe-next-address-deadline"
        const val EVENT_BOUNDARY = "voe-event-boundary-refresh" // Retired name; canceled on stop/upgrade.
        const val ACTION_BOUNDARY = "ua.svitlo.BOUNDARY"
    }
}
