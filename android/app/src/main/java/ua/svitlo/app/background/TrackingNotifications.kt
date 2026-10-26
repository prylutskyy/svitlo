package ua.svitlo.app.background

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.widget.RemoteViews
import ua.svitlo.app.MainActivity
import ua.svitlo.app.R
import ua.svitlo.app.domain.*
import java.time.Instant
import java.time.format.DateTimeFormatter

class TrackingNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Відстеження графіка", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Тихі графіки вибраних адрес без обмеження часу"
            setSound(null, null); enableVibration(false); setShowBadge(false)
        })
    }
    fun render(state: AppState, now: Instant = Instant.now()) {
        EventNotifications(context).render(state, now)
        val active = state.graphsEnabled(now) && !state.graphDismissed && state.addresses.any { it.selected }
        manager.activeNotifications.filter { it.tag?.startsWith(PREFIX) == true || (it.tag == TAG && !active) }
            .forEach { manager.cancel(it.tag, ID) }
        if (!active) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val resources = context.resources
        val fontScale = resources.configuration.fontScale
        val complete = NotificationChart.create(state,now,Int.MAX_VALUE)
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val width = (resources.displayMetrics.widthPixels/resources.displayMetrics.density-120).coerceIn(216f,320f)
        val chart = NotificationPanel.fit(complete,width,fontScale)
        val summary = chart.focus?.let { "${it.address.title} · ${it.status.ifEmpty { "Відключень не позначено" }}" }
            ?: if (complete.rows.any { it.current == PowerState.UNKNOWN }) "Стан уточнюється" else "Відключень не позначено"
        val open = PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_bolt)
            .setColor(when {
                complete.rows.any { it.emergency || it.current == PowerState.EMERGENCY_OUTAGE } -> if (dark) Color.rgb(255,102,107) else Color.rgb(187,37,43)
                chart.total > 0 -> if (dark) Color.rgb(255,187,88) else Color.rgb(169,87,0)
                complete.rows.any { it.current == PowerState.UNKNOWN } -> Color.GRAY
                else -> if (dark) Color.rgb(160,211,179) else Color.rgb(36,94,70)
            })
            .setContentTitle(complete.summary).setContentText(summary).setContentIntent(open)
            .setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(true).setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setDeleteIntent(PendingIntent.getBroadcast(context,10,
                Intent(context,NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.HIDE_GRAPHS),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        if (chart.rows.isNotEmpty()) {
            val views = RemoteViews(context.packageName,R.layout.notification_chart).apply {
                setImageViewBitmap(R.id.notification_chart_image,NotificationPanel.draw(chart,dark,width,fontScale))
                setContentDescription(R.id.notification_chart_image,"${complete.description}\n${now.atZone(KYIV).format(TIME)}")
            }
            builder.setStyle(Notification.DecoratedCustomViewStyle()).setCustomBigContentView(views)
        } else builder.setStyle(Notification.BigTextStyle().bigText(summary))
        val notification = builder.build()
        // Custom graphics intentionally use a regular ongoing notification, without promotion.
        // Dismissal is persisted by NotificationActionReceiver; subsequent refreshes cannot repost it.
        manager.notify(TAG, ID, notification)
    }
    companion object {
        const val CHANNEL = "tracking-v1"
        const val PREFIX = "address:"
        const val ID = 1
        const val TAG = "tracking"
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    }
}

fun PowerState.label(): String = when (this) {
    PowerState.EMERGENCY_OUTAGE -> "Аварійне відключення"
    PowerState.NO_SCHEDULED_OUTAGE -> "Відключень не позначено"
    PowerState.CONFIRMED_OUTAGE -> "Відключення за графіком"
    PowerState.POSSIBLE_OUTAGE -> "Відключення за графіком"
    PowerState.UNKNOWN -> "Невідомий стан графіка"
    PowerState.SCHEDULED_OUTAGE -> "За графіком"
}
fun PowerState.color(): Int = when (this) {
    PowerState.EMERGENCY_OUTAGE -> Color.rgb(185, 48, 48)
    PowerState.NO_SCHEDULED_OUTAGE -> Color.rgb(48, 142, 107)
    PowerState.CONFIRMED_OUTAGE -> Color.rgb(205, 77, 65)
    PowerState.POSSIBLE_OUTAGE -> Color.rgb(210, 147, 43)
    PowerState.UNKNOWN -> Color.rgb(139, 145, 153)
    PowerState.SCHEDULED_OUTAGE -> Color.rgb(54, 103, 175)
}
