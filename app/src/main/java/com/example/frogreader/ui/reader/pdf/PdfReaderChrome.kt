package com.example.frogreader.ui.reader.pdf

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Toc
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hintText
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.example.frogreader.R
import com.example.frogreader.data.model.PdfToolbarAppearance
import com.example.frogreader.ui.components.FrogSegmentedControl
import com.example.frogreader.ui.reader.ReaderBookHeader
import com.example.frogreader.ui.reader.ReaderColors
import com.example.frogreader.ui.reader.ReaderPanelHandle
import com.example.frogreader.ui.reader.ReaderPanelHandleHeight
import com.example.frogreader.ui.reader.ReaderPanelMotion
import com.example.frogreader.ui.reader.ReaderPanelNestedScrollConnection
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/** What the drawer pulled out of the PDF bottom bar is showing. */
internal enum class PdfBarPanel { CONTENTS, SAVED, SETTINGS }

/** Whether the bottom search field finds text or jumps to a page number. */
internal enum class PdfSearchMode { TEXT, PAGE }

private val PdfToolbarHeight = 76.dp

internal val WindowInsets.Companion.pdfControlsSafeInsets: WindowInsets
    @Composable get() = navigationBarsIgnoringVisibility.union(displayCutout)

@Composable
private fun PdfChromePanel(
    colors: PdfToolbarColors,
    backdrop: PdfChromeBackdrop?,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.pdfChromeSurface(colors, backdrop, dense),
        color = Color.Transparent,
        contentColor = colors.ink,
        content = content,
    )
}

@Composable
internal fun PdfBookHeader(
    title: String,
    author: String?,
    colors: ReaderColors,
    appearance: PdfToolbarAppearance,
    backdrop: PdfChromeBackdrop?,
    bookmarked: Boolean,
    onBack: () -> Unit,
    onBookmark: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    if (!visible) return
    val toolbarColors = remember(colors, appearance) { PdfToolbarColors(colors, appearance) }
    val safeInsets = WindowInsets.pdfControlsSafeInsets
        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    PdfChromePanel(
        toolbarColors, backdrop,
        modifier.windowInsetsPadding(safeInsets).padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 12.dp)
            .fillMaxWidth(),
    ) {
        ReaderBookHeader(
            title = title,
            author = author,
            colors = toolbarColors.panelColors.copy(control = Color.Transparent),
            onBack = onBack,
            onSearch = onBookmark,
            actionIcon = if (bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
            actionDescription = stringResource(
                if (bookmarked) R.string.pdf_bookmark_page_remove else R.string.pdf_bookmark_page,
            ),
            actionSelected = bookmarked,
        )
    }
}

/**
 * The PDF reader's bottom bar. Contents, Saved and Settings pull out of the bar itself
 * (no modal sheet), with the same half/full stops, drag and predictive Back as the EPUB reader.
 */
@Composable
internal fun PdfBottomBar(
    colors: ReaderColors,
    appearance: PdfToolbarAppearance,
    page: Int,
    pageCount: Int,
    showCollapsedHandle: Boolean,
    expandOnSwipe: Boolean,
    showFinish: Boolean,
    onFinish: () -> Unit,
    onPageIndicator: () -> Unit,
    onSearch: () -> Unit,
    onPanelOpenChange: (Boolean) -> Unit,
    panelFraction: MutableFloatState,
    modifier: Modifier = Modifier,
    /** Changes whenever a caller wants the drawer closed, e.g. after navigating. */
    closeRequest: Int = 0,
    searchVisible: Boolean = false,
    searchContent: @Composable (ReaderColors) -> Unit = {},
    backdrop: PdfChromeBackdrop? = null,
    scrubbing: Boolean = false,
    panelContent: @Composable ColumnScope.(PdfBarPanel, ReaderColors) -> Unit,
) {
    val toolbarColors = remember(colors, appearance) { PdfToolbarColors(colors, appearance) }
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // Keep the same inset owner while entering/leaving search, including the IME's last frames.
    val controlsInsets = WindowInsets.pdfControlsSafeInsets
    val safeInsets = controlsInsets.union(WindowInsets.ime).only(
        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
    ).asPaddingValues()
    val windowHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
    val safeTopPx = controlsInsets.getTop(density)
    val safeBottomPx = controlsInsets.getBottom(density)
    val panelMotion = remember(scope) { ReaderPanelMotion(scope) }
    val panelHeight = panelMotion.height
    val panelOpen = panelMotion.isOpen
    var barChromePx by remember { mutableFloatStateOf(with(density) { PdfToolbarHeight.toPx() }) }
    val fullPx = (
        windowHeightPx - safeTopPx - safeBottomPx -
            with(density) { 16.dp.toPx() } - barChromePx
        ).coerceAtLeast(0f)
    val halfPx = (windowHeightPx * 0.5f).coerceAtMost(fullPx)
    var panel by remember { mutableStateOf(PdfBarPanel.CONTENTS) }

    LaunchedEffect(fullPx) {
        if (panelMotion.targetHeight > fullPx) panelMotion.animateTo(fullPx)
    }
    LaunchedEffect(closeRequest) {
        if (closeRequest != 0) panelMotion.animateTo(0f)
    }
    LaunchedEffect(searchVisible) {
        if (searchVisible) panelMotion.animateTo(0f)
    }
    LaunchedEffect(panelOpen) { onPanelOpenChange(panelOpen) }
    DisposableEffect(Unit) { onDispose { onPanelOpenChange(false); panelFraction.floatValue = 0f } }
    LaunchedEffect(halfPx) {
        snapshotFlow { (panelHeight.value / halfPx.coerceAtLeast(1f)).coerceIn(0f, 1f) }
            .collect { panelFraction.floatValue = it }
    }

    PredictiveBackHandler(enabled = panelOpen) { progress ->
        panelMotion.beginBack()
        try {
            progress.collect { event -> panelMotion.seekBack(event.progress) }
            panelMotion.finishBack(committed = true)
        } catch (cancelled: CancellationException) {
            panelMotion.finishBack(committed = false)
            throw cancelled
        }
    }

    fun settleWithVelocity(velocity: Float) {
        val position = panelMotion.targetHeight
        val anchors = listOf(0f, halfPx, fullPx)
        val target = when {
            velocity < -2500f -> fullPx
            velocity > 2500f -> 0f
            velocity < -400f -> anchors.filter { it > position + 1f }.minOrNull() ?: fullPx
            velocity > 400f -> anchors.filter { it < position - 1f }.maxOrNull() ?: 0f
            else -> anchors.minByOrNull { abs(it - position) } ?: 0f
        }
        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
        panelMotion.animateTo(target)
    }

    val panelDrag = Modifier.draggable(
        orientation = Orientation.Vertical,
        onDragStarted = { panelMotion.startDrag() },
        state = rememberDraggableState { delta -> panelMotion.dragBy(delta, fullPx) },
        onDragStopped = { velocity -> settleWithVelocity(velocity) },
    )
    val panelNestedScroll = remember(panelMotion) {
        ReaderPanelNestedScrollConnection(panelMotion, fullPx, ::settleWithVelocity)
    }
    SideEffect { panelNestedScroll.update(fullPx, ::settleWithVelocity) }

    /** Opens [content], or closes the drawer when its button is tapped again. */
    fun toggle(content: PdfBarPanel) {
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        when {
            !panelMotion.isOpen -> { panel = content; panelMotion.animateTo(halfPx) }
            panel != content -> panel = content
            else -> panelMotion.animateTo(0f)
        }
    }

    val panelDp = with(density) { panelHeight.value.toDp() }
    val expanded = panelOpen && panelMotion.targetHeight >= fullPx - 1f
    val handleFraction = if (showCollapsedHandle) 1f else (panelDp / 48.dp).coerceIn(0f, 1f)
    Column(
        modifier.padding(safeInsets).padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .onSizeChanged { barChromePx = it.height - panelHeight.value }
            .then(if (searchVisible || scrubbing) Modifier else panelDrag),
    ) {
        if (!scrubbing && !searchVisible && (panelDp > 0.dp || handleFraction > 0f)) {
            PdfChromePanel(toolbarColors, backdrop, dense = true) {
                Column {
                    Box(
                        Modifier.fillMaxWidth().height(ReaderPanelHandleHeight * handleFraction)
                            .graphicsLayer { alpha = handleFraction; clip = true },
                    ) {
                        ReaderPanelHandle(
                            colors = toolbarColors.panelColors, expanded = expanded, open = panelOpen,
                            onToggleExpand = {
                                if (!panelOpen) toggle(panel) else {
                                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    panelMotion.animateTo(
                                        if (!expanded) fullPx else if (halfPx < fullPx - 1f) halfPx else 0f,
                                    )
                                }
                            },
                        )
                    }
                    if (panelDp > 0.dp) {
                        Column(
                            Modifier.fillMaxWidth().height(panelDp)
                                .then(if (expandOnSwipe) Modifier.nestedScroll(panelNestedScroll) else Modifier),
                        ) { panelContent(panel, toolbarColors.panelColors) }
                    }
                }
            }
            Spacer(Modifier.height(6.dp * handleFraction))
        }
        AnimatedContent(
            targetState = searchVisible && !scrubbing,
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.BottomCenter,
            transitionSpec = {
                (fadeIn(tween(160, delayMillis = 30)) togetherWith fadeOut(tween(100)))
                    .using(SizeTransform(clip = false) { _, _ -> tween(220) })
            },
            label = "pdfBottomBarMode",
        ) { searching ->
            if (searching) {
                PdfChromePanel(
                    toolbarColors, backdrop, Modifier.fillMaxWidth().heightIn(min = pdfFooterHeight()), dense = true,
                ) {
                    Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) { searchContent(toolbarColors.panelColors) }
                }
            } else {
                Column {
                    if (showFinish && !panelOpen && !scrubbing) TextButton(
                        onClick = onFinish, modifier = Modifier.align(Alignment.CenterHorizontally),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = colors.accent, containerColor = colors.chrome,
                        ),
                    ) { Text(stringResource(R.string.pdf_finish_reading)) }
                    val surface = if (scrubbing) Modifier
                        else Modifier.pdfChromeSurface(toolbarColors, backdrop)
                    Row(
                        Modifier.fillMaxWidth().height(PdfToolbarHeight).then(surface),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val action = Modifier.weight(1f)
                        if (scrubbing) {
                            Spacer(Modifier.weight(2f))
                        } else {
                            PdfBarItem(
                                Icons.AutoMirrored.Rounded.Toc, stringResource(R.string.pdf_panel_contents), toolbarColors,
                                panelOpen && panel == PdfBarPanel.CONTENTS, { toggle(PdfBarPanel.CONTENTS) }, action,
                            )
                            PdfBarItem(
                                Icons.Rounded.BookmarkBorder, stringResource(R.string.pdf_panel_saved), toolbarColors,
                                panelOpen && panel == PdfBarPanel.SAVED, { toggle(PdfBarPanel.SAVED) }, action,
                            )
                        }
                        PdfPageIndicator(page, pageCount, toolbarColors, {
                            if (panelOpen) panelMotion.animateTo(0f)
                            onPageIndicator()
                        }, action, scrubbing)
                        if (scrubbing) {
                            Spacer(Modifier.weight(2f))
                        } else {
                            PdfBarItem(
                                Icons.Rounded.Search, stringResource(R.string.pdf_panel_search), toolbarColors, false, {
                                    if (panelOpen) panelMotion.animateTo(0f)
                                    onSearch()
                                }, action,
                            )
                            PdfBarItem(
                                Icons.Rounded.Settings, stringResource(R.string.pdf_panel_settings), toolbarColors,
                                panelOpen && panel == PdfBarPanel.SETTINGS, { toggle(PdfBarPanel.SETTINGS) }, action,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfBarItem(
    icon: ImageVector,
    label: String,
    colors: PdfToolbarColors,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val ink = if (selected) colors.selectedInk else colors.ink
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = ink)
        Text(
            label,
            color = ink,
            style = MaterialTheme.typography.labelMedium,
            lineHeight = 1.12.em,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 12.sp, stepSize = 0.5.sp),
        )
    }
}

/** Current page over the total; opens the bottom field in page mode. */
@Composable
private fun PdfPageIndicator(
    page: Int,
    pageCount: Int,
    colors: PdfToolbarColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    scrubbing: Boolean = false,
) {
    val description = stringResource(if (scrubbing) R.string.pdf_scrub_page_count else R.string.pdf_page_indicator, page + 1, pageCount)
    Box(modifier, contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            enabled = !scrubbing,
            color = colors.counterTint,
            contentColor = colors.counterInk,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.size(52.dp, 64.dp)
                .semantics(mergeDescendants = true) { contentDescription = description },
        ) {
            Column(
                Modifier.padding(horizontal = 4.dp, vertical = 4.dp).clearAndSetSemantics {},
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "${page + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    lineHeight = 1.08.em, color = colors.counterInk, maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 20.sp, stepSize = 0.5.sp),
                    modifier = Modifier.weight(1f, fill = false),
                )
                HorizontalDivider(Modifier.padding(vertical = 3.dp).width(26.dp), thickness = 1.dp,
                    color = colors.counterInk.copy(alpha = 0.55f))
                Text(
                    "$pageCount", style = MaterialTheme.typography.labelMedium,
                    lineHeight = 1.08.em, color = colors.counterInk, maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 14.sp, stepSize = 0.5.sp),
                    modifier = Modifier.weight(0.8f, fill = false),
                )
            }
        }
    }
}

/** Search replaces the actions inside PdfBottomBar; it owns neither a surface nor window insets. */
@Composable
internal fun PdfSearchContent(
    colors: ReaderColors,
    mode: PdfSearchMode,
    onModeChange: (PdfSearchMode) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    textStatus: String?,
    hasMatches: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    currentPage: Int,
    pageCount: Int,
    onGoToPage: (Int) -> Unit,
    onClose: () -> Unit,
    active: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }
    var textDraft by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    var pageDraft by remember {
        val initial = (currentPage + 1).toString()
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    var editorMode by remember { mutableStateOf(mode) }
    val initialDraft = if (mode == PdfSearchMode.TEXT) textDraft else pageDraft
    val editor = rememberTextFieldState(initialDraft.text, initialDraft.selection)
    val currentQuery by rememberUpdatedState(query)
    val currentOnQueryChange by rememberUpdatedState(onQueryChange)
    val currentActive by rememberUpdatedState(active)

    fun switchEditorMode(next: PdfSearchMode) {
        if (next == editorMode) return
        val draft = TextFieldValue(editor.text.toString(), editor.selection)
        if (editorMode == PdfSearchMode.TEXT) {
            textDraft = draft
            if (draft.text != currentQuery) currentOnQueryChange(draft.text)
        } else pageDraft = draft
        // Update both synchronously: page digits must never be published as a text query.
        editorMode = next
        editor.replacePdfDraft(if (next == PdfSearchMode.TEXT) textDraft else pageDraft)
    }

    LaunchedEffect(active) {
        if (active) {
            val initial = (currentPage + 1).toString()
            pageDraft = TextFieldValue(initial, TextRange(0, initial.length))
            textDraft = TextFieldValue(query, TextRange(query.length))
            editorMode = mode
            editor.replacePdfDraft(if (mode == PdfSearchMode.TEXT) textDraft else pageDraft)
            focusRequester.requestFocus()
        }
    }
    LaunchedEffect(mode) { switchEditorMode(mode) }
    LaunchedEffect(query) {
        if (query != textDraft.text) {
            textDraft = TextFieldValue(query, TextRange(query.length))
            if (editorMode == PdfSearchMode.TEXT) editor.replacePdfDraft(textDraft)
        }
    }
    LaunchedEffect(editor) {
        snapshotFlow { Triple(editorMode, editor.text.toString(), editor.selection) }
            .collect { (editingMode, text, selection) ->
                if (currentActive) {
                    val draft = TextFieldValue(text, selection)
                    if (editingMode == PdfSearchMode.TEXT) {
                        textDraft = draft
                        if (text != currentQuery) currentOnQueryChange(text)
                    } else pageDraft = draft
                }
            }
    }
    val inputFilter = remember {
        InputTransformation {
            if (editorMode == PdfSearchMode.PAGE &&
                (length > 9 || asCharSequence().any { !it.isDigit() })) revertAllChanges()
        }
    }
    val pageInput = if (editorMode == PdfSearchMode.PAGE) editor.text.toString() else pageDraft.text
    val destination = pdfPageIndexFromInput(pageInput, pageCount)
    val pageInvalid = pageInput.isNotEmpty() && destination == null
    fun go() {
        destination?.let { focus.clearFocus(); keyboard?.hide(); onGoToPage(it) }
    }
    val placeholder = stringResource(
        if (mode == PdfSearchMode.TEXT) R.string.pdf_search_placeholder else R.string.pdf_panel_go_to_page,
    )
    val fieldHint = stringResource(
        if (mode == PdfSearchMode.TEXT) R.string.pdf_search_placeholder else R.string.pdf_search_mode_page,
    )
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(pdfSearchFieldHeight()), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                val style = MaterialTheme.typography.bodyLarge.copy(color = colors.onChrome, fontSize = 18.sp)
                BasicTextField(
                    state = editor,
                    inputTransformation = inputFilter,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    enabled = active,
                    textStyle = style,
                    cursorBrush = SolidColor(colors.accent),
                    // A keyboard-type/action change would start a new IME session. Keep one
                    // text keyboard; Page restricts input through the transformation above.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                    onKeyboardAction = KeyboardActionHandler {
                        if (editorMode == PdfSearchMode.PAGE) go() else { focus.clearFocus(); keyboard?.hide() }
                    },
                    decorator = TextFieldDecorator { innerEditor ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                            if (editor.text.isEmpty()) {
                                Text(placeholder, color = colors.onChromeVariant, style = style, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics {})
                            }
                            innerEditor()
                        }
                    },
                    modifier = Modifier.fillMaxSize().focusRequester(focusRequester)
                        .onFocusChanged { fieldFocused = it.isFocused }
                        .semantics { hintText = fieldHint },
                )
            }
            IconButton(onClick = onClose, enabled = active, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.Close, stringResource(R.string.pdf_search_close), tint = colors.onChrome)
            }
        }
        Row(Modifier.fillMaxWidth().height(pdfSearchControlsHeight()), verticalAlignment = Alignment.CenterVertically) {
            PdfSearchModeSwitch(mode, colors, {
                if (active) {
                    switchEditorMode(it)
                    onModeChange(it)
                    if (!fieldFocused) focusRequester.requestFocus()
                }
            }, Modifier.width(120.dp))
            Spacer(Modifier.width(6.dp))
            val status = when {
                mode == PdfSearchMode.TEXT && query.isNotBlank() -> textStatus.orEmpty()
                mode == PdfSearchMode.PAGE && pageInvalid -> stringResource(R.string.pdf_panel_page_range, pageCount)
                mode == PdfSearchMode.PAGE -> stringResource(R.string.pdf_panel_page_of, currentPage + 1, pageCount)
                else -> ""
            }
            Text(
                status, Modifier.weight(1f).padding(horizontal = 4.dp),
                color = if (mode == PdfSearchMode.PAGE && pageInvalid) MaterialTheme.colorScheme.error else colors.onChromeVariant,
                style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (mode == PdfSearchMode.TEXT) {
                IconButton(onClick = onPrevious, enabled = active && hasMatches, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.pdf_previous_result),
                        tint = if (hasMatches) colors.onChrome else colors.onChromeVariant)
                }
                IconButton(onClick = onNext, enabled = active && hasMatches, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.pdf_next_result),
                        tint = if (hasMatches) colors.onChrome else colors.onChromeVariant)
                }
            } else {
                IconButton(onClick = { go() }, enabled = active && destination != null, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.pdf_panel_go),
                        tint = if (destination != null) colors.onChrome else colors.onChromeVariant)
                }
            }
        }
    }
}

/** Swap only the editor's buffer, without tearing down its input connection or composing session. */
private fun TextFieldState.replacePdfDraft(draft: TextFieldValue) {
    if (composition != null) {
        // A selection-only edit commits composition using updateSelection. Replacing composing
        // text directly would request restartInput and can make some IMEs hide their window.
        edit { selection = if (selection != TextRange.Zero) TextRange.Zero else TextRange(length) }
    }
    edit {
        replace(0, length, draft.text)
        selection = draft.selection
    }
}

// Both search modes keep the same height, including at large font scales.
@Composable
private fun pdfSearchFieldHeight(): Dp = maxOf(48.dp, with(LocalDensity.current) {
    MaterialTheme.typography.bodyLarge.lineHeight.toDp()
} + 16.dp)

@Composable
private fun pdfSearchControlsHeight(): Dp = maxOf(48.dp, with(LocalDensity.current) {
    MaterialTheme.typography.labelLarge.lineHeight.toDp()
} + 12.dp)

@Composable
private fun pdfFooterHeight(): Dp = pdfSearchFieldHeight() + pdfSearchControlsHeight()

@Composable
private fun PdfSearchModeSwitch(mode: PdfSearchMode, colors: ReaderColors, onModeChange: (PdfSearchMode) -> Unit, modifier: Modifier) {
    val labels = listOf(R.string.pdf_search_mode_text, R.string.pdf_search_mode_page)
    FrogSegmentedControl(
        count = 2, selectedIndex = mode.ordinal, onSelect = { onModeChange(PdfSearchMode.entries[it]) },
        containerColor = colors.control, indicatorColor = colors.selectedControl,
        contentColor = colors.onChromeVariant, selectedContentColor = colors.onSelectedControl,
        modifier = modifier, containerRadius = 16.dp, indicatorRadius = 12.dp, minItemHeight = 40.dp,
    ) { index, selected, color ->
        Text(stringResource(labels[index]), color = color, style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
    }
}
