@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ua.svitlo.app.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A normal screen destination: persistent app bar, system back, and one content scroll owner. */
@Composable
internal fun FullScreenPage(title: String, onBack: () -> Unit,
    previousContent: (@Composable ColumnScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val progress = remember { Animatable(0f) }
    var direction by remember { mutableFloatStateOf(1f) }
    var closing by remember { mutableStateOf(false) }
    val back by rememberUpdatedState(onBack)
    val scope = rememberCoroutineScope()
    suspend fun finish() {
        if (closing) return
        closing = true
        try {
            progress.animateTo(1f,tween(180))
            back()
            // A wizard may go back one step without removing this screen instance.
            progress.snapTo(0f)
        } finally { closing = false }
    }
    // Keep this callback enabled until the destination is removed. Disabling it while
    // finishing cancels AndroidX's in-flight gesture and would undo the navigation.
    PredictiveBackHandler { events ->
        try {
            events.collect { event ->
                direction = if (event.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                progress.snapTo(event.progress.coerceIn(0f,1f))
            }
            finish()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { progress.animateTo(0f,tween(180)) }
            throw cancelled
        }
    }
    Box(Modifier.fillMaxSize()) {
    if (previousContent != null && progress.value > 0f) Scaffold(modifier = Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
    },topBar = {
        TopAppBar(title = { Text(title,maxLines = 1,overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },navigationIcon = { IconButton(onClick = {},enabled = false) { Icon(SvitloIcons.Back,null) } })
    }) { padding -> Column(Modifier.fillMaxSize().padding(padding)) { previousContent() } }
    Scaffold(modifier = Modifier.fillMaxSize().graphicsLayer {
        val fraction = progress.value
        translationX = direction * size.width * fraction
        scaleX = 1f - 0.06f * fraction
        scaleY = 1f - 0.06f * fraction
        shape = RoundedCornerShape((28f * fraction).dp)
        clip = fraction > 0f
        shadowElevation = 12.dp.toPx() * fraction
    },topBar = {
        TopAppBar(title = { Text(title,maxLines = 1,overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },navigationIcon = {
            IconButton(onClick = { scope.launch { finish() } },enabled = !closing) { Icon(SvitloIcons.Back,"Назад") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) { content() }
    }
    }
}
