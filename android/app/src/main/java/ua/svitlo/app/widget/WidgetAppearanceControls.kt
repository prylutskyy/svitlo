package ua.svitlo.app.widget

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import ua.svitlo.app.domain.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WidgetAppearanceControls(config: WidgetConfig, onChange: (WidgetConfig) -> Unit) {
    if (!config.alwaysShowGraphs) {
        FocusAppearanceControls(config,onChange)
        return
    }
    val context = LocalContext.current
    // Also refresh system colour resources when the system's theme overlay changes.
    val configuration = LocalConfiguration.current
    val colors = WidgetPalette.resolve(context, config)
    val wallpaper = remember(configuration, colors.dark) { WidgetPalette.wallpaperChoices(context, colors.dark) }
    var picking by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ua.svitlo.app.ui.SettingSwitch("Прозорий фон",config.transparentBackground,{ onChange(config.copy(transparentBackground = it)) },description = "Прозорий фон віджета")
        if (config.transparentBackground) {
            Text("Текст на шпалерах",style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to "Авто","light" to "Світлий","dark" to "Темний").forEach { (key,title) ->
                    FilterChip(config.transparentText == key,{ onChange(config.copy(transparentText = key)) },label = { Text(title) })
                }
            }
        }
        if (!config.monochrome) {
            Text("Акцент",style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(config.accentSource == null,{ onChange(config.copy(accentSource = null)) },label = { Text("Стандартний") })
                wallpaper.forEach { choice -> ColorChip(choice,config.accentSource == choice.key) { onChange(config.copy(accentSource = choice.key)) } }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WidgetAccent.presets.forEach { choice ->
                    ColorChip(choice,config.accentSource == "custom" && config.accentColor == choice.color) {
                        onChange(config.copy(accentSource = "custom",accentColor = choice.color))
                    }
                }
            }
            OutlinedButton(onClick = { picking = true }) {
                Text(if (config.accentSource == "custom") "Власний колір · ${WidgetAccent.hex(config.accentColor ?: 0)}" else "Власний колір…")
            }
        }
    }
    if (picking) WidgetColorDialog(config.accentColor ?: colors.accent ?: WidgetAccent.presets.first().color,
        onDismiss = { picking = false }, onSelected = { onChange(config.copy(accentSource = "custom", accentColor = it)); picking = false })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FocusAppearanceControls(config: WidgetConfig,onChange: (WidgetConfig) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val current = when {
        config.monochrome -> "mono"
        config.transparentBackground -> "clear"
        else -> config.surfaceStyle ?: "auto"
    }
    Text("Оформлення",style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("auto" to "Авто","light" to "Світлий","dark" to "Темний","tinted" to "Тонований","clear" to "Прозорий","mono" to "Чорно-білий").forEach { (key,title) ->
            FilterChip(current == key,onClick = { onChange(config.copy(monochrome = key == "mono",
                transparentBackground = key == "clear",surfaceStyle = key.takeIf { it in listOf("light","dark","tinted") })) },label = { Text(title) })
        }
    }
    Text(when (current) {
        "clear" -> "Картка без фону. Підкладка в перегляді — лише демонстраційні шпалери."
        "tinted" -> "Один відтінок об'єднує картку. Колір аварії залишається виразним."
        "auto" -> "Світлий або темний вигляд разом із системою."
        "mono" -> "Лише чорний, білий та сірий."
        else -> "Вигляд цієї картки незалежно від теми телефону."
    },style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (current == "tinted") {
        Text("Відтінок",style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WidgetAccent.presets.forEach { choice -> ColorChip(choice,config.accentSource == "custom" && config.accentColor == choice.color) {
                onChange(config.copy(accentSource = "custom",accentColor = choice.color))
            } }
        }
        TextButton(onClick = { picking = true }) { Text("Власний відтінок…") }
    }
    if (current == "clear") {
        Text("Текст на шпалерах",style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null to "Авто","light" to "Світлий текст","dark" to "Темний текст").forEach { (key,title) ->
                FilterChip(config.transparentText == key,{ onChange(config.copy(transparentText = key)) },label = { Text(title) })
            }
        }
    }
    if (picking) WidgetColorDialog(config.accentColor ?: 0xFF3567BE.toInt(),{ picking = false },{
        onChange(config.copy(accentSource = "custom",accentColor = it)); picking = false
    })
}

@Composable
private fun ColorChip(choice: AccentChoice, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(choice.name) }, leadingIcon = {
        Box(Modifier.size(20.dp).background(Color(choice.color), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
    })
}

/** A local colour picker; no screenshot, wallpaper or storage permission is needed. */
@Composable
private fun WidgetColorDialog(initial: Int, onDismiss: () -> Unit, onSelected: (Int) -> Unit) {
    val initialHsv = remember(initial) { FloatArray(3).also { AndroidColor.colorToHSV(initial, it) } }
    var hue by rememberSaveable { mutableFloatStateOf(initialHsv[0]) }
    var saturation by rememberSaveable { mutableFloatStateOf(initialHsv[1]) }
    var brightness by rememberSaveable { mutableFloatStateOf(initialHsv[2]) }
    var hex by rememberSaveable { mutableStateOf(WidgetAccent.hex(initial)) }
    val parsed = WidgetAccent.parseHex(hex)
    fun update(h: Float = hue, s: Float = saturation, v: Float = brightness) {
        hue = h; saturation = s; brightness = v
        hex = WidgetAccent.hex(AndroidColor.HSVToColor(floatArrayOf(h, s, v)))
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Власний колір") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().height(48.dp).background(Color(parsed ?: initial), MaterialTheme.shapes.small))
            OutlinedTextField(hex, { value ->
                hex = value.take(9)
                WidgetAccent.parseHex(hex)?.let { color ->
                    val hsv = FloatArray(3).also { AndroidColor.colorToHSV(color, it) }
                    hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]
                }
            }, label = { Text("HEX · #RRGGBB") }, singleLine = true, isError = parsed == null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                supportingText = { if (parsed == null) Text("Введіть 6 символів: 0–9, A–F") }, modifier = Modifier.fillMaxWidth())
            Text("Відтінок", style = MaterialTheme.typography.labelLarge)
            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().height(8.dp).background(Brush.horizontalGradient(
                    (0..6).map { Color(AndroidColor.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }), CircleShape))
                Slider(hue, { update(h = it) }, valueRange = 0f..360f,
                    colors = SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent),
                    modifier = Modifier.semantics { contentDescription = "Відтінок власного кольору" })
            }
            ua.svitlo.app.ui.Disclosure("Точне налаштування") {
            Text("Насиченість", style = MaterialTheme.typography.labelLarge)
            Slider(saturation, { update(s = it) }, modifier = Modifier.semantics { contentDescription = "Насиченість власного кольору" })
            Text("Яскравість", style = MaterialTheme.typography.labelLarge)
            Slider(brightness, { update(v = it) }, modifier = Modifier.semantics { contentDescription = "Яскравість власного кольору" })
            }

        }
    }, confirmButton = { TextButton(enabled = parsed != null, onClick = { parsed?.let(onSelected) }) { Text("Вибрати колір") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } })
}

@Composable
internal fun TransparencyGrid(dark: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cell = 12.dp.toPx()
        val a = if (dark) Color(0xFF22252A) else Color(0xFFE8EBE6)
        val b = if (dark) Color(0xFF30343A) else Color(0xFFF8FAF5)
        for (row in 0..(size.height / cell).toInt()) for (column in 0..(size.width / cell).toInt()) {
            drawRect(if ((row + column) % 2 == 0) a else b,
                androidx.compose.ui.geometry.Offset(column * cell, row * cell), androidx.compose.ui.geometry.Size(cell, cell))
        }
    }
}
