package ua.svitlo.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
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
    AMBER("Бурштиновий", 0xFF805600, 0xFFF7BE53)
}
internal data class AppAppearance(val mode: AppThemeMode = AppThemeMode.SYSTEM, val accent: AppAccent = AppAccent.GREEN)
internal class AppearancePreferences(context: Context) {
    val preferences: SharedPreferences = context.applicationContext.getSharedPreferences("app-appearance", Context.MODE_PRIVATE)
    fun read() = AppAppearance(
        AppThemeMode.entries.firstOrNull { it.name == preferences.getString("theme", null) } ?: AppThemeMode.SYSTEM,
        AppAccent.entries.firstOrNull { it.name == preferences.getString("accent", null) } ?: AppAccent.GREEN)
    fun theme(mode: AppThemeMode) { preferences.edit().putString("theme", mode.name).apply() }
    fun accent(accent: AppAccent) { preferences.edit().putString("accent", accent.name).apply() }
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
                                Surface(Modifier.width(24.dp).height(4.dp),color = Color(appearance.accent.day),shape = MaterialTheme.shapes.small) {}
                                Surface(Modifier.fillMaxWidth().height(4.dp),color = if (day) Color.LightGray else Color.Gray,shape = MaterialTheme.shapes.small) {}
                            }
                        }
                        Text(mode.label,Modifier.weight(1f).padding(start = 16.dp))
                        RadioButton(appearance.mode == mode,onClick = null)
                    }
                }
            }
            SettingsGroup("Акцентний колір") {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppAccent.entries.filter { it != AppAccent.WALLPAPER || Build.VERSION.SDK_INT >= 31 }.forEach { accent ->
                        FilterChip(appearance.accent == accent,{ store.accent(accent) },label = { Text(accent.label) },leadingIcon = {
                            Surface(Modifier.size(20.dp),shape = MaterialTheme.shapes.small,
                                color = if (accent == AppAccent.WALLPAPER && Build.VERSION.SDK_INT >= 31) dynamicLightColorScheme(LocalContext.current).primary else Color(accent.day)) {}
                        })
                    }
                }
            }
            Text("Віджети налаштовуються окремо.",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
