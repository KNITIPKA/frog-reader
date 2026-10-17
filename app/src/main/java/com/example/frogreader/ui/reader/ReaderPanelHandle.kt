package com.example.frogreader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.frogreader.R

internal val ReaderPanelHandleHeight = 24.dp

/** Fixed above the scrolling content; tapping also exposes expansion to accessibility. */
@Composable
internal fun ReaderPanelHandle(
    colors: ReaderColors,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
    open: Boolean = true,
) {
    val action = stringResource(
        when {
            !open -> R.string.reader_panel_open
            expanded -> R.string.reader_panel_collapse
            else -> R.string.reader_panel_expand
        },
    )
    val state = stringResource(
        when {
            !open -> R.string.reader_panel_closed
            expanded -> R.string.reader_panel_expanded
            else -> R.string.reader_panel_compact
        },
    )
    // A shallow drag strip, with a separate centred tap target. Keep it in the
    // footer even at zero panel height so opening is discoverable.
    Box(
        modifier.fillMaxWidth().height(ReaderPanelHandleHeight),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .width(64.dp)
                .height(ReaderPanelHandleHeight)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = action, onClick = onToggleExpand)
                .semantics {
                    contentDescription = action
                    stateDescription = state
                    if (expanded) collapse(action) { onToggleExpand(); true }
                    else expand(action) { onToggleExpand(); true }
                },
        ) {
            val width = 20.dp.toPx()
            val gap = 3.dp.toPx()
            repeat(3) { index ->
                val y = size.height / 2f + (index - 1) * gap
                drawLine(
                    color = colors.onChromeVariant.copy(alpha = 0.65f),
                    start = Offset((size.width - width) / 2f, y),
                    end = Offset((size.width + width) / 2f, y),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
