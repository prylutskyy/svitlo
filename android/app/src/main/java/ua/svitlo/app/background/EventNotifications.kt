package ua.svitlo.app.background

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import ua.svitlo.app.MainActivity
import ua.svitlo.app.R
import ua.svitlo.app.domain.*
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Standard warning notification, eligible for a system live pill only during the final 15 minutes. */
class EventNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    fun render(state: AppState, now: Instant) {
        NotificationSounds.createChannels(context)
        val event = if (state.eventMonitoring) FocusEvent.create(state, now) else null
        if (event == null || event.key == state.dismissedEventKey) { manager.cancel(TAG, ID); return }
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        fun actionIntent(action: String, code: Int) = PendingIntent.getBroadcast(context, code,
            Intent(context, NotificationActionReceiver::class.java).setAction(action).putExtra("key", event.key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val startTime = event.target!!.atZone(KYIV).format(DateTimeFormatter.ofPattern("HH:mm"))
        val headline = when {
            event.emergency -> "Скоро аварійне відключення"
            event.possible -> "Вимкнення за графіком"
            else -> "Скоро вимкнення"
        }
        val timing = "Початок о $startTime"
        val explanation = when {
            event.emergency -> "Аварійне повідомлення VOE"
            else -> "За графіком VOE"
        }
        // Native templates preserve promotion, accessibility and the system-owned countdown.
        val accent = when { event.emergency -> Color.rgb(187,37,43); else -> Color.rgb(169,87,0) }
        val soundPrefs = context.getSharedPreferences("event-sounds", Context.MODE_PRIVATE)
        val alreadyAlerted = soundPrefs.getString("last-event", null) == event.key
        val builder = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_bolt_off)
            .setContentTitle("${event.address.title} · $headline")
            .setContentText(timing)
            .setStyle(Notification.BigTextStyle().bigText("$timing\n$explanation"))
            .setContentIntent(open).setOnlyAlertOnce(alreadyAlerted)
            .setAutoCancel(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setColor(accent)
            .setDeleteIntent(actionIntent(NotificationActionReceiver.HIDE_EVENT, 11))
        val schedule = state.schedule(event.address.id)!!
        val freshness = Instant.ofEpochMilli(schedule.fetchedAt).plus(schedule.freshness(now))
        builder.setWhen(event.target!!.toEpochMilli()).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        // Keep the warning only until its scheduled start or until source freshness expires.
        builder.setTimeoutAfter(java.time.Duration.between(now, minOf(event.target, freshness)).toMillis().coerceAtLeast(1))
        // The user explicitly enabled current-place events; promote only during the final 15 minutes.
        val promoted = Build.VERSION.SDK_INT >= 36 && event.canPromote(now)
        if (promoted) {
            builder.setOngoing(true).setRequestPromotedOngoing(true)
                .addAction(Notification.Action.Builder(null, "Приховати",
                    actionIntent(NotificationActionReceiver.HIDE_EVENT, 12)).build())
        }
        manager.notify(TAG, ID, builder.build())
        if (!alreadyAlerted) soundPrefs.edit().putString("last-event", event.key).apply()
    }
    companion object { const val CHANNEL = NotificationSounds.WARNING; const val TAG = "current-place"; const val ID = 2 }
}
