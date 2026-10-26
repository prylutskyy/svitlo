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
    var name by rememberSaveable(address.title) { mutableStateOf(address.title) }
    val incident = schedule?.currentOutage
    val stale = schedule?.stale(now) != false
    val chart = remember(address,schedule,attempt,now,history) {
        NotificationChart.create(AppState(addresses = listOf(address.copy(selected = true)),
            schedules = schedule?.let { listOf(CachedSchedule(address.id,it)) }.orEmpty(),attempts = listOfNotNull(attempt),
            historyTails = listOf(HistoryTail(address.id,history))),now,1)
    }
    val row = chart.rows.single()
    val active = incident != null || row.current in OUTAGE_STATES
    val accent = outageColor(if (incident?.emergency == true) PowerState.EMERGENCY_OUTAGE else if (active) row.current else PowerState.CONFIRMED_OUTAGE)
    Surface(shape = MaterialTheme.shapes.large,color = colors.surfaceContainerLowest,
        border = if (isHere) androidx.compose.foundation.BorderStroke(1.dp,colors.primary.copy(alpha = .3f)) else null) {
        Column(Modifier.padding(20.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(address.title,Modifier.clickable(onClickLabel = "Відкрити повний графік",onClick = onDetails),style = MaterialTheme.typography.titleLarge)
                    Text("${address.street.name.removePrefix("вулиця. ")}, ${address.house.name} · ${address.city.name.substringBefore(" (").removePrefix("м.. ").removePrefix("м. ")}",
                        style = MaterialTheme.typography.bodySmall,color = colors.onSurfaceVariant,maxLines = 2)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(SvitloIcons.More,"Дії для ${address.title}") }
                    DropdownMenu(menu,{ menu = false }) {
                        DropdownMenuItem(text = { Text("Повний графік") },onClick = { menu = false; onDetails() })
                        DropdownMenuItem(text = { Text(if (isHere) "Ви зараз тут" else "Я зараз тут") },enabled = !isHere,onClick = { menu = false; onHere() })
                        DropdownMenuItem(text = { Text("Історія та статистика") },onClick = { menu = false; onHistory() })
                        DropdownMenuItem(text = { Text(if (address.selected) "Прибрати зі сповіщення" else "Показувати у сповіщенні") },onClick = { menu = false; onSelected(!address.selected) })
                        DropdownMenuItem(text = { Text("Перейменувати") },onClick = { menu = false; name = address.title; renaming = true })
                        DropdownMenuItem(text = { Text("Видалити",color = colors.error) },onClick = { menu = false; removing = true })
                    }
                }
            }
            if (incident != null) {
                val incidentColor = outageColor(if (incident.emergency) PowerState.EMERGENCY_OUTAGE else PowerState.CONFIRMED_OUTAGE)
                Surface(shape = MaterialTheme.shapes.medium,color = incidentColor.copy(alpha = .09f)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(SvitloIcons.Warning,null,tint = incidentColor,modifier = Modifier.size(22.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (incident.emergency) "Аварійне відключення" else "Адресне відключення",style = MaterialTheme.typography.titleMedium,color = incidentColor)
                            val finish = incident.estimatedEnd?.let { Instant.ofEpochMilli(it) }
                            if (!stale && finish != null && !incident.estimatePassed(now)) {
                                val day = when (finish.atZone(KYIV).toLocalDate()) {
                                    now.atZone(KYIV).toLocalDate() -> ""
                                    now.atZone(KYIV).toLocalDate().plusDays(1) -> "завтра "
                                    else -> finish.atZone(KYIV).format(DateTimeFormatter.ofPattern("dd.MM "))
                                }
                                Text("Орієнтовно до $day${finish.atZone(KYIV).format(Clock)}",style = MaterialTheme.typography.bodyMedium)
                            } else Text("Час відновлення уточнюється",style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            } else Text(row.status.ifEmpty { "Відключень не позначено" },style = MaterialTheme.typography.titleMedium,
                color = if (stale) colors.onSurfaceVariant else if (row.status.contains("Відключення") || row.status.contains("Можливе")) accent else colors.onSurface)
            AddressTimeline(chart,schedule,history)
            Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
                Text(when {
                    attempt?.error != null -> "Не вдалося оновити"
                    stale -> "Очікуємо свіжі дані"
                    else -> "Перевірено ${Instant.ofEpochMilli(schedule!!.fetchedAt).atZone(KYIV).format(Clock)}"
                },Modifier.weight(1f),style = MaterialTheme.typography.labelSmall,color = if (attempt?.error != null) colors.error else colors.onSurfaceVariant)
                IconButton(onClick = onHistory) { Icon(SvitloIcons.History,"Історія та статистика ${address.title}") }
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
