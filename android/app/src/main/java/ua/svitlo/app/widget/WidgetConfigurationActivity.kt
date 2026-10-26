@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package ua.svitlo.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ua.svitlo.app.MainActivity
import ua.svitlo.app.app
import ua.svitlo.app.domain.WidgetConfig
import ua.svitlo.app.domain.WidgetStyle
import ua.svitlo.app.ui.*
import java.time.Instant
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription

/** A widget needs a location and a theme. Size and typography belong to the chosen preset. */
class WidgetConfigurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id)
        setResult(RESULT_CANCELED,result)
        val manager = AppWidgetManager.getInstance(this)
        val provider = manager.getAppWidgetInfo(id)?.provider
        val style = WidgetFormats.style(provider)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || provider !in WidgetFormats.components(this)) { finish(); return }
        enableEdgeToEdge()
        setContent { WidgetEditorTheme {
            val state by app.store.state.collectAsStateWithLifecycle()
            var selectedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
            var quiet by rememberSaveable { mutableStateOf(false) }
            var inverted by rememberSaveable { mutableStateOf(false) }
            var accent by rememberSaveable { mutableIntStateOf(0xFF447BC4.toInt()) }
            var theme by rememberSaveable { mutableStateOf("auto") }
            var transparent by rememberSaveable { mutableStateOf(false) }
            var scalePercent by rememberSaveable { mutableIntStateOf(100) }
            var showSettings by rememberSaveable { mutableStateOf(true) }
            var rounded by rememberSaveable { mutableStateOf(true) }
            var initialized by rememberSaveable { mutableStateOf(false) }
            var loaded by remember { mutableStateOf(false) }
            var saving by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            var example by rememberSaveable { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            LaunchedEffect(id) {
                try {
                    val saved = app.store.load()
                    if (!initialized) {
                        val existing = saved.widgets.orEmpty().firstOrNull { it.widgetId == id }?.forStyle(saved,Instant.now(),style)
                        selectedIds = existing?.addressIds ?: if (style == WidgetStyle.TIMELINE) saved.addresses.map { it.id }
                            else listOfNotNull(saved.currentAddressId ?: saved.addresses.firstOrNull()?.id)
                        scalePercent = ((existing?.sizeMultiplier ?: 1f)*100).roundToInt()
                        showSettings = existing?.hideSettings != true
                        quiet = existing?.hideWhenQuiet == true
                        inverted = existing?.inverted == true
                        accent = existing?.accentColor ?: 0xFF447BC4.toInt()
                        transparent = existing?.transparentBackground == true
                        rounded = existing?.roundedCorners != false
                        theme = existing?.surfaceStyle?.takeIf { it in listOf("light","dark") } ?: "auto"
                        initialized = true
                    }
                    loaded = true
                } catch (_: Exception) { error = "Не вдалося прочитати адреси" }
            }
            val ids = selectedIds.filter { chosen -> state.addresses.any { it.id == chosen } }
            val config = WidgetConfig(id,ids,contentScale = (scalePercent/100f).takeUnless { scalePercent == 100 },hideSettings = !showSettings,surfaceStyle = theme.takeUnless { it == "auto" },transparentBackground = transparent,roundedCorners = false.takeUnless { rounded },hideWhenQuiet = quiet,inverted = inverted,
                accentSource = "custom".takeIf { style == WidgetStyle.CARD || style == WidgetStyle.TIMELINE },accentColor = accent).forStyle(state,Instant.now(),style)
            val configuration = LocalConfiguration.current
            val default = WidgetFormats.defaultSize(provider)
            val options = manager.getAppWidgetOptions(id)
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,default.first).coerceIn(48,600)
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,default.second).coerceIn(48,800)
            Scaffold(topBar = { TopAppBar(title = { Text("Налаштувати віджет") },navigationIcon = {
                IconButton(onClick = { finish() }) { Icon(SvitloIcons.Back,"Скасувати") }
            }) },bottomBar = {
                Surface(tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                        error?.let { Text(it,color = MaterialTheme.colorScheme.error) }
                        Button(onClick = {
                            saving = true
                            scope.launch {
                                try {
                                    app.updateControl({ latest ->
                                        require(ids.any { chosen -> latest.addresses.any { it.id == chosen } }) { "Оберіть локацію" }
                                        latest.copy(widgets = latest.widgets.orEmpty().filterNot { it.widgetId == id } + config)
                                    },refreshSoon = true)
                                    setResult(RESULT_OK,result); finish()
                                } catch (e: Exception) { error = e.message ?: "Не вдалося зберегти віджет"; saving = false }
                            }
                        },enabled = loaded && ids.isNotEmpty() && !saving,modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text(if (saving) "Зберігаємо…" else "Зберегти")
                        }
                    }
                }
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(WidgetFormats.title(provider),style = MaterialTheme.typography.titleMedium)
                    if (loaded) WidgetPinnedPreview(state,config,this@WidgetConfigurationActivity,width,height,
                        (configuration.screenHeightDp*.28f).coerceIn(100f,220f),Instant.now(),example,{ example = it })
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    SettingsGroup(if (style == WidgetStyle.TIMELINE) "Локації" else "Локація") {
                        state.addresses.forEach { address ->
                            Row(Modifier.fillMaxWidth().selectable(address.id in selectedIds,onClick = {
                                selectedIds = if (style == WidgetStyle.TIMELINE) { if (address.id in selectedIds) selectedIds-address.id else selectedIds+address.id } else listOf(address.id)
                            },role = if (style == WidgetStyle.TIMELINE) Role.Checkbox else Role.RadioButton)
                                .padding(vertical = 8.dp),verticalAlignment = Alignment.CenterVertically) {
                                if (style == WidgetStyle.TIMELINE) Checkbox(address.id in selectedIds,onCheckedChange = null)
                                else RadioButton(address.id in selectedIds,onClick = null)
                                Column(Modifier.weight(1f)) {
                                    Text(address.title,style = MaterialTheme.typography.titleMedium)
                                    Text(address.description,style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (state.addresses.isEmpty()) TextButton(onClick = { startActivity(Intent(this@WidgetConfigurationActivity,MainActivity::class.java)) }) { Text("Додати адресу") }
                    }
                    if (style == WidgetStyle.MONO) SettingSwitch("Інверсія кольорів",inverted,{ inverted = it })
                    else SettingsGroup("Тема") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("auto" to "Системна","light" to "Світла","dark" to "Темна").forEach { (key,label) ->
                                FilterChip(theme == key,{ theme = key },label = { Text(label) })
                            }
                        }
                    }
                    if (style == WidgetStyle.CARD || style == WidgetStyle.TIMELINE) SettingsGroup("Акцент") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0xFF447BC4.toInt() to "Синій",0xFF56824C.toInt() to "Зелений",0xFF9C5E9F.toInt() to "Сливовий",0xFFB57725.toInt() to "Бурштин").forEach { (value,label) ->
                                FilterChip(accent == value,{ accent = value },label = { Text(label) },leadingIcon = { Surface(Modifier.size(12.dp),color = Color(value),shape = androidx.compose.foundation.shape.CircleShape) {} })
                            }
                        }
                    }
                    SettingsGroup("Масштаб вмісту") {
                        Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
                            Text("$scalePercent%",Modifier.weight(1f),style = MaterialTheme.typography.titleLarge)
                            TextButton(onClick = { scalePercent = 100 },enabled = scalePercent != 100) { Text("Скинути") }
                        }
                        Slider(value = scalePercent.toFloat(),onValueChange = { scalePercent = (it/5).roundToInt()*5 },
                            valueRange = 80f..140f,steps = 11,modifier = Modifier.semantics {
                                contentDescription = "Масштаб вмісту віджета"; stateDescription = "$scalePercent%"
                            })
                        Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("80%",style = MaterialTheme.typography.labelSmall)
                            Text("140%",style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (style != WidgetStyle.SHORT) {
                        SettingSwitch("Показувати кнопку ⋯",showSettings,{ showSettings = it })
                    }
                    SettingSwitch("Приховувати без відключень",quiet,{ quiet = it })
                    SettingSwitch("Прозорий фон",transparent,{ transparent = it })
                    SettingSwitch("Заокруглені кути",rounded,{ rounded = it })
                    Text("Без відключень віджет показує спокійний стан. Прихований віджет залишає своє місце на екрані Android.",
                        style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } }
    }
}

@Composable private fun WidgetEditorTheme(content: @Composable () -> Unit) {
    SvitloTheme {
        val dark = androidx.compose.foundation.isSystemInDarkTheme()
        val scheme = MaterialTheme.colorScheme.copy(
            primary = Color(if (dark) 0xFF64A8FF else 0xFF007AFF),
            onPrimary = if (dark) Color(0xFF002C60) else Color.White,
            secondary = Color(if (dark) 0xFF64A8FF else 0xFF007AFF),
            secondaryContainer = Color(if (dark) 0xFF183756 else 0xFFE5F0FF),
            onSecondaryContainer = Color(if (dark) 0xFFD8E9FF else 0xFF174C84),
            background = Color(if (dark) 0xFF000000 else 0xFFF2F2F7),
            surface = Color(if (dark) 0xFF101012 else 0xFFF2F2F7),
            surfaceContainerLow = Color(if (dark) 0xFF1C1C1E else 0xFFFFFFFF),
            surfaceContainer = Color(if (dark) 0xFF1C1C1E else 0xFFF2F2F7),
            surfaceContainerHigh = Color(if (dark) 0xFF242426 else 0xFFEAEAEE),
            surfaceContainerHighest = Color(if (dark) 0xFF2C2C2E else 0xFFE5E5EA),
            onSurfaceVariant = Color(if (dark) 0xFFAEAEB4 else 0xFF5F6068),
            outlineVariant = Color(if (dark) 0xFF48484A else 0xFFD1D1D6))
        MaterialTheme(colorScheme = scheme,shapes = MaterialTheme.shapes.copy(
            medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)),content = content)
    }
}
