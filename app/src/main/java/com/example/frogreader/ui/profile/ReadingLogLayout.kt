package com.example.frogreader.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Shared geometry for the reading's view, add and edit cards. */
@Composable
internal fun ReadingLogSection(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurface, tonalElevation = 0.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun ReadingLogFieldPair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    val largeText = LocalDensity.current.fontScale > 1.3f
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 260.dp || largeText) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            first(Modifier.fillMaxWidth())
            second(Modifier.fillMaxWidth())
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            first(Modifier.weight(1f))
            second(Modifier.weight(1f))
        }
    }
}

@Composable
internal fun ReadingLogDetailControl(
    label: String, value: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    icon: ImageVector? = null, click: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val content: @Composable () -> Unit = {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
            icon?.let { Icon(it, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant) }
        }
    }
    if (click != null) Surface(onClick = click, enabled = enabled, modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp), color = colors.surfaceContainer, contentColor = colors.onSurface, content = content)
    else Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = colors.surfaceContainer,
        contentColor = colors.onSurface, content = content)
}
