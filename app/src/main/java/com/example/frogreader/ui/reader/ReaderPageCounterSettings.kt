package com.example.frogreader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.frogreader.R
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.ReadingMode

/** Only complete book pagination is supplied to the settings previews. */
@Immutable
data class PageCounterInfo(
    val currentPage: Int,
    val totalPages: Int,
    val pagesLeftInChapter: Int,
)

/** Use the same page ownership as the printed footer, including chapters sharing a page. */
internal fun pageCounterInfoForPage(
    pages: List<BookPage>,
    pageIndex: Int,
    chapterAt: (Int) -> Int,
): PageCounterInfo? {
    val page = pages.getOrNull(pageIndex) ?: return null
    val chapter = chapterAt(page.firstItemIndex)
    var chapterLastPage = pageIndex
    while (chapterLastPage < pages.lastIndex &&
        chapterAt(pages[chapterLastPage + 1].firstItemIndex) == chapter
    ) {
        chapterLastPage++
    }
    return PageCounterInfo(pageIndex + 1, pages.size, chapterLastPage - pageIndex)
}

@Composable
internal fun ReaderPageCounterSettings(
    settings: ReaderSettings,
    colors: ReaderColors,
    pageCounterInfo: PageCounterInfo?,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
) {
    val paged = settings.readingMode == ReadingMode.PAGES
    val haptics = LocalHapticFeedback.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.control),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .toggleable(
                    value = settings.showPageCounter,
                    enabled = paged,
                    role = Role.Switch,
                    onValueChange = { enabled ->
                        haptics.performHapticFeedback(
                            if (enabled) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
                        )
                        onUpdate { it.copy(showPageCounter = enabled) }
                    },
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.Tag, null, tint = colors.onChrome, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.reader_show_page_counter),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onChrome,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(if (paged) R.string.reader_counter_subtitle else R.string.reader_counter_paged_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onChromeVariant,
                )
            }
            Switch(
                checked = settings.showPageCounter,
                enabled = paged,
                onCheckedChange = null,
                modifier = Modifier.clearAndSetSemantics {},
                colors = SwitchDefaults.colors(
                    checkedTrackColor = colors.accent,
                    checkedThumbColor = colors.onAccent,
                    uncheckedTrackColor = colors.control,
                    uncheckedThumbColor = colors.onChromeVariant,
                    uncheckedBorderColor = colors.onChromeVariant,
                ),
            )
        }
        AnimatedVisibility(paged && settings.showPageCounter) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Text(
                    stringResource(R.string.reader_counter_style),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onChromeVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 10.dp),
                )
                PageCounterStyleCards(settings, colors, pageCounterInfo) { index ->
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onUpdate { it.copy(showChapterPagesLeft = index == 2, showTotalPageCount = index == 1) }
                }
                if (pageCounterInfo == null) {
                    Text(
                        stringResource(R.string.reader_counter_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onChromeVariant,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PageCounterStyleCards(
    settings: ReaderSettings,
    colors: ReaderColors,
    info: PageCounterInfo?,
    onSelect: (Int) -> Unit,
) {
    val labels = listOf(
        stringResource(R.string.reader_counter_page_only),
        stringResource(R.string.reader_counter_page_total),
        stringResource(R.string.reader_counter_chapter),
    )
    val placeholder = stringResource(R.string.reader_counter_unavailable)
    val examples = listOf(
        info?.let { stringResource(R.string.reader_page_number, it.currentPage) } ?: placeholder,
        info?.let { stringResource(R.string.reader_page_number_total, it.currentPage, it.totalPages) }
            ?: stringResource(R.string.reader_counter_total_unavailable),
        info?.let {
            if (it.pagesLeftInChapter == 0) stringResource(R.string.reader_chapter_last_page)
            else pluralStringResource(R.plurals.reader_chapter_pages_left, it.pagesLeftInChapter, it.pagesLeftInChapter)
        } ?: placeholder,
    )
    val selectedIndex = when {
        settings.showChapterPagesLeft -> 2
        settings.showTotalPageCount -> 1
        else -> 0
    }
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val measurer = rememberTextMeasurer()
    // Keep complete numeric counters on one line, including five-digit books.
    // If the smallest readable preview text needs more room, use fewer columns.
    val counterWidth = with(density) {
        measurer.measure(
            examples[1],
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            softWrap = false,
        ).size.width.toDp()
    }
    BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
        val minimumTileWidth = maxOf(100.dp * fontScale, counterWidth + 32.dp)
        val columns = ((maxWidth + 8.dp) / (minimumTileWidth + 8.dp)).toInt().coerceIn(1, 3)
        val tileWidth = if (columns == 1) minimumTileWidth.coerceAtMost(maxWidth) else {
            ((maxWidth - 8.dp * (columns - 1)) / columns).coerceAtMost(180.dp * fontScale)
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            labels.indices.chunked(columns).forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    row.forEach { index ->
                        PageCounterStyleCard(
                            label = labels[index],
                            example = examples[index],
                            numeric = index < 2,
                            selected = selectedIndex == index,
                            colors = colors,
                            modifier = Modifier.width(tileWidth),
                            onClick = { onSelect(index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageCounterStyleCard(
    label: String,
    example: String,
    numeric: Boolean,
    selected: Boolean,
    colors: ReaderColors,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val fill by animateColorAsState(
        if (selected) lerp(colors.control, colors.accent, 0.09f) else colors.control,
        label = "Counter style fill",
    )
    val border by animateColorAsState(
        if (selected) colors.accent else colors.track,
        label = "Counter style border",
    )
    val cardModifier = modifier.clip(shape)
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .semantics { contentDescription = "$label, $example" }
    Column(
        cardModifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(shape).background(fill)
                .border(if (selected) 2.dp else 1.dp, border, shape).padding(8.dp),
        ) {
            CounterMiniPage(example, numeric, colors, Modifier.fillMaxSize())
        }
        Box(
            Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 2.dp)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = colors.onChrome,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

/** Paper and counter ink match the reading page; selection follows the control palette. */
@Composable
private fun CounterMiniPage(example: String, numeric: Boolean, colors: ReaderColors, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(colors.background)
            .clearAndSetSemantics {},
    ) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            repeat(5) { line ->
                drawRoundRect(
                    color = colors.text.copy(alpha = 0.12f),
                    topLeft = Offset(0f, line * size.height * 0.085f),
                    size = Size(size.width * if (line == 4) 0.68f else 1f, 2.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            }
        }
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.65f)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Text(
                example,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = colors.pageCounterText,
                maxLines = if (numeric) 1 else 3,
                softWrap = !numeric,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = 10.sp,
                    maxFontSize = 12.sp,
                    stepSize = 0.25.sp,
                ),
            )
        }
    }
}
