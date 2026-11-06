package ua.svitlo.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ua.svitlo.app.ui.SvitloScreen
import ua.svitlo.app.ui.SvitloTheme
import ua.svitlo.app.background.refreshSchedulesOnOpen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshSchedulesOnOpen()
        setContent {
            SvitloTheme {
                SvitloScreen(onNotificationSettings = {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                })
            }
        }
    }
}
