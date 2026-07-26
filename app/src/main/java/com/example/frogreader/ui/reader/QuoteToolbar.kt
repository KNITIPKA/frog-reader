package com.example.frogreader.ui.reader

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.example.frogreader.R
import kotlin.math.roundToInt

/**
 * Replaces the system text-selection toolbar inside the reader so we can add
 * an "Add quote" action next to Copy.
 */
class QuoteTextToolbar : TextToolbar {

    class MenuState(
        val rect: Rect,
        val onCopy: (() -> Unit)?,
    )

    var menu by mutableStateOf<MenuState?>(null)
        private set

    override val status: TextToolbarStatus
        get() = if (menu != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        menu = MenuState(rect, onCopyRequested)
    }

    override fun hide() {
        menu = null
    }
}

/** Floating Copy / Add quote / Translate / Select all pill above the selection. */
@Composable
fun QuoteToolbarPopup(
    toolbar: QuoteTextToolbar,
    colors: ReaderColors,
    onQuote: () -> Unit,
    onTranslate: () -> Unit,
) {
    val menu = toolbar.menu ?: return
    val density = LocalDensity.current
    val yOffset = with(density) {
        (menu.rect.top - 64.dp.toPx()).roundToInt().coerceAtLeast(0)
    }

    Popup(offset = IntOffset(menu.rect.left.roundToInt(), yOffset)) {
        Surface(
            color = colors.chrome,
            contentColor = colors.onChrome,
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
            ) {
                ToolbarAction(
                    label = stringResource(R.string.selection_quote),
                    icon = @Composable {
                        Icon(Icons.Rounded.FormatQuote, contentDescription = null)
                    },
                ) {
                    onQuote()
                    toolbar.hide()
                }
                ToolbarAction(
                    label = stringResource(R.string.selection_translate),
                    icon = @Composable {
                        Icon(Icons.Rounded.Translate, contentDescription = null)
                    },
                ) {
                    onTranslate()
                    toolbar.hide()
                }
                ToolbarAction(
                    label = stringResource(R.string.selection_copy),
                    icon = @Composable {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                    },
                ) {
                    menu.onCopy?.invoke()
                    toolbar.hide()
                }

            }
        }
    }
}

@Composable
private fun ToolbarAction(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) {
        icon()
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}
