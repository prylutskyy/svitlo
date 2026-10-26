package ua.svitlo.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ua.svitlo.app.domain.*
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

private class TimelineInteraction {
    var reset by mutableIntStateOf(0)
    var point: Offset? = null
    val bounds = mutableMapOf<Any, Rect>()
}
private val LocalTimelineInteraction = staticCompositionLocalOf { TimelineInteraction() }

/** Observe outside taps without consuming gestures or stealing actions from buttons and scrolling. */
@Composable internal fun TimelineInteractionHost(content: @Composable () -> Unit) {
    val interaction = remember { TimelineInteraction() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    CompositionLocalProvider(LocalTimelineInteraction provides interaction) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.boundsInRoot().topLeft }.pointerInput(interaction) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var moved = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { (it.position-down.position).getDistance() > viewConfiguration.touchSlop }) moved = true
                } while (event.changes.any { it.pressed })
                if (!moved) { interaction.point = down.position+origin; interaction.reset++ }
            }
        }) { content() }
    }
}

@Composable internal fun InteractiveTimeline(spans: List<ChartSpan>, base: TimelineWindow, now: Instant) {
    val colors = MaterialTheme.colorScheme
    val planned = outageColor(PowerState.SCHEDULED_OUTAGE)
    val reported = outageColor(PowerState.CONFIRMED_OUTAGE)
    val emergency = outageColor(PowerState.EMERGENCY_OUTAGE)
    val interaction = LocalTimelineInteraction.current
    val key = remember { Any() }
    var focus by remember { mutableStateOf<Instant?>(null) }
    var selected by remember { mutableStateOf<ChartSpan?>(null) }
    LaunchedEffect(interaction.reset) {
        if (interaction.point?.let { interaction.bounds[key]?.contains(it) } != true) { focus = null; selected = null }
    }
    DisposableEffect(key) { onDispose { interaction.bounds.remove(key) } }
    val target = focus?.let(TimelineNavigation::around) ?: base
    val offset by animateFloatAsState(Duration.between(base.start, target.start).seconds.toFloat(), tween(320), label = "Позиція графіка")
    val start = base.start.plusMillis((offset*1000).toLong())
    val window = TimelineWindow(start,start.plus(Duration.between(base.start,base.end)))
    val millis = Duration.between(window.start,window.end).toMillis().coerceAtLeast(1).toDouble()
    fun fraction(at: Instant) = (Duration.between(window.start,at).toMillis()/millis).toFloat()
    val next = TimelineNavigation.next(spans,target)
    val clock = DateTimeFormatter.ofPattern("HH:mm")
    fun jump(span: ChartSpan) { focus = span.start; selected = span }
    fun select(span: ChartSpan?) { selected = span }
    val continuing = TimelineNavigation.continuing(spans,target)
    val fontScale = LocalDensity.current.fontScale
    Column(Modifier.fillMaxWidth().onGloballyPositioned { interaction.bounds[key] = it.boundsInRoot() },
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
            Text("${(Duration.between(target.start,target.end).seconds+3599)/3600} год · ${TimelineNavigation.day(target.start,now).lowercase()}",Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,color = colors.onSurfaceVariant)
            if (focus != null) TextButton(onClick = { focus = null; selected = null },contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Зараз") }
            else Text("Зараз ${now.atZone(KYIV).format(clock)}",style = MaterialTheme.typography.labelMedium,color = colors.onSurfaceVariant)
        }
        Canvas(Modifier.fillMaxWidth().height(48.dp).semantics {
            contentDescription = "Часова шкала. ${target.start.atZone(KYIV).format(clock)}–${target.end.atZone(KYIV).format(clock)}. Натисніть, щоб вибрати інтервал."
            onClick("Наступний інтервал") {
                val visible = spans.filter { it.end > target.start && it.start < target.end }
                select(visible.getOrNull((visible.indexOf(selected)+1)%visible.size.coerceAtLeast(1)))
                visible.isNotEmpty()
            }
        }.pointerInput(spans,window) { detectTapGestures { point ->
            val at = window.start.plusMillis(((point.x-5.dp.toPx())/(size.width-10.dp.toPx()).coerceAtLeast(1f)*millis).toLong().coerceIn(0,millis.toLong()-1))
            select(TimelineNavigation.at(spans,at))
        } }) {
            val y = 22.dp.toPx(); val thick = 10.dp.toPx(); val inset = 5.dp.toPx()
            fun x(at: Instant) = inset+(size.width-2*inset)*fraction(at)
            drawLine(colors.outlineVariant,Offset(inset,y),Offset(size.width-inset,y),3.dp.toPx(),StrokeCap.Round)
            spans.filter { it.end > window.start && it.start < window.end }.forEach { span ->
                val a = x(maxOf(span.start,window.start)); val b = x(minOf(span.end,window.end))
                val ink = when (span.state) {
                    PowerState.EMERGENCY_OUTAGE -> emergency
                    PowerState.CONFIRMED_OUTAGE -> reported
                    PowerState.SCHEDULED_OUTAGE,PowerState.POSSIBLE_OUTAGE -> planned
                    else -> colors.onSurfaceVariant.copy(alpha = .5f)
                }
                if (span.state in OUTAGE_STATES) {
                    val brush = if (span.end > window.end) Brush.horizontalGradient(0f to ink,.85f to ink,1f to ink.copy(alpha = .12f),startX = a,endX = b)
                        else SolidColor(ink)
                    drawLine(brush,Offset(a,y),Offset(b,y),thick)
                }
                if (span.state == PowerState.UNKNOWN) {
                    var at = a
                    while (at < b) { drawLine(ink,Offset(at,y),Offset(minOf(at+3.dp.toPx(),b),y),2.dp.toPx()); at += 7.dp.toPx() }
                }
                if (span == selected) {
                    drawLine(ink,Offset(a,y+10.dp.toPx()),Offset(b,y+10.dp.toPx()),2.dp.toPx())
                    drawCircle(ink,3.dp.toPx(),Offset((a+b)/2,y+10.dp.toPx()))
                }
            }
            val first = window.start.atZone(KYIV).withMinute(0).withSecond(0).withNano(0)
            val hours = Duration.between(window.start,window.end).toHours()
            val majorStep = if (hours > 24) 12 else if (hours > 12) 6 else 3
            (0..(hours+1).coerceAtMost(336).toInt()).map { first.plusHours(it.toLong()).toInstant() }
                .filter { it >= window.start && it <= window.end && (hours <= 24 || it.atZone(KYIV).hour%3 == 0) }.forEach { tick ->
                val major = tick.atZone(KYIV).hour%majorStep == 0
                drawLine(colors.onSurfaceVariant.copy(alpha = if (major) .6f else .25f),
                    Offset(x(tick),y+thick/2+4.dp.toPx()),Offset(x(tick),y+thick/2+(if (major) 11 else 7).dp.toPx()),1.dp.toPx())
            }
            if (now >= window.start && now < window.end) {
                val at = x(now)
                // A contrasting gap keeps the marker clear across both the rail and coloured intervals.
                drawLine(colors.surface,Offset(at,3.dp.toPx()),Offset(at,39.dp.toPx()),5.dp.toPx())
                drawLine(colors.onSurface,Offset(at,3.dp.toPx()),Offset(at,39.dp.toPx()),2.dp.toPx())
                drawCircle(colors.onSurface,3.dp.toPx(),Offset(at,3.dp.toPx()))
            }

        }
        val datesOnAxis = Duration.between(window.start,window.end).toHours() > 24
        BoxWithConstraints(Modifier.fillMaxWidth().height(((if (datesOnAxis) 36 else 20)*fontScale).dp)) {
            val width = maxWidth.value
            val labelWidth = (42*fontScale).coerceAtMost(width)
            var right = -4f
            TimelineNavigation.ticks(window).forEach { tick ->
                val left = (width*fraction(tick)-labelWidth/2).coerceIn(0f,(width-labelWidth).coerceAtLeast(0f))
                if (left >= right+4f) {
                    Text(tick.atZone(KYIV).format(if (datesOnAxis) DateTimeFormatter.ofPattern("dd.MM\nHH:mm") else clock),Modifier.offset(x = left.dp).width(labelWidth.dp),
                        style = MaterialTheme.typography.labelSmall,textAlign = TextAlign.Center,color = colors.onSurfaceVariant)
                    right = left+labelWidth
                }
            }
        }
        selected?.let { span ->
            Text(TimelineNavigation.range(span,now),style = MaterialTheme.typography.bodyMedium)
            Text(powerLabel(span.state) + when (span.state) {
                PowerState.CONFIRMED_OUTAGE,PowerState.EMERGENCY_OUTAGE -> " · завершення орієнтовне"
                in OUTAGE_STATES -> " · ${remainingTime(span.end,span.start)}"
                else -> ""
            },
                style = MaterialTheme.typography.bodySmall,color = colors.onSurfaceVariant)
        }
        if (continuing != null) TextButton(onClick = { focus = target.end.plusSeconds(3600); selected = continuing },modifier = Modifier.fillMaxWidth()) {
            Text("Відключення триває далі →")
        }
        if (next != null) TextButton(onClick = { jump(next) },modifier = Modifier.fillMaxWidth()) {
            Text("Далі · ${TimelineNavigation.day(next.start,now).lowercase()} ${next.start.atZone(KYIV).format(clock)} →")
        }
    }
}
