package ua.svitlo.app.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ua.svitlo.app.app
import java.time.Instant

class RefreshDueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        if (applicationContext.app.store.load().active(Instant.now())) applicationContext.app.scheduler.requestRefresh()
        return Result.success()
    }
}
