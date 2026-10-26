package ua.svitlo.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ua.svitlo.app.domain.*
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable internal fun AddressDetailsPage(address: SavedAddress,schedule: Schedule?,attempt: FetchAttempt?,now: Instant,
    changedAt: Long?,onBack: () -> Unit,onHistory: () -> Unit) {
    FullScreenPage(address.title,onBack) {
        LazyColumn(Modifier.fillMaxSize(),contentPadding = PaddingValues(20.dp),verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { Text(address.description,style = MaterialTheme.typography.bodyMedium,color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (schedule == null) item { Text("Очікуємо дані VOE") }
            else {
                schedule.queue?.let { queue -> item { Text("Черга $queue",style = MaterialTheme.typography.titleMedium) } }
                if (schedule.days.any { day -> day.slots.any { it.state == PowerState.SCHEDULED_OUTAGE } }) item {
                    Text("Синя лінія — відключення за графіком VOE.",style = MaterialTheme.typography.bodySmall)
                }
                item { SettingsGroup("Графік") { ScheduleTimeline(schedule,now,notificationWindow(now),"Найближчі години") } }
                val intervals = schedule.inspectionSpans(notificationWindow(now),now).filter { it.state in OUTAGE_STATES && it.end > now }
                if (intervals.isNotEmpty()) item { SettingsGroup("Опубліковані відключення") {
                    intervals.forEachIndexed { index,span ->
                        if (index > 0) HorizontalDivider()
                        Text(TimelineNavigation.range(span,now),style = MaterialTheme.typography.titleMedium)
                        Text("${powerLabel(span.state)} · " + if (span.state in setOf(PowerState.CONFIRMED_OUTAGE,PowerState.EMERGENCY_OUTAGE))
                            "завершення орієнтовне" else remainingTime(span.end,span.start),style = MaterialTheme.typography.bodyMedium)
                    }
                } }
                schedule.currentOutage?.let { incident ->
                    item { SettingsGroup(incident.kindLabel) {
                        Text(incident.reason.ifEmpty { "Причину не вказано" },style = MaterialTheme.typography.bodyLarge)
                        val start = Instant.ofEpochMilli(incident.startedAt ?: schedule.fetchedAt)
                        val end = incident.estimatedEnd?.let { Instant.ofEpochMilli(it) }
                        Text("Початок: ${start.atZone(KYIV).format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))}")
                        end?.let { Text("Орієнтовне відновлення: ${it.atZone(KYIV).format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))}") }
                        Text("Час відновлення може змінитися.",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                }
                item { Disclosure("Джерело та оновлення") {
                    Text("Перевірено ${Instant.ofEpochMilli(schedule.fetchedAt).atZone(KYIV).format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))}")
                    Text("Графік змінено ${Instant.ofEpochMilli(changedAt ?: schedule.fetchedAt).atZone(KYIV).format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))}")
                    attempt?.error?.let { Text(it,color = MaterialTheme.colorScheme.error) }
                    schedule.currentOutage?.source?.takeIf { it.isNotBlank() }?.let { Text(it,style = MaterialTheme.typography.bodySmall) }
                    schedule.messages.forEach { Text(it,style = MaterialTheme.typography.bodySmall) }
                } }
            }
            item { FilledTonalButton(onClick = onHistory,modifier = Modifier.fillMaxWidth()) {
                Icon(SvitloIcons.History,null); Spacer(Modifier.width(8.dp)); Text("Історія та статистика")
            } }
        }
    }
}
