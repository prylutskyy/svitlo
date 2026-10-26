package ua.svitlo.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import ua.svitlo.app.R
import ua.svitlo.app.domain.*
import java.time.Instant

internal fun widgetBackground(config: WidgetConfig, dark: Boolean): Int = when {
    config.transparentBackground -> android.R.color.transparent
    config.roundedCorners == false && config.monochrome && dark -> R.drawable.widget_square_black
    config.roundedCorners == false && dark -> R.drawable.widget_square_dark
    config.roundedCorners == false -> R.drawable.widget_square_light
    config.monochrome && dark -> R.drawable.widget_black
    dark -> R.drawable.widget_tva_dark
    else -> R.drawable.widget_tva_light
}

/** Compact cards stack essentials; wide cards place status and time side by side. */
internal object AdaptiveFocusWidget {
    fun views(context: Context, state: AppState, config: WidgetConfig, id: Int, width: Int, height: Int,
              now: Instant, interactive: Boolean, layout: WidgetLayout): RemoteViews {
        val short = config.style == WidgetStyle.SHORT && height < 100
        val wide = layout == WidgetLayout.WIDE
        val views = RemoteViews(context.packageName,if (short) R.layout.svitlo_widget_short else if (wide) R.layout.svitlo_widget_wide else R.layout.svitlo_widget_small)
        val colors = WidgetPalette.resolve(context,config)
        val card = WidgetFocusCard.forWidget(state,config.chart(state,now),config.hideWhenQuiet == true)
        WidgetStatusIcon.apply(views,card?.row?.current ?: PowerState.UNKNOWN,colors,config.monochrome)
        fun show(view: Int, visible: Boolean) = views.setViewVisibility(view,if (visible) View.VISIBLE else View.GONE)
        show(R.id.widget_root,card != null)
        views.setInt(R.id.widget_root,"setBackgroundResource",widgetBackground(config,colors.dark))
        if (Build.VERSION.SDK_INT >= 31 && !config.transparentBackground)
            views.setColorStateList(R.id.widget_root,"setBackgroundTintList",ColorStateList.valueOf(colors.background))
        val padding = if (height < 80 || width < 110) 4 else 8
        val density = context.resources.displayMetrics.density
        val horizontal = if (short) 12 else padding
        views.setViewPadding(R.id.widget_root,(horizontal*density).toInt(),(padding*density).toInt(),(horizontal*density).toInt(),(padding*density).toInt())
        val header = short || height >= 80
        val stateVisible = !short && (wide || height >= 140 && config.effectiveFontScale(context.resources.configuration.fontScale) <= 1.5f)
        val labelVisible = !short && wide && height >= 140
        val captionVisible = short || height >= 72
        val systemScale = context.resources.configuration.fontScale.coerceIn(1f,1.5f)
        val scale = config.effectiveFontScale(systemScale)
        val headerHeight = if (short) 14f else 24f*config.sizeMultiplier
        if (Build.VERSION.SDK_INT >= 31) {
            views.setViewLayoutHeight(R.id.focus_header,headerHeight,TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutWidth(R.id.focus_power_icon,if (short) 12f*config.sizeMultiplier.coerceAtMost(1f) else 16f*config.sizeMultiplier,TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutHeight(R.id.focus_power_icon,if (short) 12f*config.sizeMultiplier.coerceAtMost(1f) else 16f*config.sizeMultiplier,TypedValue.COMPLEX_UNIT_DIP)
        }
        val textWidth = (width-2*horizontal).toFloat().coerceAtLeast(1f)
        val timeWidth = if (wide) textWidth*.60f else textWidth
        val estimate = card?.label?.contains("орієнтовно") == true
        val active = card?.stateLabel?.endsWith("зараз") == true
        val day = if (estimate) card!!.label.removePrefix("Відновлення ").substringBefore(',').replaceFirstChar { it.uppercase() }
            else card?.caption?.substringBefore(" · ").orEmpty()
        val stateText = when {
            card?.label == "Стан уточнюється" -> "Уточнюється"
            card?.emergency == true -> if (wide) "Без світла" else card.stateLabel
            active -> if (wide) "Без світла".takeIf { state.schedule(card!!.row.address.id)?.currentOutage != null } ?: "За графіком" else card!!.stateLabel
            else -> "За графіком"
        }
        val caption = when {
            short && estimate -> "≈${card?.time} ${day.lowercase()}"
            short && card?.time == "✓" -> "За графіком"
            short && active -> "За графіком"
            short && card?.label != "Стан уточнюється" -> day
            card?.time == "✓" -> "Відключень немає"
            estimate && !stateVisible -> day
            estimate -> day
            card?.label == "Стан уточнюється" -> if (!wide) "Стан уточнюється" else when (card.caption) {
                "Повідомлення застаріло" -> "Дані застаріли"
                "Час відновлення уточнюється" -> "Прогноз минув"
                else -> "Немає даних"
            }
            active -> if (stateVisible) "Зараз" else "За графіком зараз"
            else -> day
        }
        val value = if (short && estimate) "Без світла" else
            (if (estimate) "≈ " else if (active && card?.label?.endsWith("до") == true) "до " else "")+
                (if (short) card?.time?.substringBefore("–").orEmpty() else card?.time.orEmpty())
        val typeface = android.graphics.Typeface.create("sans-serif-light",android.graphics.Typeface.NORMAL)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; textSize = 32f }
        val secondaryScale = if (short) config.sizeMultiplier.coerceAtMost(1f) else config.sizeMultiplier
        val captionDesired = (if (short) 9f else 10.5f)*systemScale.coerceAtMost(1.2f)*secondaryScale
        val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = captionDesired }
        val captionSize = minOf(captionDesired,captionDesired*(timeWidth-2).coerceAtLeast(1f)/captionPaint.measureText(caption).coerceAtLeast(1f))
        val desired = if (short) 16f else if (wide) 36f else 32f
        val room = height-2*padding-(if (header) { if (Build.VERSION.SDK_INT >= 31) headerHeight else if (short) 14f else 24f } else 0f)-(if (captionVisible) captionSize*1.25f else 0f)-
            (if (stateVisible && !wide) 34*scale else 0f)-(if (short) 0 else 4)
        val shrink = config.sizeMultiplier.coerceAtMost(1f)
        val size = minOf(desired*scale,32f*(timeWidth-3).coerceAtLeast(1f)/paint.measureText(value).coerceAtLeast(1f)*shrink,room.coerceAtLeast(10f)/1.25f*shrink)
        fun text(view: Int, value: String, size: Float, color: Int = colors.ink, lines: Int = 2) {
            views.setTextViewText(view,value)
            views.setTextViewTextSize(view,TypedValue.COMPLEX_UNIT_DIP,size)
            views.setTextColor(view,color)
            views.setInt(view,"setMaxLines",lines)
        }
        text(R.id.focus_address,card?.row?.address?.title.orEmpty(),(if (short) 10f else 11f)*systemScale.coerceAtMost(1.2f)*secondaryScale,colors.muted,1)
        text(R.id.focus_state,stateText,12f*scale,colors.ink)
        text(R.id.focus_label,if (wide) "Відновлення ≈".takeIf { estimate }.orEmpty() else card?.label.orEmpty(),11f*scale,colors.muted)
        text(R.id.focus_time,value,size,colors.accent ?: colors.ink,1)
        text(R.id.focus_caption,caption,captionSize,colors.muted,1)
        views.setTextColor(R.id.focus_configure,colors.muted)
        show(R.id.focus_header,header); show(R.id.focus_state,stateVisible); show(R.id.focus_label,labelVisible)
        show(R.id.focus_caption,captionVisible); show(R.id.focus_configure,!short && !config.hideSettings)
        views.setContentDescription(R.id.focus_event,card?.let { "${it.row.address.title}. ${it.stateLabel}. ${it.label}. ${it.time}. ${it.caption}. Відкрити деталі та графік." }.orEmpty())
        views.setContentDescription(R.id.widget_host,if (card == null) "Світло. Немає відомих відключень. Відкрити адреси." else "")
        if (interactive) {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            fun open(path: String, activity: Class<*>, address: Boolean = false) = PendingIntent.getActivity(context,id,
                Intent(context,activity).setData(Uri.parse("svitlo://widget/$id/$path")).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id)
                    .apply { if (address) putExtra(WidgetAddressesActivity.EXTRA_ADDRESS_ID,card?.row?.address?.id) },flags)
            val details = open("focus",WidgetAddressesActivity::class.java,true)
            views.setOnClickPendingIntent(R.id.widget_host,details)
            views.setOnClickPendingIntent(R.id.focus_event,details)
            views.setOnClickPendingIntent(R.id.focus_address,details)
            views.setOnClickPendingIntent(R.id.focus_configure,open("configure",WidgetConfigurationActivity::class.java))
        }
        return views
    }
}
