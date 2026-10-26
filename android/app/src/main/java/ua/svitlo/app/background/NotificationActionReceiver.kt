package ua.svitlo.app.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import ua.svitlo.app.app

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(HIDE_GRAPHS, HIDE_EVENT)) return
        val pending = goAsync()
        context.app.scope.launch {
            try {
                context.app.updateControl({ state ->
                    when (intent.action) {
                        HIDE_GRAPHS -> state.copy(graphDismissed = true)
                        HIDE_EVENT -> state.copy(dismissedEventKey = intent.getStringExtra("key"))
                        else -> state
                    }
                })
            } finally { pending.finish() }
        }
    }
    companion object {
        const val HIDE_GRAPHS = "ua.svitlo.HIDE_GRAPHS"
        const val HIDE_EVENT = "ua.svitlo.HIDE_EVENT"
    }
}
