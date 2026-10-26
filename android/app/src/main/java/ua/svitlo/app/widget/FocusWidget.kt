package ua.svitlo.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import ua.svitlo.app.R
import ua.svitlo.app.domain.*
import java.time.Instant

/** Native text keeps the event accessible, crisp and independently tappable. */
internal object FocusWidget {
    fun views(context: Context, state: AppState, config: WidgetConfig, id: Int, width: Int, height: Int,
              now: Instant, interactive: Boolean): RemoteViews {
        val layout = WidgetLayout.forSize(width,height)
        if (layout != WidgetLayout.LARGE) return AdaptiveFocusWidget.views(context,state,config,id,width,height,now,interactive,layout)
        val views = RemoteViews(context.packageName, R.layout.svitlo_widget)
        val colors = WidgetPalette.resolve(context, config)
        val card = WidgetFocusCard.forWidget(state, config.chart(state, now),config.hideWhenQuiet == true)
        WidgetStatusIcon.apply(views,card?.row?.current ?: PowerState.UNKNOWN,colors,config.monochrome)
        if (Build.VERSION.SDK_INT >= 31) {
            views.setViewLayoutWidth(R.id.focus_power_icon,16f*config.sizeMultiplier,TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutHeight(R.id.focus_power_icon,16f*config.sizeMultiplier,TypedValue.COMPLEX_UNIT_DIP)
        }
        fun show(view: Int, visible: Boolean) = views.setViewVisibility(view, if (visible) View.VISIBLE else View.GONE)
        show(R.id.widget_header, false); show(R.id.widget_transition, false); show(R.id.widget_empty, false)
        show(R.id.widget_focus, true); show(R.id.widget_root, card != null)
        views.setContentDescription(R.id.widget_host, if (card == null)
            "Світло. Немає відомих активних або майбутніх відключень. Відкрити адреси." else "")
        views.setInt(R.id.widget_root, "setBackgroundResource", widgetBackground(config,colors.dark))
        val paper = if (card?.emergency == true && config.surfaceStyle == null && config.accentSource == null &&
            !config.monochrome && !config.transparentBackground) {
            if (colors.dark) Color.rgb(40,28,29) else Color.rgb(255,245,241)
        } else colors.background
        if (Build.VERSION.SDK_INT >= 31 && !config.transparentBackground) views.setColorStateList(R.id.widget_root, "setBackgroundTintList",
            ColorStateList.valueOf(paper))
        // Old saved manual scales must not make a resized widget overflow.
        val systemScale = context.resources.configuration.fontScale.coerceIn(1f,1.5f)
        val scale = config.effectiveFontScale(systemScale)
        val d = context.resources.displayMetrics.density
        val compact = height < 240 || width < 240
        val horizontalPadding = if (compact) 12 else 16
        val available = (width - horizontalPadding * 2).toFloat()
        val light = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        fun measured(value: String, size: Float, lines: Int = 2, large: Boolean = false): Float {
            val paint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size * d
                if (large) typeface = light
            }
            return android.text.StaticLayout.Builder.obtain(value, 0, value.length, paint, (available*d).toInt()-1)
                .setIncludePad(false).setMaxLines(lines).build().height / d
        }
        fun fitTime(value: String, desired: Float): Float {
            val paint = Paint().apply { textSize = desired; typeface = light }
            // Native TextView rounds shaped glyph widths at device density; keep a small
            // inset so a fitted word such as “Зараз” never becomes an ellipsis.
            return minOf(desired, desired * (available - 4) / paint.measureText(value).coerceAtLeast(1f)*config.sizeMultiplier.coerceAtMost(1f))
        }
        val labelSize = (if (compact) 12f else 13f) * scale
        val stateSize = 14f * scale
        val distinctLabel = card?.label != card?.stateLabel
        val showDetail = width >= 240 || height >= 240
        var captionSize = labelSize
        var value = card?.time.orEmpty()
        var timeSize = fitTime(value, (if (compact) 40f else 52f) * scale)
        val normalPadding = if (compact) 12 else 16
        val mainHeight = 44 + 2*normalPadding + measured(card?.stateLabel.orEmpty(), stateSize) + 6 +
            (if (distinctLabel) measured(card?.label.orEmpty(), labelSize) else 0f) +
            measured(value, timeSize, 1, true) + 6 + (if (showDetail) measured(card?.caption.orEmpty(), captionSize) else 0f)
        // Measure the actual strings and font before dropping secondary layers.
        val micro = mainHeight + 4 > height
        val padding = if (micro) 4 else normalPadding
        val label = !micro && distinctLabel
        fun text(view: Int, value: String, size: Float, color: Int, maxLines: Int = 2) {
            views.setTextViewText(view, value)
            views.setTextViewTextSize(view, TypedValue.COMPLEX_UNIT_DIP, size)
            views.setTextColor(view, color)
            views.setInt(view, "setMaxLines", maxLines)
        }
        val caption = if (micro && card != null) {
            // Tiny hosts still retain the day and the event's uncertainty in visible text.
            val estimate = card.label.contains("орієнтовно")
            val endDay = card.label.removePrefix("Відновлення ").substringBefore(", орієнтовно")
            val day = card.caption.substringBefore(" · ").takeIf { it in listOf("Сьогодні", "Завтра") || it.matches(Regex("\\d{2}\\.\\d{2}")) }
            val kind = when {
                card.emergency -> "Аварійне"
                card.label.startsWith("Можливе") -> "Можливе"
                card.label == "Стан уточнюється" -> "Стан уточнюється"
                card.label.startsWith("Повідомлене") || card.caption.contains("повідомлене") -> "Повідомлено"
                else -> "Графік"
            }
            if (estimate) { if (endDay == "сьогодні") card.stateLabel else "Без світла · до $endDay" } else if (card.stateLabel.endsWith("зараз")) card.stateLabel
                else listOfNotNull(day, kind).joinToString(" · ")
        } else card?.caption.orEmpty()
        if (micro) {
            captionSize = 11f * systemScale.coerceAtMost(1.1f)*config.sizeMultiplier
            val prefix = when {
                card?.label?.contains("орієнтовно") == true -> "≈ "
                card?.stateLabel?.endsWith("зараз") == true && card.label.endsWith("до") -> "до "
                else -> ""
            }
            value = prefix + card?.time.orEmpty()
            timeSize = fitTime(value, 25f * scale)
            val room = (height - 44 - 2*padding - 6 - measured(caption, captionSize, 1) - 3).coerceAtLeast(1f)
            val wanted = measured(value, timeSize, 1, true)
            if (wanted > room) timeSize *= room / wanted
        }
        val other = card?.otherEvents ?: 0
        val noun = when {
            other % 100 in 11..14 -> "адрес із подіями"
            other % 10 == 1 -> "адреса з подіями"
            other % 10 in 2..4 -> "адреси з подіями"
            else -> "адрес із подіями"
        }
        val single = config.addressIds.count { chosen -> state.addresses.any { it.id == chosen } } == 1
        val footerText = if (single) "Деталі та графік  ›" else if (other > 0) "Ще $other $noun  ›" else "Усі адреси  ›"
        val footer = !micro && mainHeight + maxOf(44f, measured(footerText, 13f*scale) + 8) + 4 <= height
        views.setViewPadding(R.id.widget_root, (horizontalPadding*d).toInt(), (padding*d).toInt(), (horizontalPadding*d).toInt(), (padding*d).toInt())
        val title = card?.row?.address?.title.orEmpty() + if (!footer && other > 0) " · +$other" else ""
        text(R.id.focus_address, title, 13f * systemScale.coerceAtMost(if (micro) 1.15f else 2.1f)*config.sizeMultiplier, colors.muted, 1)
        text(R.id.focus_label, card?.label.orEmpty(), labelSize, colors.ink)
        text(R.id.focus_time, value, timeSize, colors.ink, 1)
        val eventColor = if (card?.emergency == true && !config.monochrome)
            if (colors.dark) Color.rgb(255, 137, 131) else Color.rgb(170, 42, 35) else colors.muted
        val stateColor = if (card?.emergency == true) eventColor else colors.accent ?: colors.ink
        text(R.id.focus_state, card?.stateLabel.orEmpty(), stateSize, stateColor)
        views.setTextColor(R.id.focus_label, colors.muted)
        text(R.id.focus_caption, caption, captionSize, if (micro) stateColor else colors.muted, if (micro) 1 else 2)
        text(R.id.focus_footer, footerText, 13f * scale, colors.accent ?: colors.muted)
        views.setTextColor(R.id.focus_configure, colors.muted)
        show(R.id.focus_state, !micro); show(R.id.focus_label, label); show(R.id.focus_footer, footer)
        show(R.id.focus_caption, micro || showDetail)
        // The active address is essential context even if the old generic title was hidden.
        show(R.id.focus_configure, !config.hideSettings)
        views.setContentDescription(R.id.focus_address, if (single) "${card?.row?.address?.title.orEmpty()}. Відкрити деталі локації." else "${card?.row?.address?.title.orEmpty()}. Відкрити всі адреси віджета. Інших адрес із подіями: $other")
        views.setContentDescription(R.id.focus_event, card?.let {
            "${it.row.address.title}. ${it.stateLabel}. ${it.label}. ${it.time}. ${it.caption}. ${it.row.status}. Відкрити деталі та графік."
        }.orEmpty())
        views.setContentDescription(R.id.focus_footer, if (single) "Відкрити деталі та графік цієї локації" else "Відкрити всі адреси віджета. Інших адрес із подіями: $other")
        if (interactive) {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            fun intent(path: String) = Intent(context, WidgetAddressesActivity::class.java)
                .setData(Uri.parse("svitlo://widget/$id/$path")).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            val all = PendingIntent.getActivity(context, id, intent("addresses"), flags)
            val focus = PendingIntent.getActivity(context, id, intent("focus")
                .putExtra(WidgetAddressesActivity.EXTRA_ADDRESS_ID, card?.row?.address?.id), flags)
            val settings = PendingIntent.getActivity(context, id, Intent(context, WidgetConfigurationActivity::class.java)
                .setData(Uri.parse("svitlo://widget/$id/configure")).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), flags)
            views.setOnClickPendingIntent(R.id.widget_host, all)
            views.setOnClickPendingIntent(R.id.focus_address, if (single) focus else all)
            views.setOnClickPendingIntent(R.id.focus_footer, if (single) focus else all)
            views.setOnClickPendingIntent(R.id.focus_event, focus)
            views.setOnClickPendingIntent(R.id.focus_configure, settings)
        }
        return views
    }
}
