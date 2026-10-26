package ua.svitlo.app.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import ua.svitlo.app.app
import java.time.Instant

class TrackingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            TrackingScheduler.ACTION_BOUNDARY, Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> Unit
            else -> return
        }
        val pending = goAsync()
        context.app.scope.launch {
            try {
                val services = context.app
                val state = services.store.load()
                if (!state.active(Instant.now()) && state.session != null) {
                    services.store.update { it.copy(session = null) }
                }
                // The marker alarm is local only. Network deadlines are planned independently.
                services.reconcile(refreshSoon = intent.action != TrackingScheduler.ACTION_BOUNDARY)
            } finally { pending.finish() }
        }
    }
}
