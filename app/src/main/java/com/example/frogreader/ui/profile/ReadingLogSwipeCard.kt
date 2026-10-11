package com.example.frogreader.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.data.model.ReadingLogEntry
import kotlinx.coroutines.delay

/** Removing a row uses the Log's durable deletion and existing Undo receipt. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadingLogSwipeCard(
    entry: ReadingLogEntry, enabled: Boolean, busy: Boolean,
    remove: (ReadingLogEntry) -> Unit,
    initialValue: SwipeToDismissBoxValue = SwipeToDismissBoxValue.Settled,
    content: @Composable (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val swipe = remember(entry.id, entry.updatedAtMillis) {
        SwipeToDismissBoxState(initialValue, positionalThreshold = { it * .4f })
    }
    var handled by remember(swipe) { mutableStateOf(false) }
    LaunchedEffect(busy, swipe.settledValue) {
        if (swipe.settledValue == SwipeToDismissBoxValue.Settled) handled = false
        else if (!busy) {
            // Give the save a chance to begin. A failed deletion keeps the row
            // in the list and returns it to place, so it can be retried.
            delay(150)
            swipe.reset()
        }
    }
    SwipeToDismissBox(state = swipe, gesturesEnabled = enabled,
        enableDismissFromStartToEnd = false,
        onDismiss = { direction ->
            if (!handled && direction == SwipeToDismissBoxValue.EndToStart) {
                handled = true
                remove(entry)
            }
        },
        backgroundContent = {
            Row(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(colors.errorContainer)
                .padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End) {
                Icon(Icons.Rounded.DeleteOutline, null, tint = colors.onErrorContainer)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.reading_log_remove_action), style = MaterialTheme.typography.labelLarge,
                    color = colors.onErrorContainer)
            }
        }) {
        content(enabled && swipe.dismissDirection == SwipeToDismissBoxValue.Settled)
    }
}
