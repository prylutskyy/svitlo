@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package ua.svitlo.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import ua.svitlo.app.background.NotificationTimeline
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import ua.svitlo.app.R
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ua.svitlo.app.domain.*
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
internal fun ExpressiveAddressCard(address: SavedAddress, schedule: Schedule?, attempt: FetchAttempt?, now: Instant,
    onSelected: (Boolean) -> Unit, onRemove: () -> Unit, onRename: (String) -> Unit,
    isHere: Boolean = false, onHere: () -> Unit = {}, changedAt: Long? = null,
    history: List<HistoryInterval> = emptyList(), onHistory: () -> Unit = {},onDetails: () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val identity = homeIdentity(address)
    var name by rememberSaveable(identity.title) { mutableStateOf(identity.title) }
    val incident = schedule?.currentOutage
    val plan = remember(address,schedule) { homePlan(address,schedule) }
    val evidence = remember(history) { homeHistory(history) }
    val stale = plan?.stale(now) != false
    val chart = remember(address,plan,attempt,now,evidence) {
        NotificationChart.create(AppState(addresses = listOf(address.copy(selected = true)),
            schedules = plan?.let { listOf(CachedSchedule(address.id,it)) }.orEmpty(),attempts = listOfNotNull(attempt),
            historyTails = listOf(HistoryTail(address.id,evidence))),now,1)
    }
    val row = chart.rows.single()
    val active = incident != null || row.current in OUTAGE_STATES
    val accent = if (active) outageColor(if (incident?.emergency == true) PowerState.EMERGENCY_OUTAGE else PowerState.SCHEDULED_OUTAGE) else colors.onSurfaceVariant
    val dark = colors.surface.luminance() < .5f
    val base = if (dark) colors.surfaceContainerHigh else colors.surfaceContainerLowest
    val surface = if (active && !stale) lerp(base,accent,if (dark) .07f else .045f) else base
    Surface(modifier = Modifier.semantics { contentDescription = "Місце: ${identity.title}" },shape = MaterialTheme.shapes.large,color = surface,
        border = if (isHere) androidx.compose.foundation.BorderStroke(1.dp,colors.primary.copy(alpha = .3f)) else null) {
        Column(Modifier.padding(20.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(14.dp),color = accent.copy(alpha = .09f)) {
                    Box(Modifier.size(42.dp),contentAlignment = Alignment.Center) {
                        if (incident?.emergency == true) Icon(SvitloIcons.Warning,"Повідомлена аварія",tint = accent,modifier = Modifier.size(23.dp))
                        else Icon(painterResource(if (active) R.drawable.ic_bolt_off else if (row.current == PowerState.NO_SCHEDULED_OUTAGE) R.drawable.ic_bolt else R.drawable.ic_power_unknown),
                            when {
                                incident != null -> "Повідомлене відключення"
                                row.current in OUTAGE_STATES -> "Зараз відключення за планом"
                                row.current == PowerState.NO_SCHEDULED_OUTAGE -> "Зараз без відключень за планом"
                                else -> "Графік уточнюється"
                            },tint = accent,modifier = Modifier.size(23.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(identity.title,Modifier.clickable(onClickLabel = "Відкрити повний графік",onClick = onDetails),style = MaterialTheme.typography.titleLarge)
                    if (identity.subtitle.isNotBlank()) Text(identity.subtitle,
                        style = MaterialTheme.typography.bodySmall,color = colors.onSurfaceVariant,maxLines = 2)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(SvitloIcons.More,"Дії для ${identity.title}") }
                    DropdownMenu(menu,{ menu = false }) {
                        DropdownMenuItem(text = { Text("Повний графік") },onClick = { menu = false; onDetails() })
                        DropdownMenuItem(text = { Text(if (isHere) "Ви зараз тут" else "Я зараз тут") },enabled = !isHere,onClick = { menu = false; onHere() })
                        DropdownMenuItem(text = { Text("Історія та статистика") },onClick = { menu = false; onHistory() })
                        DropdownMenuItem(text = { Text(if (address.selected) "Прибрати зі сповіщення" else "Показувати у сповіщенні") },onClick = { menu = false; onSelected(!address.selected) })
                        DropdownMenuItem(text = { Text("Перейменувати") },onClick = { menu = false; name = identity.title; renaming = true })
                        DropdownMenuItem(text = { Text("Видалити",color = colors.error) },onClick = { menu = false; removing = true })
                    }
                }
            }
            if(address.feedId==null || address.feedId.startsWith("promin/")) TextButton(onClick=onDetails) { Text("Вибрати оператора та чергу") }
            if (incident != null) {
                Text(incident.kindLabel,style = MaterialTheme.typography.titleMedium,color = accent)
                Text(if (schedule?.stale(now) != false || incident.estimatePassed(now)) "Час відновлення уточнюється"
                    else incident.endLabel(now)?.let { "Відновлення орієнтовно о $it" } ?: "Час відновлення уточнюється",
                    style = MaterialTheme.typography.bodySmall,color = colors.onSurfaceVariant)
            } else Text(homeStatus(plan,row,now),style = MaterialTheme.typography.titleMedium,
                color = if (active && !stale) accent else colors.onSurface)
            AddressTimeline(chart,plan,evidence) {
            Row(Modifier.fillMaxWidth().padding(top=6.dp),verticalAlignment = Alignment.CenterVertically) {
                Text(when {
                    attempt?.error != null -> "Не вдалося оновити"
                    stale -> "Очікуємо свіжі дані"
                    else -> "${if (address.feedId != null) "Дані" else "Перевірено"} ${Instant.ofEpochMilli(schedule!!.fetchedAt).atZone(KYIV).format(Clock)}"
                },Modifier.weight(1f),style = MaterialTheme.typography.labelSmall,color = if (attempt?.error != null) colors.error else colors.onSurfaceVariant)
                if (isHere) Icon(SvitloIcons.Home,"Поточне місце",modifier = Modifier.size(16.dp),tint = colors.onSurfaceVariant)
            }
            }

        }
    }
    if (removing) AlertDialog(onDismissRequest = { removing = false },title = { Text("Видалити адресу?") },
        text = { Text("«${address.title}» буде прибрано зі списку й відстеження.") },
        confirmButton = { TextButton(onClick = { removing = false; onRemove() }) { Text("Видалити") } },
        dismissButton = { TextButton(onClick = { removing = false }) { Text("Залишити") } })
    if (renaming) AlertDialog(onDismissRequest = { renaming = false },title = { Text("Назва адреси") },
        text = { OutlinedTextField(name,{ name = it.take(60) },label = { Text("Дім, батьки, робота…") },singleLine = true) },
        confirmButton = { TextButton(onClick = { onRename(name); renaming = false },enabled = name.isNotBlank()) { Text("Зберегти") } },
        dismissButton = { TextButton(onClick = { renaming = false }) { Text("Скасувати") } })
}

@Composable
internal fun ScheduleTimeline(schedule: Schedule, now: Instant, window: TimelineWindow, title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title,style = MaterialTheme.typography.labelLarge,color = MaterialTheme.colorScheme.onSurfaceVariant)
        InteractiveTimeline(schedule.inspectionSpans(window,now),window,now)
    }
}
