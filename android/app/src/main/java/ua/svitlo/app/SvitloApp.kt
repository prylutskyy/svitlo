package ua.svitlo.app

import android.app.Application
import android.content.Context
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ua.svitlo.app.background.*
import ua.svitlo.app.data.*
import ua.svitlo.app.domain.AppState
import ua.svitlo.app.domain.RestorationPolicy

class SvitloApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store by lazy { StateStore(this) }
    val history by lazy { HistoryStore(this) }
    val repository by lazy { Repository(store, VoeClient(), recordHistory = history::record) }
    val notifications by lazy { TrackingNotifications(this) }
    val scheduler by lazy { TrackingScheduler(this) }
    private val controlMutex = Mutex()
    override fun onCreate() {
        super.onCreate()
        getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            private var validated = false
            override fun onCapabilitiesChanged(net: android.net.Network, capabilities: android.net.NetworkCapabilities) {
                val available = capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (available && !validated) scope.launch {
                    if (store.load().active(java.time.Instant.now())) scheduler.requestRefresh(reconnected = true)
                }
                validated = available
            }
            override fun onLost(net: android.net.Network) { validated = false }
        })
    }
    suspend fun publish() = controlMutex.withLock {
        val current = store.update { RestorationPolicy.observe(it,java.time.Instant.now()) }
        notifications.render(current)
        ua.svitlo.app.widget.SvitloWidgets.renderAll(this, current)
        postRestoration(current)
        scheduler.refreshIfDue(current)
    }
    suspend fun reconcile(refreshSoon: Boolean = false) = controlMutex.withLock {
        val current = store.update { RestorationPolicy.observe(it,java.time.Instant.now()) }
        notifications.render(current)
        ua.svitlo.app.widget.SvitloWidgets.renderAll(this, current)
        postRestoration(current)
        scheduler.reconcile(current, refreshSoon)
    }
    suspend fun updateControl(transform: (AppState) -> AppState, refreshSoon: Boolean = false) = controlMutex.withLock {
        val current = store.update { RestorationPolicy.observe(transform(it),java.time.Instant.now()) }
        notifications.render(current)
        ua.svitlo.app.widget.SvitloWidgets.renderAll(this, current)
        postRestoration(current)
        scheduler.reconcile(current, refreshSoon)
    }

    private fun postRestoration(state: AppState) {
        if (!state.eventMonitoring) return
        val key = state.restorationNotifiedKey ?: return
        if(!key.startsWith("${state.currentAddressId}:"))return
        val prefs = getSharedPreferences("restoration-notices", Context.MODE_PRIVATE)
        if (prefs.getString("posted", null) == key) return
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(NotificationManager::class.java)
        NotificationSounds.createChannels(this)
        val title = state.addresses.firstOrNull { it.id == state.currentAddressId }?.title ?: "Поточне місце"
        val planned = key.endsWith(":plan-ended")
        val message = if (planned) "Плановий інтервал завершився" else "Оператор більше не повідомляє про відключення"
        val open = PendingIntent.getActivity(this, 32, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify("restoration", 3, Notification.Builder(this, NotificationSounds.RESTORATION)
            .setSmallIcon(R.drawable.ic_bolt).setColor(Color.rgb(48,142,107))
            .setContentTitle(if (planned) "Світло за графіком" else "Відключення знято").setSubText(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText("$message. Фактична наявність напруги не вимірюється."))
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(false).build())
        prefs.edit().putString("posted", key).apply()
    }
}

val Context.app: SvitloApp get() = applicationContext as SvitloApp
