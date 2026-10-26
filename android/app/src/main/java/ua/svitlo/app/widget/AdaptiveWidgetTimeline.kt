package ua.svitlo.app.widget

import android.graphics.*
import android.text.TextPaint
import android.text.TextUtils
import ua.svitlo.app.domain.*
import java.time.Instant
import java.time.Duration
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

/** Static, measured RemoteViews artwork; never schedules animation ticks or network work. */
internal object AdaptiveWidgetTimeline {
    private val outages = OUTAGE_STATES
    fun compact(row: ChartRow, chart: NotificationChart): Boolean = !WidgetVisibility.hasTimeline(row,chart)

    // The stored widget order is authoritative, including when events arrive out of order.
    fun ordered(chart: NotificationChart) = chart
    data class Ribbon(val rows: List<ChartRow>, val text: String, val hidden: Int)
    fun ribbon(chart: NotificationChart, width: Float, fontScale: Float): Ribbon {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f * fontScale.coerceIn(.8f, 2.1f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        fun title(row: ChartRow) = TextUtils.ellipsize(row.address.title, paint,
            (width * .65f).coerceAtLeast(1f), TextUtils.TruncateAt.END).toString()
        val chosen = mutableSetOf<String>()
        // Reserve overflow space up front. Prefer active addresses, but never reorder them.
        val budget = (width - paint.measureText("+${chart.rows.size}") - 16f).coerceAtLeast(0f)
        val candidates = chart.rows.filterNot { compact(it, chart) } + chart.rows.filter { compact(it, chart) }
        for (row in candidates) {
            val proposal = chart.rows.filter { it.address.id in chosen || it.address.id == row.address.id }
            if (paint.measureText(proposal.joinToString(" / ") { title(it) }) <= budget) chosen += row.address.id
        }
        if (chosen.isEmpty() && chart.rows.isNotEmpty()) chosen += candidates.first().address.id
        var visible = chart.rows.filter { it.address.id in chosen }
        if (paint.measureText(chart.rows.joinToString(" / ") { title(it) }) <= width) visible = chart.rows
        return Ribbon(visible, visible.joinToString(" / ") { title(it) }, chart.rows.size - visible.size)
    }

    private fun rowHeight(scale: Float, density: Int) = (56f + density.coerceIn(0,2) * 6f) * scale
    private fun visible(chart: NotificationChart, graphs: Boolean) = WidgetVisibility.rows(chart,graphs)
    private fun hasGraph(row: ChartRow, chart: NotificationChart, graphs: Boolean) = graphs || !compact(row,chart)
    private fun axisHeight(rows: List<ChartRow>, chart: NotificationChart, graphs: Boolean, scale: Float) =
        if (rows.any { hasGraph(it,chart,graphs) }) 44f*scale else 0f

    private fun status(row: ChartRow,chart: NotificationChart) = WidgetPresentation.status(row,chart)
    internal data class RowLayout(val height: Float,val graphOffset: Float?,val inline: Boolean,
        val textX: Float,val titleRight: Float,val statusX: Float)
    internal fun rowLayouts(chart: NotificationChart,width: Float,scale: Float,density: Int,graphs: Boolean): List<RowLayout> {
        val rows = visible(chart,graphs)
        val textX = if (rows.any { hasGraph(it,chart,graphs) }) maxOf(Axis(chart,width).x(chart.now)+10f,12f) else 4f
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        return rows.map { row ->
            paint.textSize = 14f*scale; paint.typeface = Typeface.DEFAULT_BOLD
            val titleWidth = paint.measureText(row.address.title)
            paint.textSize = 12f*scale; paint.typeface = Typeface.DEFAULT
            val statusWidth = paint.measureText(status(row,chart))
            val inline = titleWidth+statusWidth+12f*scale <= width-textX-4f
            val graph = hasGraph(row,chart,graphs)
            RowLayout(((if (graph) { if (inline) 40f else 56f } else { if (inline) 28f else 44f })+
                density.coerceIn(0,2)*(if (graph) 6f else 4f))*scale,
                if (graph) (if (inline) 28f else 48f)*scale else null,inline,textX,
                if (inline) width-statusWidth-12f*scale else width,
                if (inline) width-statusWidth else textX)
        }
    }

    fun recommendedHeight(rows: Int, scale: Float, density: Int, header: Boolean) =
        ceil(24f + (if (header) 40f else 0f) + 44f*scale + rows*rowHeight(scale,density)).toInt()
    fun fit(chart: NotificationChart, width: Float, available: Float, fontScale: Float, density: Int,
            alwaysShowGraphs: Boolean): NotificationChart {
        val scale = fontScale.coerceIn(.8f,2.1f)
        val candidates = visible(chart,alwaysShowGraphs)
        fun height(rows: List<ChartRow>) = axisHeight(rows,chart,alwaysShowGraphs,scale) +
            rowLayouts(chart.copy(rows = rows),width,scale,density,alwaysShowGraphs).sumOf { it.height.toDouble() }.toFloat()
        val count = if (height(candidates) <= available) candidates.size else {
            // Reserve the overflow label before admitting a row; never shrink the bitmap to fit.
            var count = 0
            for (n in 1..candidates.size) {
                if (height(candidates.take(n)) + 20f*scale > available) break
                count = n
            }
            count
        }
        return chart.copy(rows = candidates.take(count), total = candidates.size)
    }

    /** One time-to-position mapping for labels, bars and the marker. Text scale never zooms time. */
    internal data class Axis(val chart: NotificationChart, val width: Float) {
        val left = 4f
        val right = (width-14f).coerceAtLeast(left+1f)
        fun x(time: Instant): Float = (left + Duration.between(chart.start,time).toMillis().toDouble() /
            Duration.between(chart.start,chart.end).toMillis().coerceAtLeast(1) * (right-left)).toFloat().coerceIn(left,right)
    }
    internal data class AxisLabel(val value: String, val left: Float, val right: Float, val secondLine: Boolean = false)
    internal fun axisLabels(chart: NotificationChart, width: Float, scale: Float): List<AxisLabel> {
        val axis = Axis(chart,width)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f*scale; typeface = Typeface.DEFAULT }
        val fmt = DateTimeFormatter.ofPattern("HH:mm")
        val result = mutableListOf<AxisLabel>()
        fun add(value: String, at: Instant, force: Boolean = false,gap: Float = 8f*scale) {
            val size = paint.measureText(value).coerceAtMost(width)
            val left = (axis.x(at)-size/2f).coerceIn(0f,(width-size).coerceAtLeast(0f))
            val overlaps = result.any { !it.secondLine && left < it.right+gap && left+size > it.left-gap }
            if (!overlaps || force) result += AxisLabel(value,left,left+size,overlaps)
        }
        paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = 12f*scale
        add(chart.now.atZone(KYIV).format(fmt),chart.now,true)
        paint.typeface = Typeface.DEFAULT; paint.textSize = 11f*scale
        // The exact right endpoint makes the fixed window explicit; whole-hour ticks aren't its end.
        add(chart.end.atZone(KYIV).format(fmt),chart.end,gap = 3f*scale)
        val midnight = chart.now.atZone(KYIV).toLocalDate().plusDays(1).atStartOfDay(KYIV).toInstant()
        if (midnight < chart.end) add("Завтра · 00:00",midnight,true)
        val first = chart.now.atZone(KYIV).withMinute(0).withSecond(0).withNano(0).plusHours(1)
        val hourWidth = (axis.right-axis.left)*3600_000f /
            Duration.between(chart.start,chart.end).toMillis().coerceAtLeast(1)
        val step = ceil((paint.measureText("00:00")+8f*scale)/hourWidth).toInt().coerceIn(1,6)
        // Keep an even clock rhythm (e.g. 00, 02, 04) and reject any remaining collisions.
        for (i in 0..11) {
            val localTick = first.plusHours(i.toLong())
            val tick = localTick.toInstant()
            if (tick < chart.end && tick != midnight && localTick.hour%step == 0) add(localTick.format(fmt),tick)
        }
        return result
    }

    fun draw(chart: NotificationChart, width: Float, fontScale: Float, density: Int, mono: Boolean,
             colors: WidgetColors, alwaysShowGraphs: Boolean): Bitmap {
        val scale = fontScale.coerceIn(.8f,2.1f)
        val w = width.coerceIn(32f,1200f)
        val rows = visible(chart,alwaysShowGraphs)
        val layouts = rowLayouts(chart,w,scale,density,alwaysShowGraphs)
        val axisHeight = axisHeight(rows,chart,alwaysShowGraphs,scale)
        val height = if (rows.isEmpty() && chart.hidden == 0) 1f else
            axisHeight + layouts.sumOf { it.height.toDouble() }.toFloat() + if (chart.hidden > 0) 17f*scale else 0f
        val bitmap = Bitmap.createBitmap(ceil(w*2).toInt(),ceil(height*2).toInt(),Bitmap.Config.ARGB_8888).apply { this.density = 320 }
        if (height == 1f) return bitmap
        val canvas = Canvas(bitmap).apply { scale(2f,2f) }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val label = TextPaint(Paint.ANTI_ALIAS_FLAG)
        fun text(value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, right: Float = w) {
            label.textSize = size*scale; label.color = color
            label.typeface = Typeface.create("sans-serif",if (bold) Typeface.BOLD else Typeface.NORMAL)
            canvas.drawText(TextUtils.ellipsize(value,label,(right-x).coerceAtLeast(1f),TextUtils.TruncateAt.END).toString(),x,y,label)
        }
        val axis = Axis(chart,w)
        fun x(time: Instant) = axis.x(time)
        val nowX = x(chart.now)
        val textX = if (axisHeight > 0f) maxOf(nowX+10f,12f) else 4f
        val rowTops = layouts.runningFold(axisHeight) { offset,layout -> offset+layout.height }.dropLast(1)
        val lastRail = layouts.indices.mapNotNull { index -> layouts[index].graphOffset?.let { rowTops[index]+it } }.lastOrNull()
        var drawMidnight: (() -> Unit)? = null
        var top = axisHeight
        if (axisHeight > 0f) {
            axisLabels(chart,w,scale).forEachIndexed { index,tick ->
                text(tick.value,tick.left,(if (tick.secondLine) 32f else 15f)*scale,
                    if (index == 0) 12f else 11f,if (index == 0) colors.accent ?: colors.ink else colors.muted,index == 0)
            }
            val midnight = chart.now.atZone(KYIV).toLocalDate().plusDays(1).atStartOfDay(KYIV).toInstant()
            if (midnight < chart.end) {
                drawMidnight = {
                canvas.save()
                // Interrupt only where actual glyph bounds intersect this solid guide.
                fun protect(value: String,left: Float,baseline: Float,size: Float,bold: Boolean,right: Float) {
                    label.textSize = size*scale
                    label.typeface = Typeface.create("sans-serif",if (bold) Typeface.BOLD else Typeface.NORMAL)
                    val shown = TextUtils.ellipsize(value,label,(right-left).coerceAtLeast(1f),TextUtils.TruncateAt.END).toString()
                    val bounds = RectF(left-2f,baseline+label.fontMetrics.ascent-2f,
                        left+label.measureText(shown)+2f,baseline+label.fontMetrics.descent+2f)
                    canvas.clipOutRect(bounds)
                }
                rows.forEachIndexed { index,row ->
                    val layout = layouts[index]; val rowTop = rowTops[index]
                    protect(row.address.title,layout.textX,rowTop+15f*scale,14f,true,layout.titleRight)
                    protect(status(row,chart),layout.statusX,rowTop+(if (layout.inline) 15f else 32f)*scale,12f,false,w)
                }
                p.color = colors.muted; p.alpha = 105; p.strokeWidth = .8f
                canvas.drawLine(x(midnight),36f*scale,x(midnight),(lastRail ?: axisHeight)+7f*scale,p)
                canvas.restore()
                }
            }
        }
        rows.forEachIndexed { index,row ->
            val layout = layouts[index]
            val emergency = row.emergency || row.current == PowerState.EMERGENCY_OUTAGE
            val color = if (mono) colors.ink else if (emergency) { if (colors.dark) Color.rgb(255,102,107) else Color.rgb(187,37,43) }
                else if (colors.dark) Color.rgb(255,187,88) else Color.rgb(169,87,0)
            text(row.address.title,layout.textX,top+15f*scale,14f,colors.ink,true,right = layout.titleRight)
            val statusColor = when (row.current) {
                PowerState.EMERGENCY_OUTAGE -> color
                PowerState.CONFIRMED_OUTAGE,PowerState.POSSIBLE_OUTAGE -> colors.ink
                else -> colors.muted
            }
            text(status(row,chart),layout.statusX,top+(if (layout.inline) 15f else 32f)*scale,12f,statusColor)
            val itemHeight = layout.height
            if (layout.graphOffset == null) { top += itemHeight; return@forEachIndexed }
            val cy = top+layout.graphOffset
            p.color = colors.muted; p.alpha = 65; p.strokeWidth = 1.5f*scale
            canvas.drawLine(axis.left,cy,axis.right,cy,p)
            // Every hour has the same visible spacing, even when labels need to be skipped.
            var hour = chart.start.atZone(KYIV).withMinute(0).withSecond(0).withNano(0).plusHours(1)
            while (hour.toInstant() < chart.end) {
                p.color = colors.muted; p.alpha = 85; p.strokeWidth = .75f
                val hx = x(hour.toInstant())
                canvas.drawLine(hx,cy+3f*scale,hx,cy+6f*scale,p)
                hour = hour.plusHours(1)
            }
            row.spans.forEach spanLoop@ { span ->
                val left = x(span.start); val right = x(span.end)
                if (right > left) {
                    if (span.state == PowerState.UNKNOWN) {
                        p.color = colors.muted; p.alpha = 170; p.strokeWidth = 2f*scale
                        p.pathEffect = DashPathEffect(floatArrayOf(1f*scale,4f*scale),0f)
                        canvas.drawLine(left,cy,right,cy,p); p.pathEffect = null
                        return@spanLoop
                    }
                    if (span.state !in outages) return@spanLoop
                    val emergencySpan = span.state == PowerState.EMERGENCY_OUTAGE
                    p.color = if (mono) colors.ink else if (emergencySpan) { if (colors.dark) Color.rgb(255,102,107) else Color.rgb(187,37,43) }
                        else if (span.state == PowerState.SCHEDULED_OUTAGE) { if (colors.dark) Color.rgb(139,183,255) else Color.rgb(54,103,175) }
                        else if (colors.dark) Color.rgb(255,187,88) else Color.rgb(169,87,0)
                    p.alpha = 255; p.style = Paint.Style.FILL
                    val rect = RectF(left,cy-2f*scale,right,cy+2f*scale)
                    if (span.state == PowerState.POSSIBLE_OUTAGE) {
                        canvas.save(); canvas.clipRect(rect)
                        p.strokeWidth = 1.5f*scale
                        var hatch = left-8f*scale
                        while (hatch < right+8f*scale) {
                            canvas.drawLine(hatch,rect.bottom,hatch+6f*scale,rect.top,p); hatch += 5f*scale
                        }
                        canvas.restore()
                    } else if (mono && emergencySpan) {
                        p.strokeWidth = 2f*scale
                        canvas.drawLine(left,cy-3f*scale,right,cy-3f*scale,p)
                        canvas.drawLine(left,cy+3f*scale,right,cy+3f*scale,p)
                    } else canvas.drawRoundRect(rect,3f*scale,3f*scale,p)
                }
            }
            // A path has deterministic bounds and cannot be ellipsized or substituted by a font.
            if (row.continues && row.spans.lastOrNull()?.state in outages) {
                val edge = row.spans.last().state
                p.color = if (mono) colors.ink else if (edge == PowerState.EMERGENCY_OUTAGE) {
                    if (colors.dark) Color.rgb(255,102,107) else Color.rgb(187,37,43)
                } else if (edge == PowerState.SCHEDULED_OUTAGE) { if (colors.dark) Color.rgb(139,183,255) else Color.rgb(54,103,175) }
                else if (colors.dark) Color.rgb(255,187,88) else Color.rgb(169,87,0)
                p.alpha = 255; p.style = Paint.Style.FILL
                val tip = w-2f
                val base = tip-(6f*scale).coerceIn(4f,9f)
                val halfHeight = (3f*scale).coerceAtMost(5f)
                // A separate filled head in its own gutter: no rail or double-line intersections.
                canvas.drawPath(Path().apply {
                    moveTo(base,cy-halfHeight); lineTo(tip,cy); lineTo(base,cy+halfHeight); close()
                },p)
            }
            // Fade elapsed time, keeping the same geometry and future-event colours.
            canvas.save(); canvas.clipRect(axis.left,cy-6f*scale,nowX,cy+6f*scale)
            p.color = colors.background; p.alpha = if (Color.alpha(colors.background) == 0) 0 else 125
            canvas.drawRect(axis.left,cy-6f*scale,nowX,cy+6f*scale,p); canvas.restore()
            top += itemHeight
        }
        drawMidnight?.invoke()
        if (axisHeight > 0f) {
            p.color = if (Color.alpha(colors.background) > 0) colors.background else if (colors.dark) Color.BLACK else Color.WHITE
            p.alpha = 220; p.strokeWidth = 3f*scale
            canvas.drawLine(nowX,36f*scale,nowX,(lastRail ?: axisHeight)+7f*scale,p)
            p.color = colors.accent ?: colors.ink; p.alpha = 180; p.strokeWidth = 1.2f*scale
            canvas.drawLine(nowX,36f*scale,nowX,(lastRail ?: axisHeight)+7f*scale,p)
            p.alpha = 255
            canvas.drawCircle(nowX,36f*scale,2.5f*scale,p)
        }
        if (chart.hidden > 0) text("Ще ${chart.hidden} адрес · відкрити",textX,height-4f*scale,13f,colors.muted)
        return bitmap
    }
}
