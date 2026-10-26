package ua.svitlo.app.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import androidx.core.graphics.ColorUtils
import ua.svitlo.app.domain.*

object WidgetPalette {
    fun wallpaperChoices(context: Context, dark: Boolean): List<AccentChoice> {
        if (Build.VERSION.SDK_INT < 31) return emptyList()
        val ids = if (dark) listOf(android.R.color.system_accent1_200, android.R.color.system_accent2_200, android.R.color.system_accent3_200)
            else listOf(android.R.color.system_accent1_600, android.R.color.system_accent2_600, android.R.color.system_accent3_600)
        return ids.mapIndexed { index, resource -> AccentChoice("wallpaper${index + 1}", "Зі шпалер ${index + 1}", context.getColor(resource)) }
    }

    fun resolve(context: Context, config: WidgetConfig): WidgetColors {
        val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val dark = if (config.transparentBackground) when (config.transparentText) {
            "light" -> true
            "dark" -> false
            else -> systemDark || config.monochrome
        } else when (config.surfaceStyle) { "light" -> false; "dark" -> true; else -> systemDark || config.monochrome }
        val base = if (config.monochrome) {
            if (dark) Color.BLACK else Color.WHITE
        } else if (dark) Color.rgb(25, 26, 28) else Color.rgb(248, 249, 250)
        val raw = if (config.monochrome) null else when (config.accentSource) {
            "custom" -> config.accentColor?.let { it or 0xFF000000.toInt() }
            else -> wallpaperChoices(context, dark).firstOrNull { it.key == config.accentSource }?.color
                ?: 0xFF3567BE.toInt().takeIf { config.surfaceStyle == "tinted" }
        }
        // RemoteViews background tinting is available from API 31. Older hosts keep
        // their rounded base surface; header and clock accents still work there.
        val background = if (raw == null || Build.VERSION.SDK_INT < 31) base
            else ColorUtils.blendARGB(base, raw, if (config.surfaceStyle == "tinted") { if (dark) .25f else .18f } else if (dark) 0.10f else 0.07f)
        // Avoid unreadable clock/header colours, even with a very pale or dark custom RGB.
        val accent = raw?.let { color ->
            val target = if (dark) Color.WHITE else Color.BLACK
            (0..20).asSequence().map { ColorUtils.blendARGB(color, target, it / 20f) }
                .first { ColorUtils.calculateContrast(it, background) >= 4.5 }
        }
        return WidgetColors(if (config.transparentBackground) Color.TRANSPARENT else background,
            if (config.monochrome) { if (dark) Color.WHITE else Color.BLACK }
                else if (dark) Color.rgb(240, 242, 245) else Color.rgb(29, 32, 37),
            if (config.monochrome) { if (dark) Color.LTGRAY else Color.DKGRAY }
            else if (dark) Color.rgb(174, 182, 192) else Color.rgb(91, 99, 110), accent, dark)
    }
}
