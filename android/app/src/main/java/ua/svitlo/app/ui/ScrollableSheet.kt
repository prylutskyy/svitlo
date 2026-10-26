@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ua.svitlo.app.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch

/** Native anchored dragging, nested scrolling, settling and predictive dismissal. */
@Composable
internal fun ScrollableSheet(onDismissRequest: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.(close: () -> Unit) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheet,
        dragHandle = { BottomSheetDefaults.DragHandle(Modifier.semantics { contentDescription = "Потягніть вниз, щоб закрити" }) }) {
        content {
            scope.launch {
                sheet.hide()
                if (!sheet.isVisible) onDismissRequest()
            }
        }
    }
}
