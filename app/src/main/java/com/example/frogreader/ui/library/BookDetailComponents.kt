package com.example.frogreader.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The same text, spacing and typography in both modes; editing adds only an underline. */
@Composable
internal fun BookDetailField(
    value: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    onChange: ((String) -> Unit)? = null,
    enabled: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    isError: Boolean = false,
    supportingText: String? = null,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val editing = onChange != null
    val lineColor = when {
        isError -> colors.error
        focused -> colors.primary
        else -> colors.outlineVariant
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        label?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) }
        val fieldModifier = Modifier.fillMaxWidth().heightIn(min = 32.dp)
            .drawBehind {
                if (editing) {
                    val stroke = if (focused || isError) 2.dp.toPx() else 1.dp.toPx()
                    drawLine(lineColor, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
                }
            }.padding(vertical = 6.dp)
        if (editing) {
            BasicTextField(
                value = value, onValueChange = onChange, enabled = enabled,
                modifier = fieldModifier.onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = label ?: placeholder },
                textStyle = textStyle.copy(color = textColor), cursorBrush = SolidColor(colors.primary),
                minLines = minLines, maxLines = maxLines, singleLine = maxLines == 1,
                keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) Text(placeholder, style = textStyle, color = colors.onSurfaceVariant,
                            minLines = minLines, maxLines = maxLines)
                        inner()
                    }
                },
            )
        } else {
            SelectionContainer {
                Text(value.ifBlank { placeholder }, fieldModifier, style = textStyle,
                    color = if (value.isBlank()) colors.onSurfaceVariant else textColor,
                    minLines = minLines, maxLines = maxLines)
            }
        }
        if (editing && supportingText != null && (focused || isError)) Text(
            supportingText, style = MaterialTheme.typography.bodySmall,
            color = if (isError) colors.error else colors.onSurfaceVariant,
        )
    }
}

@Composable
internal fun BookDetailSection(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest, contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                action?.invoke()
            }
            content()
        }
    }
}

/** Labels and long values keep their room on narrow screens and with enlarged text. */
@Composable
internal fun BookDetailPair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    val largeText = LocalDensity.current.fontScale > 1.25f
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 280.dp || largeText) Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            first(Modifier.fillMaxWidth())
            second(Modifier.fillMaxWidth())
        } else Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            first(Modifier.weight(1f))
            second(Modifier.weight(1f))
        }
    }
}
