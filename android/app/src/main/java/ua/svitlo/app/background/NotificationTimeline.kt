package ua.svitlo.app.background

import android.graphics.*
import android.text.TextPaint
import android.text.TextUtils
import ua.svitlo.app.domain.*
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

/** One shared clock and a fixed elapsed-time scale. Rendering never performs network work. */
internal object NotificationTimeline {
    internal data class RowLayout(val height: Float, val graphOffset: Float, val inline: Boolean)

    /** Measure at the actual text size. Never shrink type to force the status onto one line. */
    internal fun rowLayouts(chart: NotificationChart, widthDp: Float, scale: Float, density: Int): List<RowLayout> {
        val width = widthDp.coerceIn(128f,600f)
        val elapsed = Duration.between(chart.start,chart.end).toMillis().coerceAtLeast(1).toFloat()
        val fraction = (Duration.between(chart.start,chart.now).toMillis()/elapsed).coerceIn(0f,1f)
        val available = (width-8f) * (1f-fraction) - 8f
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        return chart.rows.map { row ->
            paint.textSize = 13f * scale; paint.typeface = Typeface.create("sans-serif",Typeface.BOLD)
            val title = paint.measureText(row.address.title)
            paint.textSize = 10.5f * scale; paint.typeface = Typeface.create("sans-serif",Typeface.NORMAL)
            val inline = row.status.isEmpty() || title + paint.measureText(row.status) + 12f*scale <= available
            val height = (if (inline) 30f else 44f) + density.coerceIn(0,2)*10f
            RowLayout(height*scale,(if (inline) 23f else 37f)*scale,inline)
        }
    }

    /** Fit the measured prefix in the user's order, including the hidden-address footer. */
    internal fun fitWidget(chart: NotificationChart, widthDp: Float, availableHeightDp: Float,
                           fontScale: Float, density: Int): NotificationChart {
        if (chart.rows.isEmpty()) return chart
        val scale = fontScale.coerceIn(0.8f,2.1f)
        val rows = rowLayouts(chart,widthDp,scale,density)
        var used = 32f*scale
        var count = 0
        for ((index,row) in rows.withIndex()) {
            val footer = (if (index == rows.lastIndex && chart.hidden == 0) 4f else 16f)*scale
            if (used + row.graphOffset + 6f*scale + footer > availableHeightDp && count > 0) break
            used += row.height; count++
        }
        return chart.copy(rows = chart.rows.take(count.coerceAtLeast(1)))
    }
    fun maxRows(fontScale: Float): Int = ((220f - 48f * fontScale.coerceIn(1f, 1.5f)) /
        (54f * fontScale.coerceIn(1f, 1.5f))).toInt().coerceIn(1, 3)

    fun draw(chart: NotificationChart, dark: Boolean, widthDp: Float = 320f, fontScale: Float = 1f,
             monochrome: Boolean = false, widget: Boolean = false, timelineOnly: Boolean = false,
             widgetDensity: Int = 1, widgetColors: WidgetColors? = null, tva: Boolean = false): Bitmap {
        val scale = if (widget) fontScale.coerceIn(0.8f, 2.1f) else fontScale.coerceIn(1f, 1.5f)
        val width = widthDp.coerceIn(if (widget) 128f else 260f, if (widget) 600f else 420f)
        val top = 32f * scale
        val bottom = (if (chart.hidden > 0) 16f else 4f) * scale
        val layouts = if (widget && !timelineOnly) rowLayouts(chart,width,scale,widgetDensity) else chart.rows.map {
            RowLayout(if (widget) WidgetConfig.rowHeight(scale,widgetDensity) else (if (timelineOnly) 24f else 54f)*scale,
                (if (timelineOnly) 5f else 37f)*scale,false)
        }
        val rowTops = layouts.runningFold(top) { position, row -> position + row.height }.dropLast(1)
        val rowArea = if (widget && !timelineOnly && layouts.isNotEmpty())
            layouts.dropLast(1).sumOf { it.height.toDouble() }.toFloat()+layouts.last().graphOffset+6f*scale
            else layouts.sumOf { it.height.toDouble() }.toFloat()
        val height = top + rowArea + bottom
        val bitmap = Bitmap.createBitmap(ceil(width * 2).toInt(), ceil(height * 2).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { scale(2f, 2f) }
        val ink = widgetColors?.ink ?: if (monochrome) Color.WHITE else if (dark) Color.rgb(240,242,245) else Color.rgb(29,32,37)
        val muted = widgetColors?.muted ?: if (monochrome) Color.LTGRAY else if (dark) Color.rgb(184,190,199) else Color.rgb(82,90,102)
        val emergency = if (monochrome) ink else if (dark) Color.rgb(255,90,95) else Color.rgb(211,47,47)
        val outage = if (monochrome) ink else if (dark) Color.rgb(255,176,32) else Color.rgb(208,106,0)
        val orangeText = if (monochrome) ink else if (dark) Color.rgb(255,190,77) else Color.rgb(168,73,0)
        val rail = if (monochrome) {
            if (dark) Color.rgb(50,50,50) else Color.rgb(220,220,220)
        } else if (dark) Color.argb(48,195,201,210) else Color.argb(42,82,90,102)
        val clockInk = if (monochrome) ink else widgetColors?.accent ?: ink
        val transparent = widgetColors?.background == Color.TRANSPARENT
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val label = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val left = 2f
        val right = width - 6f // room for the continuation arrow
        val duration = Duration.between(chart.start, chart.end).toMillis().toFloat()
        fun x(at: Instant) = left + (right-left) * (Duration.between(chart.start, at).toMillis()/duration).coerceIn(0f,1f)
        val marker = x(chart.now)
        val textLeft = marker + 8f
        val protectedText = mutableListOf<RectF>()
        val midnightGuides = mutableListOf<Pair<Instant,Float>>()
        fun text(value: String, atX: Float, baseline: Float, size: Float, color: Int, maxWidth: Float = right-atX, bold: Boolean = false, protect: Boolean = false) {
            label.textSize = size * scale
            label.color = color
            label.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            val display = TextUtils.ellipsize(value, label, maxWidth.coerceAtLeast(1f), TextUtils.TruncateAt.END).toString()
            canvas.drawText(display, atX, baseline, label)
            if (protect && display.isNotEmpty()) protectedText += RectF(atX-3f*scale,baseline+label.fontMetrics.ascent-2f*scale,
                atX+label.measureText(display)+3f*scale,baseline+label.fontMetrics.descent+2f*scale)
        }
        fun measure(value: String, size: Float, bold: Boolean = false): Float {
            label.textSize = size * scale
            label.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            return label.measureText(value)
        }
        val clock = DateTimeFormatter.ofPattern("HH:mm")
        val window = TimelineWindow(chart.start, chart.end)
        val graphBottom = if (layouts.isEmpty()) top else if (widget && !timelineOnly)
            rowTops.last()+layouts.last().graphOffset+9f*scale else top+layouts.sumOf { it.height.toDouble() }.toFloat()-5f*scale
        // Clock label is attached to the same fixed marker for every address.
        val nowText = chart.now.atZone(KYIV).format(clock)
        val nowWidth = measure(nowText, 11f, true)
        val nowLeft = (marker-nowWidth/2).coerceAtLeast(left)
        text(nowText, nowLeft, 14f*scale, 11f, clockInk, nowWidth+1f, bold = true)
        val occupied = mutableListOf((nowLeft-7f)..(nowLeft+nowWidth+7f))
        fun tick(at: Instant, value: String, midnight: Boolean = false) {
            val measured = measure(value, 10f)
            var a = (x(at)-measured/2).coerceIn(left, (right-measured).coerceAtLeast(left))
            val b = a+measured
            val overlaps = occupied.any { a < it.endInclusive && b > it.start }
            if (overlaps && !midnight) return
            if (overlaps) a = (marker+8f).coerceAtMost((right-measured).coerceAtLeast(left))
            else occupied += (a-7f)..(b+7f)
            text(value, a, (if (overlaps) 28f else 14f)*scale, 10f, muted, measured+1f)
            paint.color = rail; paint.strokeWidth = 1f
            if (midnight) {
                paint.pathEffect = DashPathEffect(floatArrayOf(2f,3f),0f)
                val tickTop = (if (overlaps) 30f else 20f)*scale
                if (widget) midnightGuides += at to tickTop
                else canvas.drawLine(x(at),tickTop,x(at),top-2f*scale,paint)
                paint.pathEffect = null
            }
        }
        // Day boundary has priority over ordinary ticks. All labels share one collision budget.
        window.midnights().filter { widget || it > chart.now }.forEach {
            val day = if (it.atZone(KYIV).toLocalDate() == chart.now.atZone(KYIV).toLocalDate()) "Сьогодні" else "Завтра"
            tick(it, "$day · 00:00", midnight = true)
        }
        var at = chart.now.atZone(KYIV).withMinute(0).withSecond(0).withNano(0).toInstant().plusSeconds(3600)
        while (at < chart.end) {
            val hour = at.atZone(KYIV).hour
            if (hour % 3 == 0 && !(widget && hour == 0)) tick(at, at.atZone(KYIV).format(clock))
            at = at.plusSeconds(3600)
        }
        // A single continuous line lives in the left gutter, away from address labels.
        paint.color = clockInk; paint.alpha = 110; paint.strokeWidth = 1f
        canvas.drawLine(marker,21f*scale,marker,graphBottom,paint)
        paint.alpha = 255; paint.color = clockInk
        if (tva) {
            canvas.drawPath(Path().apply { moveTo(marker,18f*scale); lineTo(marker+3f*scale,21f*scale)
                lineTo(marker,24f*scale); lineTo(marker-3f*scale,21f*scale); close() },paint)
        } else canvas.drawCircle(marker,21f*scale,2f,paint)
        chart.rows.forEachIndexed { index, row ->
            val rowTop = rowTops[index]
            val layout = layouts[index]
            val inlineStatus = widget && !timelineOnly && layout.inline && row.status.isNotEmpty()
            val statusWidth = if (inlineStatus) measure(row.status,10.5f) else 0f
            if (!timelineOnly) text(row.address.title, textLeft, rowTop+12f*scale, 13f, ink,
                maxWidth = if (inlineStatus) right-textLeft-statusWidth-12f*scale else right-textLeft,
                bold = true,protect = widget)
            val statusColor = when {
                row.emergency -> emergency
                row.current in setOf(PowerState.CONFIRMED_OUTAGE,PowerState.POSSIBLE_OUTAGE) ||
                    row.spans.any { it.end > chart.now && it.state in setOf(PowerState.CONFIRMED_OUTAGE,PowerState.POSSIBLE_OUTAGE) } -> orangeText
                else -> muted
            }
            if (!timelineOnly && row.status.isNotEmpty()) text(row.status, if (inlineStatus) right-statusWidth else textLeft,
                rowTop+(if (inlineStatus) 12f else 27f)*scale, 10.5f, statusColor,protect = widget)
            val y = rowTop + layout.graphOffset
            val thickness = 6f*scale
            if (transparent) canvas.saveLayer(left, y-3f, right+5f, y+thickness+3f, null)
            paint.color = rail
            if (tva) canvas.drawPath(Path().apply {
                moveTo(left+3f,y); lineTo(right-3f,y); lineTo(right,y+thickness/2)
                lineTo(right-3f,y+thickness); lineTo(left+3f,y+thickness); lineTo(left,y+thickness/2); close()
            },paint) else canvas.drawRoundRect(left,y,right,y+thickness,thickness/2,thickness/2,paint)
            row.spans.forEach { span ->
                val a = x(span.start); val b = x(span.end)
                if (b <= a) return@forEach
                when (span.state) {
                    PowerState.NO_SCHEDULED_OUTAGE -> Unit
                    PowerState.EMERGENCY_OUTAGE, PowerState.CONFIRMED_OUTAGE -> {
                        paint.color = if (span.state == PowerState.EMERGENCY_OUTAGE) emergency else outage
                        canvas.drawRect(a,y,b,y+thickness,paint)
                        if (monochrome && span.state == PowerState.EMERGENCY_OUTAGE) {
                            // Filled core with a separate contour; the status explicitly names the emergency.
                            paint.color = rail
                            canvas.drawLine(a,y+thickness/2,b,y+thickness/2,paint)
                            paint.color = ink
                        }
                    }
                    PowerState.POSSIBLE_OUTAGE -> {
                        canvas.save(); canvas.clipRect(a,y,b,y+thickness)
                        paint.color = outage; paint.strokeWidth = 1.5f
                        var hatch = a-thickness
                        while (hatch < b+thickness) { canvas.drawLine(hatch,y+thickness,hatch+thickness,y,paint); hatch += 5f }
                        canvas.restore()
                    }
                    PowerState.UNKNOWN -> {
                        // Calm, continuous neutral line; uncertainty is explained in text.
                        paint.color = muted; paint.strokeWidth = 1.5f*scale
                        canvas.drawLine(a,y+thickness/2,b,y+thickness/2,paint)
                    }
                    PowerState.SCHEDULED_OUTAGE -> {
                        paint.color = if (monochrome) ink else if (dark) Color.rgb(139,183,255) else Color.rgb(54,103,175)
                        canvas.drawRect(a,y,b,y+thickness,paint)
                    }
                }
            }
            if (row.continues) {
                paint.color = if (row.spans.last().state == PowerState.EMERGENCY_OUTAGE) emergency else outage
                paint.strokeWidth = 1.5f
                canvas.drawLine(right-3f,y-1f,right+2f,y+thickness/2,paint)
                canvas.drawLine(right+2f,y+thickness/2,right-3f,y+thickness+1f,paint)
            }
            paint.color = muted; paint.strokeWidth = 1f
            if (!widget) window.midnights().forEach { midnight -> canvas.drawLine(x(midnight),y-2f,x(midnight),y+thickness+2f,paint) }
            // Past context stays visible but recedes; no additional clock marker per row.
            if (transparent) {
                paint.color = Color.WHITE; paint.alpha = 160
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                canvas.drawRect(left,y,marker,y+thickness,paint)
                paint.xfermode = null
                canvas.restore()
            } else {
                paint.color = if (dark) Color.BLACK else Color.WHITE; paint.alpha = 95
                canvas.drawRect(left,y,marker,y+thickness,paint)
            }
            paint.alpha = 255
        }
        if (widget) {
            canvas.save()
            protectedText.forEach { canvas.clipOutRect(it) }
            paint.color = muted; paint.alpha = 85; paint.strokeWidth = 0.8f
            paint.pathEffect = DashPathEffect(floatArrayOf(2.5f*scale,4f*scale),0f)
            midnightGuides.forEach { (midnight,startY) -> canvas.drawLine(x(midnight),startY,x(midnight),graphBottom,paint) }
            paint.pathEffect = null; paint.alpha = 255
            canvas.restore()
        }
        if (chart.hidden > 0) text("+${chart.hidden} у додатку",textLeft,height-4f*scale,10f,muted)
        return bitmap
    }
}
