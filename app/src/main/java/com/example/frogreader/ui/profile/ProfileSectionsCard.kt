package com.example.frogreader.ui.profile

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.PlaylistAddCheck
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.frogreader.R

private val CardVerticalPadding = 2.dp
private val DividerThickness = 1.dp
private val RowHorizontalPadding = 14.dp
private val RowGap = 10.dp

@Composable
internal fun ProfileSectionsCard(
    quotes: Int,
    bookmarks: Int,
    toRead: Int,
    finished: Int,
    onOpenQuotesAndBookmarks: () -> Unit,
    onOpenReadingQueue: () -> Unit,
    onOpenReadingLog: () -> Unit,
    compact: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 2.dp,
    ) {
        // The dashboard may hand this card more height than it needs so that it
        // reaches the dock; split it evenly so the three rows become larger targets.
        BoxWithConstraints {
            val baseRowHeight = if (compact) 60.dp else 68.dp
            val sharedHeight = (minHeight - CardVerticalPadding * 2 - DividerThickness * 2) / 3
            val rowHeight = maxOf(baseRowHeight, sharedHeight)
            // Animated, so opening the goal ruler (which takes height from these
            // rows) resizes the icons smoothly instead of snapping between sizes.
            val tileSize by animateDpAsState(
                targetValue = when {
                    rowHeight >= 84.dp -> 44.dp
                    compact -> 34.dp
                    else -> 38.dp
                },
                label = "sectionTileSize",
            )
            Column(Modifier.padding(vertical = CardVerticalPadding)) {
                ProfileSectionRow(
                    icon = Icons.Rounded.FormatQuote,
                    title = stringResource(R.string.profile_quotes_bookmarks),
                    subtitle = pluralStringResource(R.plurals.profile_bookmarks_count, bookmarks, bookmarks),
                    count = quotes,
                    countLabel = stringResource(R.string.profile_quotes_count_label),
                    tileSize = tileSize,
                    minHeight = rowHeight,
                    onClick = onOpenQuotesAndBookmarks,
                )
                SectionDivider(tileSize)
                ProfileSectionRow(
                    icon = Icons.AutoMirrored.Rounded.PlaylistAddCheck,
                    title = stringResource(R.string.profile_reading_queue),
                    subtitle = stringResource(R.string.profile_queue_hint),
                    count = toRead,
                    countLabel = stringResource(R.string.profile_to_read_count_label),
                    tileSize = tileSize,
                    minHeight = rowHeight,
                    onClick = onOpenReadingQueue,
                )
                SectionDivider(tileSize)
                ProfileSectionRow(
                    icon = Icons.Rounded.History,
                    title = stringResource(R.string.profile_reading_log),
                    subtitle = stringResource(R.string.profile_log_hint),
                    count = finished,
                    countLabel = stringResource(R.string.profile_read_count_label),
                    tileSize = tileSize,
                    minHeight = rowHeight,
                    onClick = onOpenReadingLog,
                )
            }
        }
    }
}

@Composable
private fun SectionDivider(tileSize: Dp) {
    HorizontalDivider(
        // Aligned with the row text, past the icon tile.
        modifier = Modifier.padding(start = RowHorizontalPadding + tileSize + RowGap, end = RowHorizontalPadding),
        thickness = DividerThickness,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

@Composable
private fun ProfileSectionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    count: Int,
    countLabel: String,
    onClick: () -> Unit,
    tileSize: Dp,
    minHeight: Dp,
) {
    val scheme = MaterialTheme.colorScheme
    val largeText = LocalDensity.current.fontScale > 1.3f
    Row(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = minHeight).padding(horizontal = RowHorizontalPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RowGap),
    ) {
        Box(
            Modifier.size(tileSize).background(scheme.secondaryContainer, RoundedCornerShape(tileSize * 0.34f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(tileSize * 0.55f), tint = scheme.onSecondaryContainer)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            if (largeText) {
                Text("$count $countLabel", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
            }
        }
        if (!largeText) {
            Column(Modifier.width(78.dp), horizontalAlignment = Alignment.End) {
                Text(count.toString(), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, color = scheme.primary)
                Text(countLabel, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = scheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1,
                    autoSize = TextAutoSize.StepBased(8.sp, 10.sp, 0.25.sp))
            }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(18.dp),
            tint = scheme.onSurfaceVariant.copy(alpha = 0.6f))
    }
}
