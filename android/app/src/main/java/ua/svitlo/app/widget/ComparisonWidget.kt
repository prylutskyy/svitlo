package ua.svitlo.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import ua.svitlo.app.R
import ua.svitlo.app.domain.*
import java.time.Instant
import java.time.format.DateTimeFormatter

/** All locations share one clock; colour is reserved for outages. */
internal object ComparisonWidget {
    fun views(context: Context, state: AppState, config: WidgetConfig, id: Int, width: Int, height: Int,
              now: Instant, interactive: Boolean): RemoteViews {
        val colors = WidgetPalette.resolve(context,config)
        val chart = config.chart(state,now)
        val views = RemoteViews(context.packageName,R.layout.svitlo_widget_comparison)
        val hidden = config.hideWhenQuiet == true && chart.rows.none { WidgetVisibility.relevant(it,chart) }
        views.setViewVisibility(R.id.widget_root,if (hidden) View.GONE else View.VISIBLE)
        views.setInt(R.id.widget_root,"setBackgroundResource",widgetBackground(config,colors.dark))
        if (Build.VERSION.SDK_INT >= 31 && !config.transparentBackground)
            views.setColorStateList(R.id.widget_root,"setBackgroundTintList",ColorStateList.valueOf(colors.background))
        views.setTextColor(R.id.widget_title,colors.ink)
        views.setTextColor(R.id.widget_configure,colors.muted)
        views.setViewVisibility(R.id.widget_configure,if (config.hideSettings) View.GONE else View.VISIBLE)
        views.setTextViewTextSize(R.id.widget_title,android.util.TypedValue.COMPLEX_UNIT_DIP,12f*config.effectiveFontScale(context.resources.configuration.fontScale))
        val headerHeight = if (Build.VERSION.SDK_INT >= 31) 28f*config.sizeMultiplier else 28f
        if (Build.VERSION.SDK_INT >= 31) views.setViewLayoutHeight(R.id.widget_header,headerHeight,android.util.TypedValue.COMPLEX_UNIT_DIP)
        val w = (width-16).coerceAtLeast(16).toFloat()
        val h = (height-16-headerHeight).coerceAtLeast(16f)
        val density = context.resources.displayMetrics.density.coerceAtMost(3f)
        val bitmap = Bitmap.createBitmap((w*density).toInt(),(h*density).toInt(),Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { scale(density,density) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val font = config.effectiveFontScale(context.resources.configuration.fontScale)
        val left = (w*.32f).coerceAtMost(110f)
        val plotEnd = w-14
        fun x(at: Instant) = left+(plotEnd-left)*((at.toEpochMilli()-chart.start.toEpochMilli()).toFloat()/(chart.end.toEpochMilli()-chart.start.toEpochMilli()))
        fun text(value: String, at: Float, y: Float, size: Float, color: Int, max: Float) {
            paint.color = color; paint.textSize = size
            var label = value
            if (paint.measureText(label) > max) {
                while (label.isNotEmpty() && paint.measureText("$label…") > max) label = label.dropLast(1)
                label += "…"
            }
            canvas.drawText(label,at,y,paint)
        }
        val axis = 22f*font
        val rowHeight = 34f*font
        val capacity = ((h-axis)/rowHeight).toInt().coerceAtLeast(1)
        val overflow = chart.rows.size > capacity
        val rows = chart.rows.take(if (overflow && capacity > 1) capacity-1 else capacity)
        val clock = DateTimeFormatter.ofPattern("HH:mm")
        paint.textSize = 9f*font
        val ticks = TimelineNavigation.ticks(TimelineWindow(chart.start,chart.end))
        val sparseTicks = plotEnd-left < 4*(paint.measureText("00:00")+8)
        for (at in ticks.filter { !sparseTicks || it.atZone(KYIV).hour%6 == 0 }) {
            val label = at.atZone(KYIV).format(clock)
            paint.textSize = 9f*font
            val tw = paint.measureText(label)
            text(label,(x(at)-tw/2).coerceIn(left,(w-tw).coerceAtLeast(left)),12f*font,9f*font,colors.muted,w-left)
        }
        rows.forEachIndexed { index,row ->
            val y = axis+index*rowHeight+rowHeight/2
            context.getDrawable(WidgetStatusIcon.resource(row.current))?.mutate()?.let { icon ->
                icon.setTint(WidgetStatusIcon.color(row.current,colors,config.monochrome))
                val checkpoint = canvas.save()
                canvas.translate(0f,y-7f*config.sizeMultiplier); icon.setBounds(0,0,(14*config.sizeMultiplier).toInt(),(14*config.sizeMultiplier).toInt()); icon.draw(canvas)
                canvas.restoreToCount(checkpoint)
            }
            text(row.address.title,18f*config.sizeMultiplier,y+4,11f*font,colors.ink,(left-18f*config.sizeMultiplier-10).coerceAtLeast(1f))
            paint.color = colors.muted; paint.alpha = 45
            canvas.drawRoundRect(left,y-3,plotEnd,y+3,3f,3f,paint); paint.alpha = 255
            row.spans.forEach { span ->
                paint.color = when (span.state) {
                    PowerState.EMERGENCY_OUTAGE -> 0xFFD74A4A.toInt()
                    PowerState.CONFIRMED_OUTAGE -> 0xFFE59A28.toInt()
                    PowerState.POSSIBLE_OUTAGE,PowerState.SCHEDULED_OUTAGE -> colors.accent ?: 0xFF447BC4.toInt()
                    else -> colors.muted
                }
                if (span.state in OUTAGE_STATES) canvas.drawRoundRect(x(span.start),y-5,x(span.end),y+5,3f,3f,paint)
                else if (span.state == PowerState.UNKNOWN) {
                    paint.strokeWidth = 2f; paint.pathEffect = DashPathEffect(floatArrayOf(2f,3f),0f)
                    canvas.drawLine(x(span.start),y,x(span.end),y,paint); paint.pathEffect = null
                }
            }
            if (row.continues || row.nextOutageAt?.let { it >= chart.end } == true)
                text("›",w-9,y+5,15f,colors.ink,9f)
        }
        paint.color = colors.ink; paint.strokeWidth = 1.5f
        canvas.drawLine(x(now),axis-5,x(now),(axis+rows.size*rowHeight).coerceAtMost(h),paint)
        canvas.drawCircle(x(now),axis-5,2.5f,paint)
        if (chart.rows.size > rows.size)
            text("+${chart.rows.size-rows.size} адрес",0f,(axis+rows.size*rowHeight+18*font).coerceAtMost(h-2),11f*font,colors.muted,w)
        views.setImageViewBitmap(R.id.widget_chart,bitmap)
        views.setContentDescription(R.id.widget_chart,chart.rows.joinToString(". ") { "${it.address.title}: ${WidgetStatusIcon.description(it.current)}. ${it.status}" }+". Шкала 12 годин. Відкрити всі адреси та точний час.")
        if (interactive) {
            fun open(path: String, type: Class<*>) = PendingIntent.getActivity(context,id,
                Intent(context,type).setData(Uri.parse("svitlo://widget/$id/$path")).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_host,open("addresses",WidgetAddressesActivity::class.java))
            views.setOnClickPendingIntent(R.id.widget_configure,open("configure",WidgetConfigurationActivity::class.java))
        }
        return views
    }
}
