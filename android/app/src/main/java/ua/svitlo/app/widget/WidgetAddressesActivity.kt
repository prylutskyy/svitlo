package ua.svitlo.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import ua.svitlo.app.MainActivity
import ua.svitlo.app.app
import ua.svitlo.app.ui.FullScreenPage
import ua.svitlo.app.ui.SvitloTheme
import ua.svitlo.app.ui.AddressDetailsPage
import ua.svitlo.app.ui.HistoryPage
import ua.svitlo.app.ui.SvitloIcons
import java.time.Instant

/** Overflow always opens every address belonging to this widget, in its saved order. */
class WidgetAddressesActivity : ComponentActivity() {
    companion object { const val EXTRA_ADDRESS_ID = "ua.svitlo.widget.address" }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        enableEdgeToEdge()
        setContent { SvitloTheme {
            val state by app.store.state.collectAsStateWithLifecycle()
            var error by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { try { app.store.load() } catch (_: Exception) { error = true } }
            val config = state.widgets.orEmpty().firstOrNull { it.widgetId == id }
            var detailsId by rememberSaveable { mutableStateOf(intent.getStringExtra(EXTRA_ADDRESS_ID)) }
            var historyId by rememberSaveable { mutableStateOf<String?>(null) }
            var now by remember { mutableStateOf(Instant.now()) }
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { now = Instant.now(); delay(60_000-System.currentTimeMillis()%60_000) }
            } }
            val rows = config?.chart(state,now)?.rows.orEmpty()
            FullScreenPage("Адреси віджета",onBack = { finish() }) {
                LazyColumn(Modifier.weight(1f),contentPadding = PaddingValues(20.dp),verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (error) item { Text("Не вдалося прочитати адреси") }
                    items(rows,key = { it.address.id }) { row ->
                        Surface(onClick = { detailsId = row.address.id },shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(16.dp),verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(row.address.title,style = MaterialTheme.typography.titleMedium)
                                    Text(row.address.description,style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(row.status.ifEmpty { "Відключень у відомому графіку немає" },style = MaterialTheme.typography.bodyLarge)
                                }
                                Spacer(Modifier.width(12.dp))
                                Icon(SvitloIcons.ArrowForward,"Деталі графіка ${row.address.title}")
                            }
                        }
                    }
                }
                TextButton(onClick = { startActivity(Intent(this@WidgetAddressesActivity,MainActivity::class.java)); finish() },
                    modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("Відкрити застосунок") }
            }
            rows.firstOrNull { it.address.id == detailsId }?.address?.let { address ->
                AddressDetailsPage(address,state.schedule(address.id),state.attempt(address.id),now,
                    state.schedules.firstOrNull { it.addressId == address.id }?.changedAt,
                    onBack = { detailsId = null },onHistory = { historyId = address.id })
            }
            rows.firstOrNull { it.address.id == historyId }?.address?.let { address ->
                HistoryPage(address,state.schedule(address.id),now,onDismiss = { historyId = null })
            }
        } }
    }
}
