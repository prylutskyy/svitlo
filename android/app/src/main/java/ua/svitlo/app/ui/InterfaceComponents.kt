package ua.svitlo.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ua.svitlo.app.domain.*
import java.time.Duration
import java.time.format.DateTimeFormatter

@Composable internal fun outageColor(state: PowerState): Color {
    val dark = MaterialTheme.colorScheme.onSurface.luminance() > .5f
    return when (state) {
        PowerState.EMERGENCY_OUTAGE -> if (dark) Color(0xFFFF666B) else Color(0xFFBB252B)
        PowerState.CONFIRMED_OUTAGE, PowerState.POSSIBLE_OUTAGE -> if (dark) Color(0xFFFFBB58) else Color(0xFFA95700)
        PowerState.SCHEDULED_OUTAGE -> if (dark) Color(0xFF8BB7FF) else Color(0xFF3667AF)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
internal fun powerLabel(state: PowerState) = when (state) {
    PowerState.EMERGENCY_OUTAGE -> "Аварійне"
    PowerState.CONFIRMED_OUTAGE -> "Повідомлене відключення"
    PowerState.POSSIBLE_OUTAGE -> "За графіком"
    PowerState.UNKNOWN -> "Немає даних"
    PowerState.SCHEDULED_OUTAGE -> "За графіком"
    PowerState.NO_SCHEDULED_OUTAGE -> "Відключень не позначено"
}

@Composable internal fun PowerSwatch(state: PowerState, modifier: Modifier = Modifier) {
    val ink = outageColor(state)
    val neutral = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.size(width = 60.dp,height = 24.dp)) {
        val y = size.height/2; val thickness = 6.dp.toPx()
        drawLine(neutral,Offset(0f,y),Offset(size.width,y),2.dp.toPx())
        when (state) {
            PowerState.POSSIBLE_OUTAGE -> clipRect(0f,y-thickness/2,size.width,y+thickness/2) {
                var x = -thickness
                while (x < size.width) { drawLine(ink,Offset(x,y+thickness/2),Offset(x+thickness,y-thickness/2),2.dp.toPx()); x += 5.dp.toPx() }
            }
            PowerState.CONFIRMED_OUTAGE,PowerState.EMERGENCY_OUTAGE,PowerState.SCHEDULED_OUTAGE -> drawLine(ink,Offset(0f,y),Offset(size.width,y),thickness)
            PowerState.UNKNOWN -> drawLine(ink.copy(alpha = .5f),Offset(0f,y),Offset(size.width,y),1.dp.toPx())
            else -> Unit
        }
    }
}

@Composable internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title,style = MaterialTheme.typography.titleMedium,color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = MaterialTheme.shapes.large,color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement = Arrangement.spacedBy(12.dp),content = content)
        }
    }
}

@Composable internal fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit,
    subtitle: String? = null, enabled: Boolean = true, description: String = title) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .toggleable(checked,enabled = enabled,role = Role.Switch,onValueChange = onChange)
        .semantics { contentDescription = description },verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title,style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it,style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked,onCheckedChange = null,enabled = enabled)
    }
}

@Composable internal fun Disclosure(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(onClick = { expanded = !expanded },shape = MaterialTheme.shapes.large,color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().animateContentSize().padding(16.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title,Modifier.weight(1f),style = MaterialTheme.typography.titleMedium)
                Icon(if (expanded) SvitloIcons.Up else SvitloIcons.Down,if (expanded) "Згорнути" else "Розгорнути",Modifier.size(20.dp))
            }
            AnimatedVisibility(expanded) { Column(verticalArrangement = Arrangement.spacedBy(8.dp),content = content) }
        }
    }
}

/** One interaction model on the home screen and in address details. */
@Composable internal fun AddressTimeline(chart: NotificationChart, schedule: Schedule? = null, history: List<HistoryInterval> = emptyList(),
    footer: @Composable () -> Unit = {}) {
    val window = TimelineWindow(chart.start,chart.end)
    val source = if (schedule != null) PowerHistory.overlay(schedule.inspectionSpans(window,chart.now),history,chart.now)
        else chart.rows.firstOrNull()?.spans.orEmpty()
    InteractiveTimeline(TimelineNavigation.coalesced(source),window,chart.now,footer)
}
