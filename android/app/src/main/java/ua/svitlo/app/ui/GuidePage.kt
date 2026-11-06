@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package ua.svitlo.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
            Text("Світло",style=MaterialTheme.typography.headlineLarge)
            Text("Ваші місця та графіки відключень.",color=colors.onSurfaceVariant)
            SettingsGroup("Позначки") {
                listOf(PowerState.SCHEDULED_OUTAGE,PowerState.NO_SCHEDULED_OUTAGE,PowerState.UNKNOWN).forEach { power ->
                    Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        PowerSwatch(power)
                        Text(powerLabel(power),style=MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Text("План за чергою — з GitHub. Адресні події — з журналу оператора. Графік не вимірює напругу.",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
            TextButton(onClick=onAndroidSettings) { Text("Сповіщення Android") }
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
