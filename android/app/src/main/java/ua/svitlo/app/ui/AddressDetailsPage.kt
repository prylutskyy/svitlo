package ua.svitlo.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ua.svitlo.app.R
import ua.svitlo.app.domain.*
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable internal fun AddressDetailsPage(address: SavedAddress,schedule: Schedule?,attempt: FetchAttempt?,now: Instant,
    changedAt: Long?,onBack: () -> Unit,onHistory: () -> Unit,onFeed: () -> Unit = {},
    onRename: (String)->Unit = {},refreshing:Boolean = false,onRefresh:()->Unit = {}) {
    val identity=homeIdentity(address)
    val plan=remember(address,schedule) {homePlan(address,schedule)}
    val colors=MaterialTheme.colorScheme
    var menu by remember {mutableStateOf(false)}
    var renaming by remember {mutableStateOf(false)}
    var name by rememberSaveable(identity.title) {mutableStateOf(identity.title)}
    val pager=rememberPagerState(pageCount={2})
    val scope=rememberCoroutineScope()
    val today=now.atZone(KYIV).toLocalDate()
    FullScreenPage(identity.title,onBack) {
        HomeRefreshContainer(refreshing,onRefresh,Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(identity.subtitle,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=colors.onSurfaceVariant)
                        Box {
                            IconButton(onClick={menu=true}) {Icon(SvitloIcons.Settings,"Налаштування місця")}
                            DropdownMenu(menu,{menu=false}) {
                                DropdownMenuItem(text={Text("Перейменувати")},onClick={menu=false;name=identity.title;renaming=true})
                                DropdownMenuItem(text={Text("Оператор і черга")},onClick={menu=false;onFeed()})
                            }
                        }
                    }
                }
                if(attempt?.error!=null) item {Text("Не вдалося оновити. Показано збережені дані.",color=colors.error,style=MaterialTheme.typography.bodySmall)}
                else if(plan?.stale(now)==true) item {Text("Графік застарів",color=colors.error,style=MaterialTheme.typography.bodySmall)}
                if(plan!=null) item {
                    Surface(shape=MaterialTheme.shapes.large,color=colors.surfaceContainerLow) {
                        Column(Modifier.padding(16.dp)) {ScheduleTimeline(plan,now,notificationWindow(now),"Найближчі години")}
                    }
                }
                item {
                    Surface(shape=MaterialTheme.shapes.large,color=colors.surfaceContainerLow) {
                        Column {
                            TabRow(selectedTabIndex=pager.currentPage,containerColor=colors.surfaceContainerLow) {
                                listOf("Сьогодні","Завтра").forEachIndexed {index,label ->
                                    Tab(selected=pager.currentPage==index,onClick={scope.launch {pager.animateScrollToPage(index)}},text={Text(label)})
                                }
                            }
                            HorizontalPager(state=pager,modifier=Modifier.fillMaxWidth().semantics {contentDescription="Розклад за днями"},verticalAlignment=Alignment.Top) {page ->
                                val date=today.plusDays(page.toLong())
                                val events=remember(schedule,date,now) {detailsIntervals(schedule,date,now)}
                                Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                                    Text(date.format(DateTimeFormatter.ofPattern("dd.MM")),style=MaterialTheme.typography.labelMedium,color=colors.onSurfaceVariant)
                                    when {
                                        events==null -> Text(if(schedule==null) "Очікуємо графік" else "Графік ще не опубліковано",style=MaterialTheme.typography.bodyLarge)
                                        events.isEmpty() -> Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                            Icon(painterResource(R.drawable.ic_bolt),null,tint=colors.primary)
                                            Text("Відключень не заплановано",style=MaterialTheme.typography.bodyLarge)
                                        }
                                        else -> events.forEachIndexed {index,event ->
                                            if(index>0) HorizontalDivider(color=colors.outlineVariant.copy(alpha=.4f))
                                            DetailIntervalRow(event,date)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item {FilledTonalButton(onClick=onHistory,modifier=Modifier.fillMaxWidth()) {
                    Icon(SvitloIcons.History,null);Spacer(Modifier.width(8.dp));Text("Історія та статистика")
                }}
            }
        }
    }
    if(renaming) AlertDialog(onDismissRequest={renaming=false},title={Text("Назва місця")},
        text={OutlinedTextField(name,{name=it.take(60)},label={Text("Дім, батьки, робота…")},singleLine=true)},
        confirmButton={TextButton(onClick={onRename(name.trim());renaming=false},enabled=name.isNotBlank()) {Text("Зберегти")}},
        dismissButton={TextButton(onClick={renaming=false}) {Text("Скасувати")}})
}

@Composable private fun DetailIntervalRow(event:DetailInterval,date:java.time.LocalDate) {
    val colors=MaterialTheme.colorScheme
    val accent=outageColor(event.state)
    val clock=DateTimeFormatter.ofPattern("HH:mm")
    val start=event.start.atZone(KYIV).format(clock)
    val end=event.end?.let {if(it==date.plusDays(1).atStartOfDay(KYIV).toInstant()) "24:00" else it.atZone(KYIV).format(clock)}
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Surface(shape=RoundedCornerShape(12.dp),color=accent.copy(alpha=.1f)) {
            Box(Modifier.size(40.dp),contentAlignment=Alignment.Center) {
                if(event.state==PowerState.EMERGENCY_OUTAGE) Icon(SvitloIcons.Warning,"Аварійне відключення",tint=accent)
                else Icon(painterResource(if(event.state==PowerState.UNKNOWN) R.drawable.ic_power_unknown else R.drawable.ic_bolt_off),null,tint=accent)
            }
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text(if(end==null) "З $start" else "$start–$end",style=MaterialTheme.typography.titleMedium)
            Text(event.label+(if(event.estimated) " · орієнтовно" else ""),style=MaterialTheme.typography.bodySmall,color=accent)
        }
        Text(event.end?.let {remainingTime(it,event.start)} ?: "Уточнюється",style=MaterialTheme.typography.labelLarge,color=colors.onSurfaceVariant)
    }
}
