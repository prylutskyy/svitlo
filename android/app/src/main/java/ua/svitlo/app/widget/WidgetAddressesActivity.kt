package ua.svitlo.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import ua.svitlo.app.MainActivity
import ua.svitlo.app.app
import ua.svitlo.app.background.refreshSchedulesOnOpen
import ua.svitlo.app.ui.*
import java.time.Instant

/** Keeps existing widget PendingIntents compatible, without an intermediate address-list screen. */
class WidgetAddressesActivity : ComponentActivity() {
    companion object {const val EXTRA_ADDRESS_ID="ua.svitlo.widget.address"}
    private fun openHome() {
        // MainActivity is standard launch mode: CLEAR_TOP recreates it on its actual home screen.
        startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        val requestedId=intent.getStringExtra(EXTRA_ADDRESS_ID)
        enableEdgeToEdge();refreshSchedulesOnOpen()
        setContent {SvitloTheme {
            val vm:MainViewModel=viewModel()
            val refreshing by vm.busy.collectAsStateWithLifecycle()
            val state by app.store.state.collectAsStateWithLifecycle()
            var loaded by remember {mutableStateOf(false)}
            var error by remember {mutableStateOf(false)}
            LaunchedEffect(Unit) {
                try {app.store.load()}catch(_:Exception) {error=true}
                loaded=true
            }
            val config=state.widgets.orEmpty().firstOrNull {it.widgetId==widgetId}
            val address=state.addresses.firstOrNull {it.id==requestedId}
                ?: config?.addressIds?.firstNotNullOfOrNull {id->state.addresses.firstOrNull{it.id==id}}
            var history by rememberSaveable {mutableStateOf(false)}
            var editing by rememberSaveable {mutableStateOf(false)}
            var now by remember {mutableStateOf(Instant.now())}
            val lifecycle=LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(lifecycle) {lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while(true) {now=Instant.now();delay(60_000-System.currentTimeMillis()%60_000)}
            }}
            LaunchedEffect(loaded,address?.id,error) {if(loaded && address==null && !error)openHome()}
            when {
                error -> FullScreenPage("Графік недоступний",onBack=::openHome) {Text("Не вдалося прочитати місце")}
                address!=null && editing -> QueueFeedPage(vm,address,onDismiss={editing=false})
                address!=null && history -> HistoryPage(address,state.schedule(address.id),now,onDismiss={history=false})
                address!=null -> AddressDetailsPage(address,state.schedule(address.id),state.attempt(address.id),now,
                    state.schedules.firstOrNull{it.addressId==address.id}?.changedAt,
                    onBack=::openHome,onHistory={history=true},onFeed={editing=true},onRename={vm.rename(address.id,it)},
                    refreshing=refreshing,onRefresh=vm::refresh)
            }
        }}
    }
}
