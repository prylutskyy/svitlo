@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package ua.svitlo.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import ua.svitlo.app.domain.*
import java.time.Instant
import java.time.format.DateTimeFormatter

internal val Clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun SvitloScreen(onNotificationSettings: () -> Unit, vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var feedAddressId by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = Instant.now(); delay(60_000 - System.currentTimeMillis() % 60_000) }
        }
    }
    var adding by rememberSaveable { mutableStateOf(false) }
    var detailsAddressId by rememberSaveable { mutableStateOf<String?>(null) }
    var historyAddressId by rememberSaveable { mutableStateOf<String?>(null) }
    var moreMenu by remember { mutableStateOf(false) }
    var appearanceSettings by rememberSaveable { mutableStateOf(false) }
    var info by rememberSaveable { mutableStateOf(false) }
    var notificationSettings by rememberSaveable { mutableStateOf(false) }
    var choosingPlace by rememberSaveable { mutableStateOf(false) }
    var pendingAction by rememberSaveable { mutableStateOf("graphs") }
    val selected = state.addresses.count { it.selected }
    val focused = state.addresses.firstOrNull { it.id == state.currentAddressId }
    val event = FocusEvent.create(state, now)
    fun perform(action: String) { when (action) {
        "events" -> vm.monitorEvents(true)
        else -> vm.showGraphs()
    } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) perform(pendingAction) else vm.message.value = "Дозвольте сповіщення, щоб бачити графіки поза додатком."
    }
    fun request(action: String) {
        pendingAction = action
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.POST_NOTIFICATIONS) else perform(action)
    }
    val snackbar = remember { SnackbarHostState() }
    val homeState = rememberSaveableStateHolder()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value = null } }
    val fullPageOpen = appearanceSettings || notificationSettings || info || adding || historyAddressId != null || detailsAddressId != null
    val homeColors = MaterialTheme.colorScheme
    val homeBackground = if (homeColors.surface.luminance() < .5f) homeColors.surface else homeColors.surfaceContainerLow
    Box(Modifier.fillMaxSize()) {
    // Keep the actual preceding screen alive for predictive back, but hide its accessibility tree.
    Box(Modifier.fillMaxSize().then(if (fullPageOpen) Modifier.clearAndSetSemantics { }.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    } else Modifier)) {
    homeState.SaveableStateProvider("home") { Scaffold(
        containerColor = homeBackground,
        topBar = {
            TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = homeBackground,scrolledContainerColor = homeBackground),title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(SvitloIcons.Bolt, null, tint = MaterialTheme.colorScheme.primary)
                Text("Світло", style = MaterialTheme.typography.titleLarge)
            } }, actions = {
                IconButton(onClick = { notificationSettings = true }, enabled = state.addresses.isNotEmpty()) { Icon(SvitloIcons.Notifications, "Сповіщення") }
                Box {
                    IconButton(onClick = { moreMenu = true }) { Icon(SvitloIcons.More, "Інші дії") }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(text = { Text("Налаштування") }, leadingIcon = { Icon(SvitloIcons.Settings, null) },
                            onClick = { moreMenu = false; appearanceSettings = true })
                        DropdownMenuItem(text = { Text("Про додаток і позначки") }, leadingIcon = { Icon(SvitloIcons.Info, null) },
                            onClick = { moreMenu = false; info = true })
                    }
                }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        HomeRefreshContainer(refreshing = busy,onRefresh = { if (state.addresses.isNotEmpty()) vm.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding).semantics {
                if (state.addresses.isNotEmpty()) customActions = listOf(CustomAccessibilityAction("Оновити графіки") { vm.refresh();true })
            }) {
            LazyColumn(Modifier.fillMaxSize().widthIn(max = 720.dp), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.graphDismissed && state.graphsEnabled(now)) item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Сповіщення приховано", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { request("graphs") }) { Text("Показати знову") }
                    }
                }
                if (state.addresses.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Поточне місце", style = MaterialTheme.typography.titleMedium)
                            if (focused == null) Text("Виберіть місце для попереджень", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { choosingPlace = true }) { Text(if (focused == null) "Вибрати" else "Змінити") }
                        if (focused != null && state.addresses.size == 1) IconButton(onClick = { adding = true }) { Icon(SvitloIcons.Add, "Додати місце") }
                    }
                }
                if (focused != null) item(key = "current:${focused.id}") {
                    ExpressiveAddressCard(focused, state.schedule(focused.id), state.attempt(focused.id), now,
                        onSelected = { vm.select(focused.id, it) }, onRemove = { vm.remove(focused.id) },
                        onRename = { vm.rename(focused.id, it) }, isHere = true,
                        onHere = { choosingPlace = true }, changedAt = state.schedules.firstOrNull { it.addressId == focused.id }?.changedAt,
                        history = state.historyTails.orEmpty().firstOrNull { it.addressId == focused.id }?.intervals.orEmpty(),
                        onHistory = { historyAddressId = focused.id },onDetails = { detailsAddressId = focused.id })
                }
                if (state.addresses.isNotEmpty() && (focused == null || state.addresses.size > 1)) item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (focused == null) "Ваші місця" else "Інші місця", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        FilledTonalIconButton(onClick = { adding = true }, modifier = Modifier.size(48.dp)) { Icon(SvitloIcons.Add, "Додати місце") }
                    }
                }
                if (state.addresses.isEmpty()) item {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraLarge) {
                        Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            Icon(SvitloIcons.Home, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("Ваші місця\nв одному місці", style = MaterialTheme.typography.headlineLarge)
                            Text("Виберіть оператора та свою чергу відключень.")
                            Button(onClick = { adding = true }, shapes = ButtonDefaults.shapes(),
                                modifier = Modifier.heightIn(min = 56.dp)) {
                                Icon(SvitloIcons.Add, null); Spacer(Modifier.width(8.dp)); Text("Додати місце")
                            }
                        }
                    }
                }
                items(state.addresses.filterNot { it.id == focused?.id }, key = { it.id }) { address ->
                    ExpressiveAddressCard(address, state.schedule(address.id), state.attempt(address.id), now,
                        onSelected = { vm.select(address.id, it) }, onRemove = { vm.remove(address.id) },
                        onRename = { vm.rename(address.id, it) }, isHere = address.id == state.currentAddressId,
                        onHere = { vm.here(address.id) }, changedAt = state.schedules.firstOrNull { it.addressId == address.id }?.changedAt,
                        history = state.historyTails.orEmpty().firstOrNull { it.addressId == address.id }?.intervals.orEmpty(),
                        onHistory = { historyAddressId = address.id },onDetails = { detailsAddressId = address.id })
                }

            }
        }
    }
    }
    }
    if (appearanceSettings) AppearancePage(onBack = { appearanceSettings = false })
    val sheetMaxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.9f
    state.addresses.firstOrNull { it.id == detailsAddressId }?.let { address ->
        AddressDetailsPage(address,state.schedule(address.id),state.attempt(address.id),now,
            state.schedules.firstOrNull { it.addressId == address.id }?.changedAt,
            onBack = { detailsAddressId = null },onHistory = { historyAddressId = address.id },onFeed = { feedAddressId = address.id },
            onRename={vm.rename(address.id,it)},refreshing=busy,onRefresh=vm::refresh)
    }
    state.addresses.firstOrNull { it.id == historyAddressId }?.let { address ->
        HistoryPage(address,state.schedule(address.id),now,onDismiss = { historyAddressId = null })
    }
    if (choosingPlace) ScrollableSheet(onDismissRequest = { choosingPlace = false }) { close ->
        Column(Modifier.fillMaxWidth().heightIn(max = sheetMaxHeight)) {
            Text("Де ви зараз?", Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp), style = MaterialTheme.typography.headlineSmall)
            Text("Попереджати про заплановані відключення.",
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.addresses, key = { it.id }) { address ->
                    Surface(onClick = { vm.here(address.id); close() }, shape = MaterialTheme.shapes.large,
                        color = if (focused?.id == address.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(address.title, style = MaterialTheme.typography.titleMedium)
                                Text(address.description, style = MaterialTheme.typography.bodySmall)
                            }
                            if (focused?.id == address.id) Icon(SvitloIcons.Check, "Поточне місце")
                        }
                    }
                }
            }
        }
    }
    if (notificationSettings) FullScreenPage("Сповіщення",onBack = { notificationSettings = false }) {
        LazyColumn(Modifier.fillMaxSize(),contentPadding = PaddingValues(20.dp),verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { SettingsGroup("На екрані блокування") {
                SettingSwitch("Графіки місць",state.graphsEnabled(now),
                    { if (it) request("graphs") else vm.hideGraphs() },"$selected місць · без обмеження часу",
                    enabled = selected > 0 || state.graphsEnabled(now),description = "Показувати графіки у сповіщенні")
                if (state.graphDismissed && state.graphsEnabled(now)) TextButton(onClick = { request("graphs") }) { Text("Показати знову") }
            } }
            item { SettingsGroup("Для поточного місця") {
                FilledTonalButton(onClick = { notificationSettings = false; choosingPlace = true },modifier = Modifier.fillMaxWidth()) {
                    Icon(SvitloIcons.Home,null); Spacer(Modifier.width(8.dp)); Text(focused?.title ?: "Вибрати місце")
                }
                SettingSwitch("Попереджати про відключення",state.eventMonitoring,{ if (it) request("events") else vm.monitorEvents(false) },
                    "За 15 хв до початку",enabled = focused?.selected == true,description = "Події поточного місця")
                if (focused != null && !focused.selected) TextButton(onClick = { vm.select(focused.id,true) }) { Text("Показувати місце у сповіщенні") }
                PillExample()
                Text("Сповіщення попереджає про початок за планом. Фактичне відновлення не перевіряється.",
                    style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.eventMonitoring && event != null) Text("${event.address.title} · ${event.title}",style = MaterialTheme.typography.bodyMedium)
            } }
            item { OutlinedButton(onClick = onNotificationSettings,modifier = Modifier.fillMaxWidth()) { Text("Налаштування Android") } }
        }
    }
    if (adding) AddAddressPage(vm, onDismiss = { adding = false })
    if (info) GuidePage(state,onBack = { info = false },onAndroidSettings = onNotificationSettings)
}
    state.addresses.firstOrNull { it.id == feedAddressId }?.let { address ->
        QueueFeedPage(vm,address,onDismiss={ feedAddressId=null })
    }

}
