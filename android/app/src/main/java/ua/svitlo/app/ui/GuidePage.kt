@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package ua.svitlo.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ua.svitlo.app.domain.*

@Composable internal fun GuidePage(state: AppState,onBack: () -> Unit,onAndroidSettings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    FullScreenPage("Про застосунок",onBack) {
        LazyColumn(Modifier.fillMaxSize(),contentPadding = PaddingValues(20.dp),verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Text("Світло",style = MaterialTheme.typography.headlineMedium)
                Text("Ваші адреси. Графіки Вінниччини.",color = colors.onSurfaceVariant,style = MaterialTheme.typography.bodyMedium)
            }
            item { LegendGuide() }
            item { SettingsGroup("Попередження") {
                PillExample()
                Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("За 15 хв",style = MaterialTheme.typography.labelLarge)
                    Text("Початок",style = MaterialTheme.typography.labelLarge)
                    Text("Відновлення",style = MaterialTheme.typography.labelLarge)
                }
                Text("Пігулка → зникає → звичайне сповіщення",style = MaterialTheme.typography.bodySmall,color = colors.onSurfaceVariant)
                Text("План не підтверджує фактичного відновлення світла.",style = MaterialTheme.typography.bodySmall,color = colors.onSurfaceVariant)
            } }
            item { Disclosure("Віджет і екран блокування") {
                Text("Утримуйте робочий стіл → Віджети → Світло.")
                Text("Адреси та порядок налаштовуються окремо для кожного віджета.")
                Text("Samsung AOD: Good Lock → LockStar, якщо підтримується пристроєм.",style = MaterialTheme.typography.bodySmall)
            } }
            item { Disclosure("Дані й оновлення") {
                Text("Погодинний план завантажується з GitHub за чергою. Для Вінницької області можна додатково перевіряти адресу в офіційному журналі VOE, включно із селами. Дані не вимірюють напругу у вашому будинку.")
                Text("Зазвичай перевіряємо раз на 30 хв, під час відключень — раз на 15 хв, а також на межах подій. Android може затримувати фонові задачі.",style = MaterialTheme.typography.bodySmall)
                Text("Якщо дані прострочені — повторюємо перевірку автоматично. Помилка мережі не означає, що відключень немає.",style = MaterialTheme.typography.bodySmall)
                state.syncStats?.let { Text("${it.succeeded} оновлень · ${it.failed} помилок",style = MaterialTheme.typography.labelMedium) }
            } }
            item { Disclosure("Що зберігається") {
                Text("Місця, налаштування й історія — на цьому пристрої. План отримуємо з GitHub. Для ввімкненої перевірки адресних подій — з voe.com.ua. Адреса зіставляється локально, до VOE надсилається лише район.")
                Text("«Я зараз тут» — ручний вибір місця, без GPS.",style = MaterialTheme.typography.bodySmall)
            } }
            item { TextButton(onClick = onAndroidSettings,modifier = Modifier.fillMaxWidth()) { Text("Сповіщення Android") } }
        }
    }
}

@Composable internal fun LegendGuide() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val states = listOf(PowerState.SCHEDULED_OUTAGE,PowerState.NO_SCHEDULED_OUTAGE,PowerState.UNKNOWN)
    SettingsGroup("Як читати графік") {
        states.forEachIndexed { index,state ->
            Surface(onClick = { selected = index },shape = MaterialTheme.shapes.medium,
                color = if (selected.coerceIn(states.indices) == index) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PowerSwatch(state)
                    Text(powerLabel(state),Modifier.weight(1f),style = MaterialTheme.typography.bodyMedium)
                    if (selected.coerceIn(states.indices) == index) Icon(SvitloIcons.Check,null,Modifier.size(18.dp))
                }
            }
        }
        Text(when (states[selected.coerceIn(states.indices)]) {
            PowerState.SCHEDULED_OUTAGE -> "Опублікований інтервал ГПВ. Це план, а не повідомлення про фактичну відсутність напруги."
            PowerState.NO_SCHEDULED_OUTAGE -> "У відомому графіку немає відключень."
            else -> "Немає свіжих даних для цих годин."
        },style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun PillExample() {
    Surface(shape = MaterialTheme.shapes.extraLarge,color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp,vertical = 12.dp),verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(androidx.compose.ui.res.painterResource(ua.svitlo.app.R.drawable.ic_bolt_off),null)
            Column(Modifier.weight(1f)) {
                Text("Дім · скоро вимкнення",style = MaterialTheme.typography.titleSmall)
                Text("Приклад попередження",style = MaterialTheme.typography.labelSmall)
            }
            Text("15 хв",style = MaterialTheme.typography.titleMedium)
        }
    }
}
