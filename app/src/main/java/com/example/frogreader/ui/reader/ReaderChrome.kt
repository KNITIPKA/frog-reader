package com.example.frogreader.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.frogreader.R
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Consistent circular controls; the visible face sits inside a 48 dp hit target. */
@Composable
internal fun ReaderChromeButton(
    icon: ImageVector,
    contentDescription: String,
    colors: ReaderColors,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp).semantics { this.selected = selected },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(36.dp).background(
                if (selected) colors.accent else colors.control,
                CircleShape,
            ),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (selected) colors.onAccent else colors.onChrome,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
internal fun ReaderBookHeader(
    title: String,
    author: String?,
    colors: ReaderColors,
    onBack: () -> Unit,
    onSearch: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        ReaderChromeButton(
            Icons.AutoMirrored.Rounded.ArrowBack,
            stringResource(R.string.reader_back), colors, onBack,
        )
        // Exceptionally verbose metadata remains scrollable instead of growing
        // over the entire reading surface. Ordinary titles show in full at rest.
        val metadataScroll = key(title, author) { rememberScrollState() }
        Column(
            modifier = Modifier.weight(1f)
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.28f)
                .verticalScroll(metadataScroll)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            ReaderChromeText(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, lineBreak = LineBreak.Heading,
                ),
                color = colors.onChrome,
                preferredLines = 2,
                largestSp = 15,
                smallestSp = 14,
            )
            if (!author.isNullOrBlank()) {
                ReaderChromeText(
                    text = author,
                    style = MaterialTheme.typography.bodySmall.copy(textAlign = TextAlign.Center),
                    color = colors.onChromeVariant,
                    preferredLines = 1,
                    largestSp = 12,
                    smallestSp = 12,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        ReaderChromeButton(
            Icons.Rounded.Search,
            stringResource(R.string.reader_search), colors, onSearch,
        )
    }
}

/** A persistent cue for both dragging and tapping the reader drawer. */
@Composable
private fun ReaderPanelHandle(
    colors: ReaderColors,
    expanded: Boolean,
    onToggle: () -> Unit,
    drag: Modifier = Modifier,
) {
    val label = stringResource(if (expanded) R.string.reader_collapse_panel else R.string.reader_expand_panel)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .then(drag)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = label,
                onClick = onToggle,
            )
            .semantics { contentDescription = label },
    ) {
        Box(
            modifier = Modifier
                .size(width = 32.dp, height = 4.dp)
                .background(colors.onChromeVariant.copy(alpha = 0.4f), CircleShape),
        )
    }
}

/** Chapter identity and navigation, followed by a stable full-width seek track. */
@Composable
internal fun ReaderChapterFooter(
    chapterLabel: String,
    chapterParents: List<String>,
    bookFraction: Float,
    bookPageCount: Int?,
    chapterStops: List<Float>,
    progressValuesReady: Boolean,
    colors: ReaderColors,
    contentsSelected: Boolean,
    settingsSelected: Boolean,
    onContents: () -> Unit,
    onSettings: () -> Unit,
    onSeekBook: (Float) -> Boolean,
    chapterStartPages: List<Int>? = null,
    chapterStarts: List<Int>? = null,
    itemCount: Int? = null,
    chapterAtFraction: ((Float) -> ReaderChapterPreview)? = null,
    headerDrag: Modifier = Modifier,
    panelExpanded: Boolean = false,
    onTogglePanel: () -> Unit = onContents,
    panelContent: @Composable () -> Unit = {},
) {
    ReaderBookProgress(
        fraction = bookFraction,
        pageCount = bookPageCount,
        chapterStops = chapterStops,
        chapterStartPages = chapterStartPages,
        chapterStarts = chapterStarts,
        itemCount = itemCount,
        chapterAtFraction = chapterAtFraction,
        defaultChapterLabel = chapterLabel,
        defaultChapterParents = chapterParents,
        enabled = progressValuesReady,
        colors = colors,
        onSeek = onSeekBook,
    ) { activeLabel, activeParents ->
        ReaderPanelHandle(colors, panelExpanded, onTogglePanel, headerDrag)
        panelContent()
        val heading = remember(activeLabel, activeParents) {
            resolveReaderBottomBarHeading(activeLabel, activeParents)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ReaderChromeButton(
                Icons.AutoMirrored.Rounded.FormatListBulleted,
                stringResource(R.string.reader_contents), colors, onContents, contentsSelected,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 40.dp)
                    .then(headerDrag)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onContents)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                if (heading.context.isNotEmpty()) {
                    Text(
                        text = heading.context,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onChromeVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        autoSize = TextAutoSize.StepBased(
                            minFontSize = 8.sp,
                            maxFontSize = 11.sp,
                            stepSize = 0.5.sp,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 1.dp)
                            .clearAndSetSemantics {
                                contentDescription = heading.context
                            },
                    )
                }
                Text(
                    text = heading.title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Medium,
                        lineBreak = LineBreak.Heading,
                    ),
                    color = colors.onChrome,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 8.5.sp,
                        maxFontSize = 14.sp,
                        stepSize = 0.5.sp,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clearAndSetSemantics {
                            contentDescription = heading.title
                        },
                )
            }
            ReaderChromeButton(
                Icons.Rounded.Settings,
                stringResource(R.string.reader_settings), colors, onSettings, settingsSelected,
            )
        }
    }
}

/**
 * Try a compact, readable size first, then allow extra lines. No maxLines or
 * ellipsis: long names keep every word, including at the user's font scale.
 */
@Composable
private fun ReaderChromeText(
    text: String,
    style: TextStyle,
    color: Color,
    preferredLines: Int,
    largestSp: Int,
    smallestSp: Int,
    modifier: Modifier = Modifier,
) {
    val normalized = remember(text) { text.trim().replace(Regex("\\s+"), " ") }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = constraints.maxWidth
        val sizedStyle = remember(
            normalized, style, width, density, direction, measurer, preferredLines, largestSp, smallestSp,
        ) {
            (largestSp downTo smallestSp).firstOrNull { size ->
                val measurement = measurer.measure(
                    text = normalized,
                    style = style.copy(fontSize = size.sp, lineHeight = (size + 4).sp),
                    constraints = Constraints(maxWidth = width),
                    maxLines = preferredLines + 1,
                    density = density,
                    layoutDirection = direction,
                )
                measurement.lineCount <= preferredLines && !measurement.hasVisualOverflow
            }.let { size ->
                val chosen = size ?: smallestSp
                style.copy(fontSize = chosen.sp, lineHeight = (chosen + 4).sp)
            }
        }
        Text(
            text = normalized,
            style = sizedStyle,
            color = color,
            textAlign = sizedStyle.textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private data class ReaderProgressDraft(val fraction: Float, val preview: ReaderChapterPreview?)

@Composable
private fun ReaderBookProgress(
    fraction: Float,
    pageCount: Int?,
    chapterStops: List<Float>,
    enabled: Boolean,
    colors: ReaderColors,
    onSeek: (Float) -> Boolean,
    chapterStartPages: List<Int>? = null,
    chapterStarts: List<Int>? = null,
    itemCount: Int? = null,
    chapterAtFraction: ((Float) -> ReaderChapterPreview)? = null,
    defaultChapterLabel: String = "",
    defaultChapterParents: List<String> = emptyList(),
    chapterHeader: @Composable (
        activeLabel: String,
        activeParents: List<String>,
    ) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var draft by remember { mutableStateOf<ReaderProgressDraft?>(null) }
    var adjusting by remember { mutableStateOf(false) }
    var lastScrubbedChapterId by remember { mutableStateOf<Any?>(null) }
    val interaction = remember {
        ReaderSeekInteractionSource(onCancel = {
            adjusting = false
            draft = null
            lastScrubbedChapterId = null
        })
    }
    val shown = draft?.fraction ?: readerProgressPageFraction(fraction, pageCount)
    // Hold a committed position while navigation catches up, without adding
    // history for a cancelled gesture or a seek to the page already displayed.
    LaunchedEffect(fraction, adjusting, draft, pageCount, itemCount) {
        val pending = draft
        if (!adjusting && pending != null) {
            if (!readerProgressSeekReached(fraction, pending.fraction, pageCount ?: itemCount)) delay(600)
            draft = null
        }
    }

    LaunchedEffect(adjusting) {
        if (!adjusting) {
            lastScrubbedChapterId = null
        }
    }

    val pagePreview = remember(shown, draft != null, pageCount, chapterAtFraction) {
        if (pageCount != null && draft == null) chapterAtFraction?.invoke(shown) else null
    }
    val currentPreview = draft?.preview ?: pagePreview
    val activeLabel = currentPreview?.label ?: defaultChapterLabel
    val activeParents = currentPreview?.parents ?: defaultChapterParents

    val pagesLeftInChapter = readerChapterPagesLeft(chapterStartPages, pageCount, shown)
    val remaining = when {
        !enabled -> "…"
        pagesLeftInChapter != null -> {
            if (pagesLeftInChapter <= 0) {
                stringResource(R.string.reader_chapter_last_page)
            } else {
                pluralStringResource(R.plurals.library_pages_left_in_chapter, pagesLeftInChapter, pagesLeftInChapter)
            }
        }
        else -> {
            val percent = readerChapterPercentRemaining(chapterStarts, itemCount, shown)
            if (percent != null) {
                stringResource(R.string.reader_chapter_percent_remaining, percent)
            } else {
                val remainingChapters = readerChaptersRemaining(chapterStops, shown)
                if (remainingChapters <= 0) {
                    stringResource(R.string.reader_last_chapter)
                } else {
                    pluralStringResource(R.plurals.reader_chapters_remaining, remainingChapters, remainingChapters)
                }
            }
        }
    }
    val page = pageCount?.takeIf { it > 0 }?.let {
        ReaderNavigationPolicy.progressTargetIndex(shown, 0, it - 1) + 1
    }
    val position = when {
        !enabled -> "…/…"
        page != null -> stringResource(R.string.reader_book_page_position, page, pageCount)
        else -> stringResource(R.string.reader_book_percent_position, (shown * 100).roundToInt())
    }
    val label = stringResource(R.string.reader_track_book)
    Column {
        chapterHeader(activeLabel, activeParents)
        Spacer(Modifier.height(2.dp))
        ReaderSegmentedProgressSlider(
            fraction = shown,
            chapterStops = chapterStops,
            positionCount = pageCount ?: itemCount,
            adjusting = adjusting,
            onValueChange = { newVal ->
                if (!adjusting || draft?.fraction != newVal) {
                    adjusting = true
                    // Resolve once per new position and reuse it for both the
                    // title and haptics. Repeated moves within a page do no work.
                    val preview = chapterAtFraction?.invoke(newVal)
                    draft = ReaderProgressDraft(newVal, preview)
                    val currentId = preview?.chapterId ?: if (chapterStops.isNotEmpty()) {
                        chapterStops.indexOfLast { it <= newVal }.coerceAtLeast(0)
                    } else null

                    if (currentId != null) {
                        val lastId = lastScrubbedChapterId
                        if (lastId == null) {
                            lastScrubbedChapterId = currentId
                        } else if (lastId != currentId) {
                            lastScrubbedChapterId = currentId
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                    }
                }
            },
            onValueChangeFinished = {
                adjusting = false
                lastScrubbedChapterId = null
                if (draft?.fraction?.let(onSeek) == true) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                } else {
                    draft = null
                }
            },
            enabled = enabled,
            interactionSource = interaction,
            colors = colors,
            label = label,
            progressDescription = position,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .height(26.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .padding(top = 1.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = remaining,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum",
                ),
                color = colors.onChromeVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clearAndSetSemantics { contentDescription = remaining },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = position,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum",
                ),
                color = colors.onChromeVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics { contentDescription = position },
            )
        }
    }
}

/**
 * Material Slider finishes even a cancelled drag. Clear its draft synchronously
 * before that callback; collecting interactions later can race with navigation.
 */
internal class ReaderSeekInteractionSource(private val onCancel: () -> Unit) : MutableInteractionSource {
    private val delegate = MutableInteractionSource()
    override val interactions = delegate.interactions

    override suspend fun emit(interaction: Interaction) {
        if (interaction is DragInteraction.Cancel) onCancel()
        delegate.emit(interaction)
    }

    override fun tryEmit(interaction: Interaction): Boolean {
        if (interaction is DragInteraction.Cancel) onCancel()
        return delegate.tryEmit(interaction)
    }
}
