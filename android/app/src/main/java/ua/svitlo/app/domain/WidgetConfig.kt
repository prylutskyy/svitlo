package ua.svitlo.app.domain

import kotlin.math.ceil

/** Each placed widget owns its selection independently of the app and other widgets. */
data class WidgetConfig(val widgetId: Int, val addressIds: List<String>, val monochrome: Boolean = false,
                        val hideTitle: Boolean = false, val hideSettings: Boolean = false,
                        val contentScale: Float? = null, val verticalDensity: Int? = null,
                        val transparentBackground: Boolean = false, val accentSource: String? = null,
                        val accentColor: Int? = null, val transparentText: String? = null,
                        val alwaysShowGraphs: Boolean = false, val surfaceStyle: String? = null,
                        val roundedCorners: Boolean? = null, val style: WidgetStyle? = null,
                        val hideWhenQuiet: Boolean? = null, val inverted: Boolean? = null) {
    // Nullable for Gson snapshots created before per-widget scaling existed.
    val sizeMultiplier get() = contentScale?.takeIf { it.isFinite() }?.coerceIn(0.8f, 1.4f) ?: 1f
    val densityMode get() = verticalDensity?.coerceIn(0, 2) ?: 1
    /** Keep the visible location and appearance preferences when normalizing a single-address preset. */
    fun standardized(state: AppState, now: java.time.Instant): WidgetConfig {
        val chosen = WidgetPresentation.focus(chart(state,now))?.address?.id
            ?: addressIds.firstOrNull { id -> state.addresses.any { it.id == id } }
        return WidgetConfig(widgetId,listOfNotNull(chosen),surfaceStyle = surfaceStyle?.takeIf { it in listOf("light","dark") },
            transparentBackground = transparentBackground,roundedCorners = roundedCorners,style = style,
            hideWhenQuiet = hideWhenQuiet,inverted = inverted,accentSource = accentSource,accentColor = accentColor,
            contentScale = contentScale,hideSettings = hideSettings)
    }
    fun forStyle(state: AppState, now: java.time.Instant, kind: WidgetStyle): WidgetConfig {
        val base = if (kind == WidgetStyle.TIMELINE) copy(addressIds = addressIds.distinct().filter { id -> state.addresses.any { it.id == id } })
            else standardized(state,now)
        return base.copy(style = kind,monochrome = kind == WidgetStyle.MONO,alwaysShowGraphs = kind == WidgetStyle.TIMELINE,
            contentScale = base.contentScale,verticalDensity = 0,hideTitle = false,hideSettings = base.hideSettings,
            surfaceStyle = if (kind == WidgetStyle.MONO) { if (inverted == true) "light" else "dark" } else base.surfaceStyle,
            transparentText = if (kind == WidgetStyle.MONO) { if (inverted == true) "dark" else "light" }
                else when (base.surfaceStyle) { "light" -> "dark"; "dark" -> "light"; else -> null })
    }
    fun effectiveFontScale(systemFontScale: Float) = systemFontScale.coerceIn(1f, 1.5f) * sizeMultiplier
    fun chart(state: AppState, now: java.time.Instant, maxRows: Int = Int.MAX_VALUE): NotificationChart {
        // addressIds is also the user's display order for this widget.
        val ordered = addressIds.mapNotNull { id -> state.addresses.firstOrNull { it.id == id }?.copy(selected = true) }
        val complete = NotificationChart.create(state.copy(addresses = ordered), now, Int.MAX_VALUE)
        return complete.copy(rows = complete.rows.take(maxRows.coerceAtLeast(1)))
    }

    companion object {
        const val PADDING = 12
        const val HEADER = 40
        fun rowHeight(fontScale: Float, density: Int = 1): Float =
            (44f + density.coerceIn(0, 2) * 10f) * fontScale.coerceIn(0.8f, 2.1f)

        fun recommendedHeight(rows: Int, fontScale: Float = 1f, headerVisible: Boolean = true, density: Int = 1): Int =
            ceil((PADDING * 2 + (if (headerVisible) HEADER else 0) + 36 * fontScale.coerceIn(0.8f, 2.1f) +
                rows.coerceAtLeast(1) * rowHeight(fontScale, density)).toDouble()).toInt()

        fun maxRows(heightDp: Int, fontScale: Float, headerVisible: Boolean = true, density: Int = 1, total: Int = Int.MAX_VALUE): Int {
            val available = heightDp - PADDING * 2 - (if (headerVisible) HEADER else 0)
            val scale = fontScale.coerceIn(0.8f, 2.1f)
            val row = rowHeight(scale, density)
            val all = ((available - 36 * scale) / row + 0.0001f).toInt().coerceAtLeast(1)
            return if (all >= total) total.coerceAtLeast(1)
                else ((available - 48 * scale) / row + 0.0001f).toInt().coerceAtLeast(1)
        }
    }
}
