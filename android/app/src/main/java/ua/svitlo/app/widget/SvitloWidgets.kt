package ua.svitlo.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.util.SizeF
import android.widget.RemoteViews
import ua.svitlo.app.MainActivity
import ua.svitlo.app.R
import ua.svitlo.app.background.NotificationTimeline
import ua.svitlo.app.domain.*
import java.time.Instant
import java.time.format.DateTimeFormatter

object SvitloWidgets {
    fun renderAll(context: Context, state: AppState) {
        val manager = AppWidgetManager.getInstance(context)
        WidgetFormats.components(context).flatMap { manager.getAppWidgetIds(it).toList() }.forEach { render(context, state, it) }
    }

    fun render(context: Context, state: AppState, id: Int) {
        val manager = AppWidgetManager.getInstance(context)
        val options = manager.getAppWidgetOptions(id)
        val defaultSize = WidgetFormats.defaultSize(manager.getAppWidgetInfo(id)?.provider)
        val now = Instant.now()
        val config = state.widgets.orEmpty().firstOrNull { it.widgetId == id }?.forStyle(state,now,WidgetFormats.style(manager.getAppWidgetInfo(id)?.provider))
        val motion = context.getSharedPreferences("widget-transitions",Context.MODE_PRIVATE)
        val signature = config?.let { appearance -> appearance.chart(state,now).let { chart ->
            appearance.alwaysShowGraphs.toString()+chart.rows.joinToString { row ->
                "${row.address.id}:${WidgetVisibility.relevant(row,chart)}:${AdaptiveWidgetTimeline.compact(row,chart)}:${row.current}:${row.unpublishedFrom}"
            }
        } }.orEmpty()
        val previous = motion.getString("signature:$id",null)
        val oldFrame = motion.getInt("frame:$id",0)
        val frame = if (previous != null && previous != signature) 1-oldFrame else oldFrame
        val animate = previous != null && previous != signature && config?.monochrome != true &&
            android.provider.Settings.Global.getFloat(context.contentResolver,android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f) > 0f
        @Suppress("DEPRECATION")
        val sizes = if (Build.VERSION.SDK_INT >= 31) options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            ?.filter { it.width >= 48 && it.height >= 48 }?.distinct()?.sortedBy { it.width * it.height }
            ?.take(16) else null
        val views = if (!sizes.isNullOrEmpty() && Build.VERSION.SDK_INT >= 31) {
            RemoteViews(sizes.associateWith { views(context, state, config, id, it.width.toInt(), it.height.toInt(), now,frame,animate) })
        } else {
            val widthKey = if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH
            val width = options.getInt(widthKey, defaultSize.first).coerceAtLeast(48)
            val heightKey = if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
            val height = options.getInt(heightKey, if (config?.alwaysShowGraphs != true) defaultSize.second else
                AdaptiveWidgetTimeline.recommendedHeight(config.addressIds.size,
                    config?.effectiveFontScale(context.resources.configuration.fontScale) ?: 1f,
                    config?.densityMode ?: 1,!(config?.hideTitle == true && config.hideSettings))).coerceAtLeast(48)
            views(context, state, config, id, width, height, now,frame,animate)
        }
        manager.updateAppWidget(id, views)
        if (previous != signature) motion.edit().putString("signature:$id",signature).putInt("frame:$id",frame).apply()
    }

    internal fun views(context: Context, state: AppState, config: WidgetConfig?, id: Int,
                       width: Int, height: Int, now: Instant, frame: Int = 0, animate: Boolean = false,
                       interactive: Boolean = true): RemoteViews {
        if (config?.style == WidgetStyle.TIMELINE) return ComparisonWidget.views(context,state,config,id,width,height,now,interactive)
        if (config != null && !config.alwaysShowGraphs && config.addressIds.any { chosen -> state.addresses.any { it.id == chosen } })
            return FocusWidget.views(context,state,config,id,width,height,now,interactive)
        val appearance = config ?: WidgetConfig(id, emptyList())
        val mono = appearance.monochrome
        val colors = WidgetPalette.resolve(context, appearance)
        val dark = colors.dark
        val views = RemoteViews(context.packageName, R.layout.svitlo_widget)
        views.setViewVisibility(R.id.widget_focus,android.view.View.GONE)
        views.setViewPadding(R.id.widget_root,(12*context.resources.displayMetrics.density).toInt(),
            (12*context.resources.displayMetrics.density).toInt(),(12*context.resources.displayMetrics.density).toInt(),(12*context.resources.displayMetrics.density).toInt())
        views.setViewVisibility(R.id.widget_root,android.view.View.VISIBLE)
        views.setContentDescription(R.id.widget_host,"")
        views.setInt(R.id.widget_root, "setBackgroundResource", widgetBackground(appearance,dark))
        if (Build.VERSION.SDK_INT >= 31 && !appearance.transparentBackground) {
            views.setColorStateList(R.id.widget_root, "setBackgroundTintList", android.content.res.ColorStateList.valueOf(colors.background))
        }
        views.setTextColor(R.id.widget_title, colors.ink)
        views.setTextColor(R.id.widget_overflow, colors.muted)
        views.setTextColor(R.id.widget_configure, colors.accent ?: colors.muted)
        views.setTextColor(R.id.widget_empty, colors.muted)
        // Keep the weighted title's space so the settings control stays at the right edge.
        views.setViewVisibility(R.id.widget_title, if (config?.hideTitle == true) android.view.View.INVISIBLE else android.view.View.VISIBLE)
        views.setViewVisibility(R.id.widget_configure, if (config?.hideSettings == true) android.view.View.GONE else android.view.View.VISIBLE)
        views.setViewVisibility(R.id.widget_header, if (config?.hideTitle == true && config.hideSettings) android.view.View.GONE else android.view.View.VISIBLE)
        val selected = config?.addressIds.orEmpty().count { id -> state.addresses.any { it.id == id } }
        var primaryAddressId: String? = null
        if (config == null || selected == 0) {
            views.setViewVisibility(R.id.widget_transition, android.view.View.GONE)
            views.setViewVisibility(R.id.widget_empty, android.view.View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_empty, android.view.View.GONE)
            val scale = config.effectiveFontScale(context.resources.configuration.fontScale)
            val headerVisible = !(config.hideTitle && config.hideSettings)
            val all = config.chart(state, now)
            val relevant = WidgetVisibility.rows(all,config.alwaysShowGraphs)
            val dormant = relevant.isEmpty()
            // Reapply must explicitly restore the panel after the next outage appears.
            views.setViewVisibility(R.id.widget_root,if (dormant) android.view.View.GONE else android.view.View.VISIBLE)
            views.setContentDescription(R.id.widget_host,if (dormant)
                "Світло. Немає відомих активних або майбутніх відключень. Натисніть, щоб перевірити адреси." else "")
            val complete = all.copy(rows = relevant,total = relevant.size,names = relevant.map { it.address.title })
            val ribbon = AdaptiveWidgetTimeline.ribbon(complete,
                (width-WidgetConfig.PADDING*2-if (config.hideSettings) 0 else 48).toFloat(),scale)
            val hasTimeline = relevant.any { config.alwaysShowGraphs || WidgetVisibility.hasTimeline(it,all) }
            val focusedRow = if (config.alwaysShowGraphs) null else WidgetPresentation.focus(complete)
            primaryAddressId = focusedRow?.address?.id
            views.setTextViewText(R.id.widget_title,if (config.alwaysShowGraphs) ribbon.text else WidgetPresentation.headline(complete,compact = width < 240))
            views.setTextColor(R.id.widget_title,colors.ink)
            views.setTextViewTextSize(R.id.widget_title,android.util.TypedValue.COMPLEX_UNIT_DIP,15f*scale)
            views.setTextViewTextSize(R.id.widget_overflow,android.util.TypedValue.COMPLEX_UNIT_DIP,11f*scale)
            views.setTextViewText(R.id.widget_overflow,if (config.alwaysShowGraphs) "+${ribbon.hidden}" else "12 год")
            views.setContentDescription(R.id.widget_title,WidgetPresentation.headline(complete)+". "+
                (focusedRow?.address?.title?.let { "Відкрити деталі: $it" } ?: complete.names.joinToString()))
            views.setContentDescription(R.id.widget_overflow,if (config.alwaysShowGraphs) "Ще ${ribbon.hidden} адрес. Відкрити застосунок" else "Часова шкала: одна година позаду, одинадцять попереду")
            val showMetadata = if (config.alwaysShowGraphs) ribbon.hidden > 0 else hasTimeline && width >= 280 && scale <= 1.4f
            views.setViewVisibility(R.id.widget_overflow,if (showMetadata && !config.hideTitle) android.view.View.VISIBLE else android.view.View.GONE)
            val chart = AdaptiveWidgetTimeline.fit(complete, (width-WidgetConfig.PADDING*2).toFloat(),
                (height-WidgetConfig.PADDING*2-if (headerVisible) WidgetConfig.HEADER else 0).toFloat(),scale,config.densityMode,config.alwaysShowGraphs)
            val bitmap = AdaptiveWidgetTimeline.draw(chart, (width-WidgetConfig.PADDING*2).toFloat(), scale,
                config.densityMode,mono,colors,config.alwaysShowGraphs)
            views.setViewVisibility(R.id.widget_transition, android.view.View.VISIBLE)
            val imageId = if (frame == 0) R.id.widget_chart else R.id.widget_chart_alt
            views.setImageViewBitmap(imageId, bitmap)
            val unpublished = chart.rows.filter { it.unpublishedFrom != null }.joinToString(". ") {
                "${it.address.title}: на завтра ще немає графіка"
            }
            val continuation = chart.rows.filter { it.continues }.joinToString(". ") {
                "${it.address.title}: відключення продовжується за межами шкали"
            }
            val description = complete.description + (if (unpublished.isEmpty()) "" else ". $unpublished") +
                (if (continuation.isEmpty()) "" else ". $continuation") +
                (if (chart.hidden > 0) ". Ще ${chart.hidden} адрес із подіями або станом. Натисніть, щоб відкрити" else "") +
                (if (hasTimeline) ". Шкала 12 годин: ${chart.start.atZone(KYIV).format(DateTimeFormatter.ofPattern("HH:mm"))}–${chart.end.atZone(KYIV).format(DateTimeFormatter.ofPattern("HH:mm"))}" else "") +
                ". Час побудови " + now.atZone(KYIV).format(DateTimeFormatter.ofPattern("HH:mm"))
            views.setContentDescription(imageId, description)
            if (animate) views.setDisplayedChild(R.id.widget_transition,frame) else {
                // A launcher may reinflate at any time. Both resting frames must be valid;
                // don't call setDisplayedChild here: even selecting the same child restarts its fade.
                val otherId = if (frame == 0) R.id.widget_chart_alt else R.id.widget_chart
                views.setImageViewBitmap(otherId,bitmap)
                views.setContentDescription(otherId,description)
            }
        }
        if (!interactive) return views
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val configure = PendingIntent.getActivity(context, id, Intent(context, WidgetConfigurationActivity::class.java)
            .setData(Uri.parse("svitlo://widget/$id/configure")).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), flags)
        val addresses = PendingIntent.getActivity(context, id, Intent(context, WidgetAddressesActivity::class.java)
            .setData(Uri.parse("svitlo://widget/$id/addresses")).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id),flags)
        views.setOnClickPendingIntent(R.id.widget_configure, configure)
        val primary = primaryAddressId?.let { addressId -> PendingIntent.getActivity(context,id,
            Intent(context,WidgetAddressesActivity::class.java).setData(Uri.parse("svitlo://widget/$id/focus"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).putExtra(WidgetAddressesActivity.EXTRA_ADDRESS_ID,addressId),flags) }
            ?: addresses
        views.setOnClickPendingIntent(R.id.widget_title, primary)
        views.setOnClickPendingIntent(R.id.widget_overflow, addresses)
        views.setOnClickPendingIntent(R.id.widget_chart, addresses)
        views.setOnClickPendingIntent(R.id.widget_chart_alt, addresses)
        views.setOnClickPendingIntent(R.id.widget_empty, configure)
        views.setOnClickPendingIntent(R.id.widget_host, addresses)
        return views
    }
}
