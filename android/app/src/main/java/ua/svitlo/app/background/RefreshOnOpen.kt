package ua.svitlo.app.background

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ua.svitlo.app.app
import ua.svitlo.app.domain.RefreshPolicy

/** Both launcher and widget entry points use the same durable cooldown and source batch. */
internal fun ComponentActivity.refreshSchedulesOnOpen(refresh: suspend (opened:Boolean)->Unit = { opened ->
    val services=applicationContext.app
    services.repository.refresh(manual=false,foreground=!opened,opened=opened,
        lowPower=applicationContext.lowPower(),onUpdated={services.publish()})
    services.reconcile()
}) {
    lifecycleScope.launch(Dispatchers.IO) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var opened=true
            while(true) {
                try { refresh(opened) }
                catch(e:CancellationException) {throw e}
                catch(e:Exception) {Log.w("Svitlo","Unable to check schedules on screen entry",e)}
                opened=false
                delay(RefreshPolicy.FOREGROUND_MILLIS)
            }
        }
    }
}
