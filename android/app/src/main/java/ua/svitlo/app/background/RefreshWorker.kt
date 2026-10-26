package ua.svitlo.app.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ua.svitlo.app.app
import java.time.Instant

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val services = applicationContext.app
        if (services.store.load().active(Instant.now())) services.repository.refresh(manual = false,
            lowPower = applicationContext.lowPower(), reconnected = inputData.getBoolean(RECONNECTED,false),
            onUpdated = { services.publish() })
        services.reconcile()
        // Per-address durable exponential backoff owns retries, not an additional WorkManager loop.
        return Result.success()
    }
    companion object { const val RECONNECTED = "network-reconnected" }
}
