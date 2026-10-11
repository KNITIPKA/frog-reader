package com.example.frogreader.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.data.model.ReadingLogEntry

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReadingLogToolbar(
    entries: List<ReadingLogEntry>, filter: ReadingLogFilter, sort: ReadingLogSort,
    enabled: Boolean, onFilter: (ReadingLogFilter) -> Unit, onSort: () -> Unit,
    visuallyEnabled: Boolean = enabled,
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ReadingLogFilter.entries.forEach { option ->
                val selected = filter == option
                val count = entries.count(option::includes)
                val label = stringResource(readingLogFilterLabel(option))
                Surface(shape = RoundedCornerShape(12.dp),
                    color = if (selected) colors.primaryContainer else colors.surfaceContainerLow,
                    contentColor = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant) {
                    Row(Modifier.selectable(selected, enabled = enabled, role = Role.Tab, onClick = { onFilter(option) })
                        .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                        Text(count.toString(), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onSort, enabled = enabled, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant,
                    disabledContentColor = if (visuallyEnabled) colors.onSurfaceVariant else colors.onSurface.copy(alpha = .38f))) {
                Icon(Icons.AutoMirrored.Rounded.Sort, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(sortLabel(sort)), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Icon(Icons.Rounded.ExpandMore, stringResource(R.string.reading_log_sort), Modifier.size(18.dp))
            }
        }
    }
}

internal fun readingLogFilterLabel(filter: ReadingLogFilter): Int = when (filter) {
    ReadingLogFilter.ALL -> R.string.reading_log_filter_all
    ReadingLogFilter.READING -> R.string.reading_log_filter_reading
    ReadingLogFilter.FINISHED -> R.string.reading_log_finished
    ReadingLogFilter.ABANDONED -> R.string.reading_log_abandoned
}

@Composable
internal fun ReadingLogSortOptions(sort: ReadingLogSort, canReorder: Boolean, select: (ReadingLogSort) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        Text(stringResource(R.string.reading_log_sort), Modifier.padding(bottom = 12.dp),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Column(Modifier.selectableGroup()) {
            ReadingLogSort.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(sort == option, role = Role.RadioButton, onClick = { select(option) })
                    .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected = sort == option, onClick = null)
                    Text(stringResource(sortLabel(option)), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (sort != ReadingLogSort.MANUAL) Text(stringResource(R.string.reading_log_dates_hint),
            Modifier.padding(top = 12.dp, bottom = 16.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        if (canReorder) {
            HorizontalDivider(color = colors.outlineVariant)
            Text(stringResource(if (sort == ReadingLogSort.MANUAL) R.string.reading_log_manual_hint else R.string.reading_log_partial_order_hint),
                Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}
