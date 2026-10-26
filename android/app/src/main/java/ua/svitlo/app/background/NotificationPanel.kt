package ua.svitlo.app.background

import android.graphics.*
import android.text.TextPaint
import android.text.TextUtils
import ua.svitlo.app.domain.*
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

/** Measured notification artwork. At most 220 dp high; preserves font size and source order. */
internal object NotificationPanel {
    private val outage = OUTAGE_STATES
    fun hasEvent(row: ChartRow,chart: NotificationChart) = row.emergency || row.current in outage ||
        row.spans.any { it.state in outage && it.end > chart.start && it.start < chart.end } ||
        row.status.startsWith("Відключення ·") || row.status.startsWith("Аварійне")
    private fun priority(row: ChartRow) = when {
        row.emergency || row.current == PowerState.EMERGENCY_OUTAGE -> 0
        row.current in outage -> 1
        else -> 2
    }
    private fun lines(row: ChartRow,width: Float,scale: Float): List<String> {
        val value = row.status.ifEmpty { "Відключень не позначено" }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f*scale; typeface = Typeface.create("sans-serif",Typeface.NORMAL) }
        val available = (width-width/12-16f).coerceAtLeast(1f)
        if (paint.measureText(value) <= available) return listOf(value)
        var end = paint.breakText(value,true,available,null)
        val space = value.lastIndexOf(' ',(end-1).coerceAtLeast(0))
        if (space > end/2) end = space
        return listOf(value.take(end).trim(),TextUtils.ellipsize(value.drop(end).trim(),paint,available,TextUtils.TruncateAt.END).toString())
    }
    private fun rowHeight(row: ChartRow,width: Float,scale: Float) = (50f+14f*(lines(row,width,scale).size-1))*scale
    fun fit(chart: NotificationChart,width: Float,fontScale: Float): NotificationChart {
        val scale = fontScale.coerceIn(1f,1.5f)
        val events = chart.rows.filter { hasEvent(it,chart) }
        val candidates = events.sortedWith(compareBy<ChartRow> { priority(it) }.thenBy {
            it.spans.firstOrNull { span -> span.state in outage && span.end > chart.now }?.start ?: Instant.MAX
        })
        val selected = mutableSetOf<String>()
        candidates.forEach { row ->
            val proposal = events.filter { it.address.id in selected || it.address.id == row.address.id }
            val height = 24f*scale+proposal.sumOf { rowHeight(it,width,scale).toDouble() }+
                (if (proposal.size < events.size) 18f else 4f)*scale
            if (height <= 220f) selected += row.address.id
        }
        return chart.copy(rows = events.filter { it.address.id in selected },total = events.size,focus = candidates.firstOrNull())
    }
    fun draw(chart: NotificationChart,dark: Boolean,width: Float,fontScale: Float): Bitmap {
        val scale = fontScale.coerceIn(1f,1.5f)
        val w = width.coerceIn(216f,420f)
        val height = 24f*scale+chart.rows.sumOf { rowHeight(it,w,scale).toDouble() }.toFloat()+
            (if (chart.hidden > 0) 18f else 4f)*scale
        val bitmap = Bitmap.createBitmap(ceil(w*2).toInt(),ceil(height*2).toInt(),Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { scale(2f,2f) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val label = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val ink = if (dark) Color.rgb(243,243,243) else Color.rgb(29,32,37)
        val muted = if (dark) Color.rgb(190,194,200) else Color.rgb(82,90,102)
        val orange = if (dark) Color.rgb(255,187,88) else Color.rgb(169,87,0)
        val red = if (dark) Color.rgb(255,102,107) else Color.rgb(187,37,43)
        val duration = Duration.between(chart.start,chart.end).toMillis().coerceAtLeast(1).toDouble()
        fun x(at: Instant) = (w*Duration.between(chart.start,at).toMillis()/duration).toFloat().coerceIn(0f,w)
        fun text(value: String,left: Float,baseline: Float,size: Float,color: Int,bold: Boolean = false) {
            label.textSize = size*scale; label.color = color
            label.typeface = Typeface.create("sans-serif",if (bold) Typeface.BOLD else Typeface.NORMAL)
            canvas.drawText(TextUtils.ellipsize(value,label,(w-left).coerceAtLeast(1f),TextUtils.TruncateAt.END).toString(),left,baseline,label)
        }
        val marker = x(chart.now); val textLeft = marker+12f
        val clock = DateTimeFormatter.ofPattern("HH:mm")
        text(chart.now.atZone(KYIV).format(clock),0f,14f*scale,12f,ink,true)
        val local = chart.now.atZone(KYIV)
        val first = local.withMinute(0).withSecond(0).withNano(0).plusHours((3-local.hour%3).toLong())
        var previousRight = 48f*scale
        (0..3).map { first.plusHours(it*3L).toInstant() }.filter { it < chart.end }.forEach { tick ->
            val left = (x(tick)-16f*scale).coerceIn(0f,(w-33f*scale).coerceAtLeast(0f))
            if (left > previousRight+8f) { text(tick.atZone(KYIV).format(clock),left,14f*scale,11f,muted); previousRight = left+33f*scale }
        }
        var top = 24f*scale
        val gaps = mutableListOf<Pair<Float,Float>>()
        chart.rows.forEach { row ->
            val status = lines(row,w,scale)
            val color = if (row.emergency || row.current == PowerState.EMERGENCY_OUTAGE) red else if (row.current in outage || row.spans.any { it.end > chart.now && it.state in outage }) orange else muted
            text(row.address.title,textLeft,top+14f*scale,14f,ink,true)
            status.forEachIndexed { index,value -> text(value,textLeft,top+(30f+14f*index)*scale,12f,color) }
            val cy = top+(40f+14f*(status.size-1))*scale
            paint.color = muted; paint.alpha = 65; paint.strokeWidth = 1.5f*scale
            canvas.drawLine(0f,cy,w,cy,paint)
            row.spans.forEach { span ->
                val a = x(span.start); val b = x(span.end)
                if (b > a && span.state in outage) {
                    paint.color = if (span.state == PowerState.EMERGENCY_OUTAGE) red else orange; paint.alpha = 255
                    val rect = RectF(a,cy-3f*scale,b,cy+3f*scale)
                    if (span.state == PowerState.POSSIBLE_OUTAGE) {
                        canvas.save(); canvas.clipRect(rect); paint.strokeWidth = 1.5f*scale
                        var hatch = a-6f*scale
                        while (hatch < b+6f*scale) { canvas.drawLine(hatch,rect.bottom,hatch+6f*scale,rect.top,paint); hatch += 5f*scale }
                        canvas.restore()
                    } else canvas.drawRoundRect(rect,3f*scale,3f*scale,paint)
                }
            }
            if (row.continues) text("›",w-8f*scale,cy+5f*scale,14f,color,true)
            val bottom = top+rowHeight(row,w,scale)
            gaps += (cy-5f*scale) to bottom
            top = bottom
        }
        val midnight = chart.now.atZone(KYIV).toLocalDate().plusDays(1).atStartOfDay(KYIV).toInstant()
        if (midnight < chart.end) {
            paint.color = muted; paint.alpha = 65; paint.strokeWidth = .8f
            paint.pathEffect = DashPathEffect(floatArrayOf(3f,5f),0f)
            gaps.forEach { (a,b) -> canvas.drawLine(x(midnight),a,x(midnight),b,paint) }
            paint.pathEffect = null
        }
        paint.color = ink; paint.alpha = 160; paint.strokeWidth = 1f
        canvas.drawLine(marker,20f*scale,marker,top-6f*scale,paint)
        paint.alpha = 255; canvas.drawCircle(marker,20f*scale,2.5f*scale,paint)
        if (chart.hidden > 0) {
            val ending = when {
                chart.hidden%100 in 11..14 -> "подій"
                chart.hidden%10 == 1 -> "подія"
                chart.hidden%10 in 2..4 -> "події"
                else -> "подій"
            }
            text("Ще ${chart.hidden} $ending · у застосунку",textLeft,height-5f*scale,11f,muted)
        }
        return bitmap
    }
}
