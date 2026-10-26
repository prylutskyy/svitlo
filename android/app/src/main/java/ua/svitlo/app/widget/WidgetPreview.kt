package ua.svitlo.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ua.svitlo.app.ui.SvitloIcons
import ua.svitlo.app.domain.*
import java.time.Instant
import kotlin.math.roundToInt

@Composable internal fun WidgetEditorLayout(modifier: Modifier,landscape: Boolean,preview: @Composable () -> Unit,
    controls: @Composable ColumnScope.() -> Unit) {
    if (landscape) Row(modifier) {
        Column(Modifier.weight(.45f).fillMaxHeight()) { preview() }
        Column(Modifier.weight(.55f).fillMaxHeight(),content = controls)
    } else Column(modifier) { preview(); controls() }
}

/** The real RemoteViews renderer, measured at the launcher's size. No clicks or state writes. */
internal fun nativeWidgetPreview(context: Context,state: AppState,config: WidgetConfig,width: Int,height: Int,now: Instant): Bitmap {
    val root = SvitloWidgets.views(context,state,config,config.widgetId,width,height,now,interactive = false)
        .apply(context,FrameLayout(context))
    val density = context.resources.displayMetrics.density
    val w = (width*density).roundToInt(); val h = (height*density).roundToInt()
    root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
    root.layout(0,0,w,h)
    val bottom = root.findViewById<View>(ua.svitlo.app.R.id.widget_root).bottom.coerceIn(1,h)
    return Bitmap.createBitmap(w,bottom,Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
}

@Composable internal fun WidgetPinnedPreview(state: AppState,config: WidgetConfig,context: Context,
    width: Int,height: Int,maxHeight: Float,now: Instant,example: Boolean,onExample: (Boolean) -> Unit) {
    val previewState = remember(state,config.addressIds,now,example) { if (example) widgetExample(state,config,now) else state }
    val dormant = config.hideWhenQuiet == true && config.addressIds.isNotEmpty() &&
        WidgetVisibility.rows(config.chart(previewState,now),false).isEmpty()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val bitmap = remember(previewState,config,width,height,now,context.resources.configuration.uiMode,
        context.resources.configuration.fontScale) { nativeWidgetPreview(context,previewState,config,width,height,now) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer,tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp,vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
                Text(if (example) "Приклад подій" else "Ваш віджет",Modifier.weight(1f),style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { onExample(!example) },enabled = config.addressIds.isNotEmpty()) {
                    Text(if (example) "Мої дані" else "Показати приклад")
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val previewHeight = (maxWidth.value*bitmap.height/bitmap.width).coerceIn(64f,maxHeight)
                Box(Modifier.fillMaxWidth().height(previewHeight.dp).clipToBounds(),contentAlignment = Alignment.Center) {
                    PreviewWallpaper(config,Modifier.matchParentSize())
                    if (config.addressIds.isEmpty()) Text("Виберіть локацію нижче",Modifier.padding(16.dp),style = MaterialTheme.typography.bodyMedium,color = Color(WidgetPalette.resolve(context,config).ink))
                    else if (dormant) Text("Віджет прихований · немає відомих відключень",Modifier.padding(16.dp),style = MaterialTheme.typography.bodySmall,color = Color(WidgetPalette.resolve(context,config).ink))
                    else Image(bitmap.asImageBitmap(),"Попередній перегляд віджета",contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().clickable(onClickLabel = "Розгорнути перегляд") { expanded = true })
                }
            }
        }
    }
    if (expanded) Dialog(onDismissRequest = { expanded = false },properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp),shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (example) "Приклад подій" else "Ваш віджет",Modifier.weight(1f),style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { expanded = false }) { Icon(SvitloIcons.Close,"Закрити перегляд") }
                }
                Box(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp*.75f).dp).verticalScroll(rememberScrollState())) {
                    PreviewWallpaper(config,Modifier.matchParentSize())
                    Image(bitmap.asImageBitmap(),"Розгорнутий перегляд віджета",Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat()/bitmap.height))
                }
            }
        }
    }
}

/** A locally drawn wallpaper, never the user's actual wallpaper or a permission request. */
@Composable private fun PreviewWallpaper(config: WidgetConfig,modifier: Modifier) {
    val lightInk = !WidgetPalette.resolve(androidx.compose.ui.platform.LocalContext.current,config).dark
    val stops = if (lightInk) listOf(Color(0xFFE4EDF7),Color(0xFFD3DCEE),Color(0xFFF0DCD6))
        else listOf(Color(0xFF142747),Color(0xFF36466B),Color(0xFF59405E))
    Canvas(modifier.clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(stops))) {
        drawCircle(Color.White.copy(alpha = .08f),size.width*.55f,androidx.compose.ui.geometry.Offset(size.width*.9f,size.height*1.1f))
        drawCircle(Color.White.copy(alpha = .06f),size.width*.4f,androidx.compose.ui.geometry.Offset(size.width*.1f,-size.height*.2f))
    }
}

/** Illustrations only: never stored, refreshed or published to an actual widget. */
private fun widgetExample(state: AppState,config: WidgetConfig,now: Instant): AppState {
    val today = now.atZone(KYIV).toLocalDate()
    val schedules = config.addressIds.mapIndexed { index,id ->
        val start = now.plusSeconds(1800+(index%3)*900L)
        val end = start.plusSeconds(7200)
        val days = (0..1).map { offset ->
            val date = today.plusDays(offset.toLong()); val midnight = date.atStartOfDay(KYIV).toInstant()
            val a = java.time.Duration.between(midnight,start).toMinutes().toInt().coerceIn(0,1440)
            val b = java.time.Duration.between(midnight,end).toMinutes().toInt().coerceIn(0,1440)
            val power = if (index%3 == 2) PowerState.POSSIBLE_OUTAGE else PowerState.CONFIRMED_OUTAGE
            val slots = listOf(Slot(0,a,PowerState.NO_SCHEDULED_OUTAGE),Slot(a,b,power),Slot(b,1440,PowerState.NO_SCHEDULED_OUTAGE)).filter { it.endMinute > it.startMinute }
            ScheduleDay("",date.toString(),slots)
        }
        CachedSchedule(id,Schedule(now.toEpochMilli(),days,emptyList(),
            if (index%3 == 1) CurrentOutage("Приклад аварійного відключення",true,now.minusSeconds(900).toEpochMilli(),now.plusSeconds(5400).toEpochMilli(),"","") else null))
    }
    return state.copy(schedules = state.schedules.filterNot { it.addressId in config.addressIds }+schedules)
}
