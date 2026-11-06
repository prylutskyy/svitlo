package ua.svitlo.app.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri

/** Versioned channels: Android does not allow replacing an existing channel's sound. */
internal object NotificationSounds {
    const val WARNING = "current-place-v2"
    const val RESTORATION = "restoration-v2"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        fun channel(id: String, name: String, description: String, sound: String) =
            NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
                this.description = description
                setSound(Uri.parse("android.resource://${context.packageName}/raw/$sound"), attributes)
                enableVibration(false)
                setShowBadge(false)
            }
        manager.createNotificationChannels(listOf(
            channel(WARNING, "Попередження про відключення", "Звук під час появи пігулки перед відключенням", "electric_shock"),
            channel(RESTORATION, "Відновлення світла", "Повідомляє, коли джерела більше не позначає відключення", "community_electro")))
    }
}
