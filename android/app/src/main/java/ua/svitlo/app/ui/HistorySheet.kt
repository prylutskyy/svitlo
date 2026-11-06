@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ua.svitlo.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.svitlo.app.app
import ua.svitlo.app.domain.*
import java.time.Instant
import java.time.format.DateTimeFormatter

private val historyDate = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
internal fun historyDuration(millis: Long): String {
    val minutes = (millis.coerceAtLeast(0) / 60_000)
    return when {
        millis > 0 && minutes == 0L -> "< 1 хв"
        minutes < 60 -> "$minutes хв"
        minutes % 60 == 0L -> "${minutes / 60} год"
        else -> "${minutes / 60} год ${minutes % 60} хв"
    }
}

@Composable internal fun HistoryPage(address: SavedAddress, schedule: Schedule?, now: Instant, onDismiss: () -> Unit) {
    var period by rememberSaveable(address.id) { mutableIntStateOf(0) }
    var shown by rememberSaveable(address.id,period) { mutableIntStateOf(30) }
    var openDays by rememberSaveable(address.id,period) { mutableStateOf(emptyList<String>()) }
    var openIncident by rememberSaveable(address.id,period) { mutableStateOf<Long?>(null) }
    val dayLimits = remember(address.id,period) { mutableStateMapOf<String,Int>() }
    val context = LocalContext.current
    val end = now.toEpochMilli()
    val start = when (period) {
        0 -> now.atZone(KYIV).minusWeeks(1).toInstant().toEpochMilli()
        1 -> now.atZone(KYIV).minusMonths(1).toInstant().toEpochMilli()
        2 -> now.atZone(KYIV).minusMonths(6).toInstant().toEpochMilli()
        3 -> now.atZone(KYIV).minusYears(1).toInstant().toEpochMilli()
        else -> null
    }
    val result by produceState<Result<HistoryReport>?>(null,address.id,period,now,schedule) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching {
            schedule?.let { context.app.history.record(address,null,it) }
            context.app.history.report(address.id,start,end)
        } }
    }
    val colors = MaterialTheme.colorScheme
    FullScreenPage("Історія та статистика",onBack = onDismiss) {
        LazyColumn(Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp,end = 20.dp,bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column {
                        Text(address.title,style = MaterialTheme.typography.headlineMedium)
                        Text(address.description,style = MaterialTheme.typography.bodyMedium,color = colors.onSurfaceVariant)
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("Тиждень","Місяць","Пів року","Рік","Весь час").withIndex().toList()) { (index,label) ->
                        FilterChip(selected = period == index,onClick = { period = index },label = { Text(label) })
                    }
                }
            }
            val report = result?.getOrNull()
            when {
                result == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                report == null -> item { Text("Не вдалося прочитати історію. Закрийте та спробуйте знову.",color = colors.error) }
                report.recordedSince == null -> item { Text("Історія почнеться після першого успішного оновлення цієї адреси.") }
                else -> {
                    item {
                        Surface(shape = MaterialTheme.shapes.extraLarge,color = colors.surfaceContainerLow) {
                            Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Адресні відключення",style = MaterialTheme.typography.labelLarge,color = colors.onSurface)
                                Text("≈ ${historyDuration(report.outageMillis)}",style = MaterialTheme.typography.headlineLarge,color = colors.onSurface)
                                Text("${Instant.ofEpochMilli(report.start).atZone(KYIV).format(historyDate)} — ${now.atZone(KYIV).format(historyDate)}",
                                    style = MaterialTheme.typography.bodySmall,color = colors.onSurface)
                            }
                        }
                    }
                    item { HistoryBreakdown(report) }
                    item { Disclosure("Як рахуємо") {
                        Text("За даними джерела, без вимірювання напруги. Ймовірні інтервали не входять у суму відключень.",style = MaterialTheme.typography.bodySmall)
                        Text("Пропуски перевірок позначаємо як невідомі. Історія зберігається на пристрої.",style = MaterialTheme.typography.bodySmall)
                        Text("Перший запис: ${Instant.ofEpochMilli(report.recordedSince!!).atZone(KYIV).format(historyDate)}",style = MaterialTheme.typography.labelMedium)
                    } }
                    item { Text("Журнал подій ВОЕ",style = MaterialTheme.typography.titleLarge) }
                    if (report.incidents.isEmpty()) item {
                        Text("Подій немає",color = colors.onSurfaceVariant)
                    }
                    val days = HistoryJournal.days(report.incidents).entries.toList()
                    days.take(shown).forEach { (date,events) ->
                        val key = date.toString()
                        val expanded = key in openDays
                        item(key = "day:$key") {
                            Surface(onClick = { openDays = if (expanded) openDays-key else openDays+key },
                                shape = MaterialTheme.shapes.large,color = colors.surfaceContainerLow) {
                                Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment = Alignment.CenterVertically) {
                                    Text(HistoryJournal.label(date,now),Modifier.weight(1f),style = MaterialTheme.typography.titleMedium)
                                    Text(events.size.toString(),style = MaterialTheme.typography.labelLarge,color = colors.onSurfaceVariant)
                                    Spacer(Modifier.width(12.dp))
                                    Icon(if (expanded) SvitloIcons.Up else SvitloIcons.Down,if (expanded) "Згорнути дату" else "Відкрити дату")
                                }
                            }
                        }
                        if (expanded) {
                            items(events.take(dayLimits[key] ?: 10),key = { "event:${it.id}" }) { incident ->
                                HistoryEvent(incident,openIncident == incident.id) { openIncident = incident.id.takeUnless { openIncident == it } }
                            }
                            if (events.size > (dayLimits[key] ?: 10)) item(key = "more:$key") {
                                TextButton(onClick = { dayLimits[key] = (dayLimits[key] ?: 10)+10 }) { Text("Ще 10 подій") }
                            }
                        }
                    }
                    if (days.size > shown) item { TextButton(onClick = { shown += 30 }) { Text("Ще дати") } }

                }
            }
        }
    }
}

@Composable private fun HistoryEvent(incident: HistoryIncident, expanded: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val clock = DateTimeFormatter.ofPattern("HH:mm")
    fun time(at: Long) = Instant.ofEpochMilli(at).atZone(KYIV).format(clock)
    Surface(onClick = onClick,shape = MaterialTheme.shapes.medium,color = colors.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(if (incident.emergency) SvitloIcons.Warning else SvitloIcons.Bolt,null,
                    tint = if (incident.emergency) colors.error else colors.primary,modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(time(incident.start),style = MaterialTheme.typography.titleLarge)
                    Text(if (incident.emergency) "Аварійне" else if (incident.reason.contains("ГПВ")) "ГПВ" else "Відключення",
                        style = MaterialTheme.typography.bodyMedium)
                }
                Text(incident.disappearedAt?.let { "Знято ${time(it)}" } ?: "Не закрито",style = MaterialTheme.typography.bodyMedium,color = colors.onSurfaceVariant)
            }
            if (expanded) {
                if (incident.reason.isNotBlank()) Text(incident.reason,style = MaterialTheme.typography.bodyMedium)
                Text("Останнє спостереження: ${Instant.ofEpochMilli(incident.lastSeen).atZone(KYIV).format(historyDate)}",style = MaterialTheme.typography.bodyMedium)
                incident.estimatedEnd?.let { Text("Прогноз: ${Instant.ofEpochMilli(it).atZone(KYIV).format(historyDate)}",style = MaterialTheme.typography.bodyMedium) }
                Text(if (incident.disappearedAt != null) "Завершення — між останнім спостереженням і зняттям з даних джерела." else "Закриття події в даних джерела ще не зафіксовано.",
                    style = MaterialTheme.typography.bodyMedium,color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun HistoryBreakdown(report: HistoryReport) {
    val colors = MaterialTheme.colorScheme
    val rows = listOf(
        Triple(PowerState.NO_SCHEDULED_OUTAGE,"Без відключень за графіком",colors.primary),
        Triple(PowerState.CONFIRMED_OUTAGE,"Заплановані / адресні",Color(0xFFD78816)),
        Triple(PowerState.EMERGENCY_OUTAGE,"Аварійні",Color(0xFFD64545)),
        Triple(PowerState.SCHEDULED_OUTAGE,"За графіком",Color(0xFF3667AF)),
        Triple(PowerState.POSSIBLE_OUTAGE,"За старим графіком",Color(0xFFD78816)),
        Triple(PowerState.UNKNOWN,"Невідомо",colors.outlineVariant)).filter { report.millis(it.first) > 0 }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val description = rows.joinToString(". ") { (state,label,_) -> "$label: ${historyDuration(report.millis(state))}" }
        Canvas(Modifier.fillMaxWidth().height(18.dp).semantics { contentDescription = description }) {
            var x = 0f
            rows.forEach { (state,_,color) ->
                val width = if (report.totalMillis > 0) size.width * report.millis(state).toFloat() / report.totalMillis else 0f
                drawRect(color,Offset(x,0f),Size(width,size.height))
                if (state == PowerState.POSSIBLE_OUTAGE) clipRect(x,0f,x+width,size.height) {
                    var line = x-size.height
                    while (line < x+width) { drawLine(Color.White.copy(alpha = 0.7f),Offset(line, size.height),Offset(line+size.height,0f),2.dp.toPx()); line += 7.dp.toPx() }
                }
                x += width
            }
        }
        rows.forEach { (state,label,color) ->
            Row(verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(Modifier.size(10.dp),shape = MaterialTheme.shapes.small,color = color) {}
                Text(label,Modifier.weight(1f),style = MaterialTheme.typography.bodyMedium)
                Text(historyDuration(report.millis(state)),style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}
