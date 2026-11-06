@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package ua.svitlo.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

internal enum class AppThemeMode(val label: String) {
    SYSTEM("Як у системі"), LIGHT("Світла"), DARK("Темна")
}
internal enum class AppAccent(val label: String, val day: Long, val night: Long) {
    GREEN("Зелений", 0xFF245E46, 0xFFA0D3B3),
    WALLPAPER("Кольори шпалер", 0xFF526878, 0xFFABCADD),
    BLUE("Синій", 0xFF245EA8, 0xFFA9C7FF),
    PURPLE("Фіолетовий", 0xFF76509B, 0xFFDEB7FF),
    TEAL("Бірюзовий", 0xFF006A68, 0xFF80D5D0),
    ROSE("Рожевий", 0xFF984467, 0xFFFFB0CE),
    AMBER("Бурштиновий", 0xFF805600, 0xFFF7BE53),
    CUSTOM("Власний", 0xFF245E46, 0xFFA0D3B3)
}
internal data class AppAppearance(val mode: AppThemeMode = AppThemeMode.SYSTEM, val accent: AppAccent = AppAccent.GREEN, val customColor: Long? = null, val wallpaperColor: Long? = null)
internal class AppearancePreferences(context: Context) {
    val preferences: SharedPreferences = context.applicationContext.getSharedPreferences("app-appearance", Context.MODE_PRIVATE)
    fun read() = AppAppearance(
        AppThemeMode.entries.firstOrNull { it.name == preferences.getString("theme", null) } ?: AppThemeMode.SYSTEM,
        AppAccent.entries.firstOrNull { it.name == preferences.getString("accent", null) } ?: AppAccent.GREEN,
        preferences.takeIf { it.contains("custom-color") }?.getLong("custom-color",0),
        preferences.takeIf { it.contains("wallpaper-color") }?.getLong("wallpaper-color",0))
    fun theme(mode: AppThemeMode) { preferences.edit().putString("theme", mode.name).apply() }
    fun accent(accent: AppAccent) { preferences.edit().putString("accent", accent.name).remove("wallpaper-color").apply() }
    fun customColor(color: Long) { preferences.edit().putString("accent",AppAccent.CUSTOM.name).putLong("custom-color",color).remove("wallpaper-color").apply() }
    fun wallpaperColor(color: Long) { preferences.edit().putString("accent",AppAccent.WALLPAPER.name).putLong("wallpaper-color",color).apply() }
}
@Composable
internal fun rememberAppearance(): Pair<AppAppearance, AppearancePreferences> {
    val context = LocalContext.current
    val store = remember(context) { AppearancePreferences(context) }
    var appearance by remember(store) { mutableStateOf(store.read()) }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> appearance = store.read() }
        store.preferences.registerOnSharedPreferenceChangeListener(listener)
        appearance = store.read()
        onDispose { store.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return appearance to store
}
@Composable
internal fun AppearancePage(onBack: () -> Unit) {
    val (appearance, store) = rememberAppearance()
    val context = LocalContext.current
    val palette = remember(context) { wallpaperPalette(context) }
    var moreTones by rememberSaveable { mutableStateOf(false) }
    var hex by rememberSaveable { mutableStateOf(appearance.customColor?.let { "%06X".format(it and 0xFFFFFF) }.orEmpty()) }
    FullScreenPage("Налаштування", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            SettingsGroup("Тема") {
                AppThemeMode.entries.forEach { mode ->
                    Row(Modifier.fillMaxWidth().selectable(appearance.mode == mode,role = Role.RadioButton,
                        onClick = { store.theme(mode) }).heightIn(min = 52.dp),verticalAlignment = Alignment.CenterVertically) {
                        val day = mode != AppThemeMode.DARK
                        Surface(Modifier.size(width = 56.dp,height = 36.dp),shape = MaterialTheme.shapes.small,
                            color = if (day) Color(0xFFF4F5F6) else Color(0xFF202124),border = androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
                            Column(Modifier.padding(8.dp),verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Surface(Modifier.width(24.dp).height(4.dp),color = MaterialTheme.colorScheme.primary,shape = MaterialTheme.shapes.small) {}
                                Surface(Modifier.fillMaxWidth().height(4.dp),color = if (day) Color.LightGray else Color.Gray,shape = MaterialTheme.shapes.small) {}
                            }
                        }
                        Text(mode.label,Modifier.weight(1f).padding(start = 16.dp))
                        RadioButton(appearance.mode == mode,onClick = null)
                    }
                }
            }
            SettingsGroup("Акцентний колір") {
                if (Build.VERSION.SDK_INT >= 31) {
                    Text("Кольори шпалер",style=MaterialTheme.typography.titleSmall)
                    FilterChip(appearance.accent==AppAccent.WALLPAPER && appearance.wallpaperColor==null,
                        {store.accent(AppAccent.WALLPAPER)},label={Text("Автоматично")})
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        (if(moreTones)palette else palette.filterIndexed { index,_->index%13==6 }).forEach { tone ->
                            val selected=appearance.accent==AppAccent.WALLPAPER && appearance.wallpaperColor==tone
                            Surface(onClick={store.wallpaperColor(tone)},modifier=Modifier.size(48.dp).semantics { contentDescription="Колір шпалер #${"%06X".format(tone and 0xFFFFFF)}"; this.selected=selected },shape=CircleShape,color=Color(tone),
                                border=androidx.compose.foundation.BorderStroke(if(selected)3.dp else 1.dp,MaterialTheme.colorScheme.outline)) {
                                Box(contentAlignment=Alignment.Center) { if(selected)Icon(SvitloIcons.Check,"Обраний колір шпалер",tint=contrastingInk(Color(tone))) }
                            }
                        }
                    }
                    TextButton(onClick={moreTones=!moreTones}) {Text(if(moreTones)"Менше відтінків" else "Усі відтінки шпалер")}
                    HorizontalDivider()
                }
                Text("Кольори застосунку",style=MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    AppAccent.entries.filter { it!=AppAccent.WALLPAPER && it!=AppAccent.CUSTOM }.forEach { accent ->
                        FilterChip(appearance.accent == accent,{ store.accent(accent) },label = { Text(accent.label) },leadingIcon = {
                            Surface(Modifier.size(20.dp),shape=CircleShape,color=Color(accent.day)) {}
                        })
                    }
                }
                HorizontalDivider()
                Text("Власний колір",style=MaterialTheme.typography.titleSmall)
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(hex,{hex=it.removePrefix("#").take(6).uppercase()},modifier=Modifier.weight(1f).testTag("custom-accent"),
                        label={Text("HEX")},prefix={Text("#")},placeholder={Text("245E46")},singleLine=true,
                        isError=hex.isNotEmpty() && parseAccentHex(hex)==null)
                    val chosen=appearance.accent==AppAccent.CUSTOM && parseAccentHex(hex)==appearance.customColor
                    FilledTonalButton(onClick={parseAccentHex(hex)?.let(store::customColor)},enabled=parseAccentHex(hex)!=null && !chosen) {Text(if(chosen)"Обрано" else "Обрати")}
                }
            }
            Text("Віджети налаштовуються окремо.",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun parseAccentHex(value:String):Long? = value.removePrefix("#").takeIf {it.matches(Regex("[0-9a-fA-F]{6}"))}?.toLongOrNull(16)?.or(0xFF000000)

/** Public Android tonal palettes, rather than guessed colors from a wallpaper bitmap. */
internal fun wallpaperPalette(context:Context):List<Long> {
    if(Build.VERSION.SDK_INT<31)return emptyList()
    val tones=listOf(0,10,50,100,200,300,400,500,600,700,800,900,1000)
    return (1..3).flatMap { palette -> tones.mapNotNull { tone ->
        val id=context.resources.getIdentifier("system_accent${palette}_${tone}","color","android")
        if(id==0)null else context.getColor(id).toLong() and 0xFFFFFFFF
    } }
}
