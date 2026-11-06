package ua.svitlo.app.ui

import android.os.Build
import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun SvitloTheme(dark: Boolean? = null, content: @Composable () -> Unit) {
    val (appearance, _) = rememberAppearance()
    val systemDark = isSystemInDarkTheme()
    val useDark = dark ?: when (appearance.mode) {
        AppThemeMode.SYSTEM -> systemDark
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme = if (appearance.accent == AppAccent.WALLPAPER && appearance.wallpaperColor == null && Build.VERSION.SDK_INT >= 31) {
        if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else accentScheme(useDark, appearance.accent, if(appearance.accent==AppAccent.WALLPAPER)appearance.wallpaperColor else if(appearance.accent==AppAccent.CUSTOM)appearance.customColor else null)
    val view = LocalView.current
    SideEffect {
        var host = context
        while (host is ContextWrapper && host !is Activity) host = host.baseContext
        (host as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !useDark
                isAppearanceLightNavigationBars = !useDark
            }
        }
    }
    BaseTheme(scheme) { TimelineInteractionHost(content) }
}

internal fun accentScheme(dark: Boolean, accent: AppAccent, customColor:Long? = null): ColorScheme {
    val base = if (dark) Night else Day
    if (customColor==null && (accent == AppAccent.GREEN || accent == AppAccent.WALLPAPER)) return base
    val seed = Color(customColor ?: accent.day)
    val primary = if(customColor==null)Color(if(dark)accent.night else accent.day) else accessibleAccent(seed,base.background)
    val onAccent = contrastingInk(primary)
    val ink = Color(0xFF17191C)
    val paper = Color(0xFFFAFAFC)
    val container = lerp(if (dark) ink else paper, seed, if (dark) 0.40f else 0.14f)
    fun surface(day: Long, night: Long, tint: Float) = lerp(Color(if (dark) night else day), seed, tint)
    return base.copy(primary = primary, onPrimary = onAccent,
        primaryContainer = container, onPrimaryContainer = if (dark) paper else ink,
        inversePrimary = if(customColor==null)Color(if(dark)accent.day else accent.night) else accessibleAccent(seed,if(dark)Day.background else Night.background), surfaceTint = primary,
        secondary = primary, onSecondary = onAccent,
        secondaryContainer = container, onSecondaryContainer = if (dark) paper else ink,
        tertiary = primary, onTertiary = onAccent,
        tertiaryContainer = container, onTertiaryContainer = if (dark) paper else ink,
        background = surface(0xFFFAFAFC, 0xFF101114, 0.025f),
        surface = surface(0xFFFAFAFC, 0xFF101114, 0.025f),
        surfaceContainerLowest = if (dark) Color(0xFF0B0C0E) else Color.White,
        surfaceContainerLow = surface(0xFFF3F3F6, 0xFF191B1E, 0.035f),
        surfaceContainer = surface(0xFFEDEDF0, 0xFF202226, 0.045f),
        surfaceContainerHigh = surface(0xFFE6E6EA, 0xFF292B30, 0.055f),
        surfaceContainerHighest = surface(0xFFE0E0E5, 0xFF34363B, 0.065f),
        onSurface = if (dark) Color(0xFFE5E5EA) else ink,
        onBackground = if (dark) Color(0xFFE5E5EA) else ink,
        onSurfaceVariant = if (dark) Color(0xFFC5C5CE) else Color(0xFF45454E),
        outline = if (dark) Color(0xFF92929C) else Color(0xFF75757E),
        outlineVariant = if (dark) Color(0xFF45454F) else Color(0xFFC7C7D0))
}

internal fun contrastingInk(color:Color):Color = if(color.luminance()>0.179f)Color.Black else Color.White
internal fun accessibleAccent(seed:Color,background:Color):Color {
    fun contrast(a:Color,b:Color):Float = (maxOf(a.luminance(),b.luminance())+0.05f)/(minOf(a.luminance(),b.luminance())+0.05f)
    val target=contrastingInk(background)
    for(step in 0..100) {
        val candidate=lerp(seed,target,step/100f)
        if(contrast(candidate,background)>=4.5f)return candidate
    }
    return target
}
