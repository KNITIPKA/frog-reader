package com.example.frogreader.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frogreader.R
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.DocumentLocation
import com.example.frogreader.data.model.ProfileSearchCategory
import com.example.frogreader.data.model.ReadingLogStatus
import com.example.frogreader.data.search.AchievementSearchHit
import com.example.frogreader.data.search.BookmarkSearchHit
import com.example.frogreader.data.search.MatchSpan
import com.example.frogreader.data.search.QuoteSearchHit
import com.example.frogreader.data.search.ReadingLogSearchHit
import com.example.frogreader.data.search.ReadingQueueSearchHit
import com.example.frogreader.data.search.SearchMatchHighlighter
import com.example.frogreader.ui.theme.LocalFrogColors
import com.example.frogreader.ui.theme.ProfileSearchCategoryPalette
import com.example.frogreader.ui.theme.ProfileSearchColors

private const val COLLAPSED_PREVIEW_LIMIT = 2

@Composable
fun ProfileSearchScreen(
    onBack: () -> Unit,
    onOpenQuote: (QuoteSearchHit) -> Unit,
    onOpenBookmark: (BookmarkSearchHit) -> Unit,
    onOpenReadingQueue: (ReadingQueueSearchHit) -> Unit,
    onOpenReadingLog: (ReadingLogSearchHit) -> Unit,
    onOpenAchievement: (AchievementSearchHit) -> Unit,
    viewModel: ProfileSearchViewModel = viewModel(factory = ProfileSearchViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler(enabled = state.isCategoryOrderExpanded) {
        viewModel.dismissCategoryOrderCard()
    }

    ProfileSearchContent(
        state = state,
        autoFocusSearch = true,
        onBack = onBack,
        onQueryChange = viewModel::setQuery,
        onClearQuery = viewModel::clearQuery,
        onToggleCategoryOrder = viewModel::toggleCategoryOrderCard,
        onDismissCategoryOrder = viewModel::dismissCategoryOrderCard,
        onToggleCategoryVisibility = viewModel::toggleCategoryVisibility,
        onMoveCategory = { from, to -> viewModel.moveCategory(from, to) },
        onToggleCategoryExpanded = viewModel::toggleCategoryExpanded,
        onOpenQuote = onOpenQuote,
        onOpenBookmark = onOpenBookmark,
        onOpenReadingQueue = onOpenReadingQueue,
        onOpenReadingLog = onOpenReadingLog,
        onOpenAchievement = onOpenAchievement,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfileSearchContent(
    state: ProfileSearchUiState,
    autoFocusSearch: Boolean = false,
    onBack: () -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onClearQuery: () -> Unit = {},
    onToggleCategoryOrder: () -> Unit = {},
    onDismissCategoryOrder: () -> Unit = {},
    onToggleCategoryVisibility: (ProfileSearchCategory) -> Unit = {},
    onMoveCategory: (Int, Int) -> Unit = { _, _ -> },
    onToggleCategoryExpanded: (ProfileSearchCategory) -> Unit = {},
    onOpenQuote: (QuoteSearchHit) -> Unit = {},
    onOpenBookmark: (BookmarkSearchHit) -> Unit = {},
    onOpenReadingQueue: (ReadingQueueSearchHit) -> Unit = {},
    onOpenReadingLog: (ReadingLogSearchHit) -> Unit = {},
    onOpenAchievement: (AchievementSearchHit) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val searchColors = LocalFrogColors.current.profileSearch
    val topSystemPadding = WindowInsets.systemBarsIgnoringVisibility
        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        .asPaddingValues()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.surface)
            .padding(topSystemPadding),
    ) {
        ProfileSearchTopBar(
            query = state.query,
            searchColors = searchColors,
            autoFocus = autoFocusSearch,
            onBack = onBack,
            onQueryChange = onQueryChange,
            onClear = onClearQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
        )

        val hasQuery = state.query.isNotBlank()
        val visibleNonEmptyCategories = remember(state.categoryOrder, state.hiddenCategories, state.results) {
            state.categoryOrder.filter { category ->
                category !in state.hiddenCategories && state.results.countFor(category) > 0
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 6.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "toolbar") {
                ProfileSearchToolbarRow(
                    hasQuery = hasQuery,
                    visibleCount = state.visibleResultCount,
                    isCategoryOrderExpanded = state.isCategoryOrderExpanded,
                    onToggleCategoryOrder = onToggleCategoryOrder,
                )
            }

            item(key = "category_order_panel") {
                AnimatedVisibility(
                    visible = state.isCategoryOrderExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    CategoryOrderCard(
                        categoryOrder = state.categoryOrder,
                        hiddenCategories = state.hiddenCategories,
                        searchColors = searchColors,
                        onToggleVisibility = onToggleCategoryVisibility,
                        onMoveCategory = onMoveCategory,
                        onDone = onDismissCategoryOrder,
                    )
                }
            }

            when {
                !hasQuery -> {
                    item(key = "empty_prompt") {
                        ProfileSearchStatusPlaceholder(
                            icon = Icons.Rounded.Search,
                            title = stringResource(R.string.profile_search_empty_prompt_title),
                            subtitle = stringResource(R.string.profile_search_empty_prompt_subtitle),
                        )
                    }
                }
                state.allCategoriesHidden -> {
                    item(key = "all_hidden") {
                        ProfileSearchStatusPlaceholder(
                            icon = Icons.Outlined.VisibilityOff,
                            title = stringResource(R.string.profile_search_all_hidden_title),
                            subtitle = stringResource(R.string.profile_search_all_hidden_subtitle),
                        )
                    }
                }
                visibleNonEmptyCategories.isEmpty() -> {
                    item(key = "no_results") {
                        ProfileSearchStatusPlaceholder(
                            icon = Icons.Rounded.Search,
                            title = stringResource(R.string.profile_search_no_results_title, state.query.trim()),
                            subtitle = stringResource(R.string.profile_search_no_results_subtitle),
                        )
                    }
                }
                else -> {
                    visibleNonEmptyCategories.forEach { category ->
                        item(key = "section_${category.name}") {
                            ProfileSearchCategorySection(
                                category = category,
                                query = state.query,
                                results = state.results,
                                expanded = category in state.expandedCategories,
                                palette = searchColors.paletteFor(category),
                                onToggleExpand = { onToggleCategoryExpanded(category) },
                                onOpenQuote = onOpenQuote,
                                onOpenBookmark = onOpenBookmark,
                                onOpenReadingQueue = onOpenReadingQueue,
                                onOpenReadingLog = onOpenReadingLog,
                                onOpenAchievement = onOpenAchievement,
                            )
                        }
                    }
                }
            }

            item(key = "nav_spacer") {
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun ProfileSearchTopBar(
    query: String,
    searchColors: ProfileSearchColors,
    autoFocus: Boolean,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    if (autoFocus) {
        LaunchedEffect(Unit) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    Surface(
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(26.dp),
        color = searchColors.searchBarContainer,
        border = BorderStroke(1.dp, searchColors.searchBarBorder),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(42.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.profile_search_back),
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(21.dp),
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.profile_search_placeholder),
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = scheme.onSurface,
                        textDirection = TextDirection.Content,
                    ),
                    cursorBrush = SolidColor(scheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            focusManager.clearFocus()
                            keyboard?.hide()
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            }

            if (query.isNotEmpty()) {
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.size(42.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.profile_search_clear),
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Spacer(Modifier.width(10.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileSearchToolbarRow(
    hasQuery: Boolean,
    visibleCount: Int,
    isCategoryOrderExpanded: Boolean,
    onToggleCategoryOrder: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasQuery) {
            Text(
                text = pluralStringResource(
                    R.plurals.profile_search_results_count,
                    visibleCount,
                    visibleCount,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        } else {
            Spacer(Modifier.width(1.dp))
        }

        Surface(
            onClick = onToggleCategoryOrder,
            shape = CircleShape,
            color = if (isCategoryOrderExpanded) scheme.secondaryContainer else Color.Transparent,
            contentColor = if (isCategoryOrderExpanded) scheme.onSecondaryContainer else scheme.onSurface,
            border = BorderStroke(
                width = 1.dp,
                color = if (isCategoryOrderExpanded) scheme.outline else scheme.outlineVariant,
            ),
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 38.dp)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.SwapVert,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    text = stringResource(R.string.profile_search_category_order_button),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun CategoryOrderCard(
    categoryOrder: List<ProfileSearchCategory>,
    hiddenCategories: Set<ProfileSearchCategory>,
    searchColors: ProfileSearchColors,
    onToggleVisibility: (ProfileSearchCategory) -> Unit,
    onMoveCategory: (Int, Int) -> Unit,
    onDone: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val currentOrder by rememberUpdatedState(categoryOrder)
    val currentOnMove by rememberUpdatedState(onMoveCategory)

    var draggedCategory by remember { mutableStateOf<ProfileSearchCategory?>(null) }
    var dragAccumulatedY by remember { mutableFloatStateOf(0f) }
    var rowStepPx by remember { mutableFloatStateOf(132f) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = searchColors.categoryOrderContainer,
        border = BorderStroke(1.dp, searchColors.categoryOrderBorder),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.profile_search_category_order_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.profile_search_category_order_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                categoryOrder.forEachIndexed { index, category ->
                    key(category) {
                        val isVisible = category !in hiddenCategories
                        val isDragged = draggedCategory == category
                        val label = categoryDisplayName(category)
                        val moveUpLabel = stringResource(R.string.profile_search_move_up, label)
                        val moveDownLabel = stringResource(R.string.profile_search_move_down, label)

                        val accessibilityMoves = buildList {
                            if (index > 0) {
                                add(
                                    CustomAccessibilityAction(moveUpLabel) {
                                        onMoveCategory(index, index - 1)
                                        true
                                    },
                                )
                            }
                            if (index < categoryOrder.lastIndex) {
                                add(
                                    CustomAccessibilityAction(moveDownLabel) {
                                        onMoveCategory(index, index + 1)
                                        true
                                    },
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (isDragged) 2f else 0f)
                                .onSizeChanged { size ->
                                    if (size.height > 0) rowStepPx = size.height.toFloat()
                                }
                                .graphicsLayer {
                                    translationY = if (isDragged) dragAccumulatedY else 0f
                                    val scale = if (isDragged) 1.02f else 1f
                                    scaleX = scale
                                    scaleY = scale
                                }
                                .semantics {
                                    if (accessibilityMoves.isNotEmpty()) {
                                        customActions = accessibilityMoves
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = categoryHeaderIcon(category),
                                contentDescription = null,
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )

                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = if (isVisible) scheme.onSurface else scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )

                            IconButton(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onToggleVisibility(category)
                                },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    imageVector = if (isVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                                    contentDescription = stringResource(
                                        if (isVisible) R.string.profile_search_hide_category else R.string.profile_search_show_category,
                                        label,
                                    ),
                                    tint = if (isVisible) scheme.primary else scheme.onSurfaceVariant,
                                    modifier = Modifier.size(21.dp),
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(searchColors.dragHandleContainer)
                                    .pointerInput(category) {
                                        detectVerticalDragGestures(
                                            onDragStart = {
                                                draggedCategory = category
                                                dragAccumulatedY = 0f
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                            onDragEnd = {
                                                draggedCategory = null
                                                dragAccumulatedY = 0f
                                            },
                                            onDragCancel = {
                                                draggedCategory = null
                                                dragAccumulatedY = 0f
                                            },
                                            onVerticalDrag = { change, dragAmount ->
                                                change.consume()
                                                dragAccumulatedY += dragAmount
                                                val step = (rowStepPx + 8.dp.toPx()).coerceAtLeast(48f)
                                                var idx = currentOrder.indexOf(category)
                                                while (idx > 0 && dragAccumulatedY < -step * 0.55f) {
                                                    currentOnMove(idx, idx - 1)
                                                    dragAccumulatedY += step
                                                    idx--
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                                while (idx >= 0 && idx < currentOrder.lastIndex && dragAccumulatedY > step * 0.55f) {
                                                    currentOnMove(idx, idx + 1)
                                                    dragAccumulatedY -= step
                                                    idx++
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            },
                                        )
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DragIndicator,
                                    contentDescription = stringResource(R.string.profile_search_drag_category, label),
                                    tint = scheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }

            Button(
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.primary,
                    contentColor = scheme.onPrimary,
                ),
            ) {
                Text(
                    text = stringResource(R.string.profile_search_category_done),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun ProfileSearchCategorySection(
    category: ProfileSearchCategory,
    query: String,
    results: com.example.frogreader.data.search.ProfileSearchResults,
    expanded: Boolean,
    palette: ProfileSearchCategoryPalette,
    onToggleExpand: () -> Unit,
    onOpenQuote: (QuoteSearchHit) -> Unit,
    onOpenBookmark: (BookmarkSearchHit) -> Unit,
    onOpenReadingQueue: (ReadingQueueSearchHit) -> Unit,
    onOpenReadingLog: (ReadingLogSearchHit) -> Unit,
    onOpenAchievement: (AchievementSearchHit) -> Unit,
) {
    val totalCount = results.countFor(category)
    if (totalCount == 0) return

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = categoryHeaderIcon(category),
                contentDescription = null,
                tint = palette.headerInk,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = categoryDisplayName(category),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = palette.headerInk,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = totalCount.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = palette.headerInk,
            )
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(),
            shape = RoundedCornerShape(22.dp),
            color = palette.cardContainer,
            border = BorderStroke(1.dp, palette.cardBorder),
            tonalElevation = 0.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                when (category) {
                    ProfileSearchCategory.QUOTES -> {
                        val shown = if (expanded) results.quotes else results.quotes.take(COLLAPSED_PREVIEW_LIMIT)
                        shown.forEachIndexed { index, hit ->
                            if (index > 0) CategoryCardDivider(palette.cardDivider, inset = true)
                            QuoteResultRow(
                                hit = hit,
                                query = query,
                                palette = palette,
                                onClick = { onOpenQuote(hit) },
                            )
                        }
                    }
                    ProfileSearchCategory.BOOKMARKS -> {
                        val shown = if (expanded) results.bookmarks else results.bookmarks.take(COLLAPSED_PREVIEW_LIMIT)
                        shown.forEachIndexed { index, hit ->
                            if (index > 0) CategoryCardDivider(palette.cardDivider, inset = true)
                            BookmarkResultRow(
                                hit = hit,
                                palette = palette,
                                onClick = { onOpenBookmark(hit) },
                            )
                        }
                    }
                    ProfileSearchCategory.READING_QUEUE -> {
                        val shown = if (expanded) results.readingQueue else results.readingQueue.take(COLLAPSED_PREVIEW_LIMIT)
                        shown.forEachIndexed { index, hit ->
                            if (index > 0) CategoryCardDivider(palette.cardDivider, inset = true)
                            ReadingQueueResultRow(
                                hit = hit,
                                palette = palette,
                                onClick = { onOpenReadingQueue(hit) },
                            )
                        }
                    }
                    ProfileSearchCategory.READING_LOG -> {
                        val shown = if (expanded) results.readingLog else results.readingLog.take(COLLAPSED_PREVIEW_LIMIT)
                        shown.forEachIndexed { index, hit ->
                            if (index > 0) CategoryCardDivider(palette.cardDivider, inset = true)
                            ReadingLogResultRow(
                                hit = hit,
                                palette = palette,
                                onClick = { onOpenReadingLog(hit) },
                            )
                        }
                    }
                    ProfileSearchCategory.ACHIEVEMENTS -> {
                        val shown = if (expanded) results.achievements else results.achievements.take(COLLAPSED_PREVIEW_LIMIT)
                        shown.forEachIndexed { index, hit ->
                            if (index > 0) CategoryCardDivider(palette.cardDivider, inset = true)
                            AchievementResultRow(
                                hit = hit,
                                palette = palette,
                                onClick = { onOpenAchievement(hit) },
                            )
                        }
                    }
                }

                if (totalCount > COLLAPSED_PREVIEW_LIMIT) {
                    val remaining = totalCount - COLLAPSED_PREVIEW_LIMIT
                    CategoryCardDivider(palette.cardDivider, inset = false)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 46.dp)
                            .clickable(role = Role.Button, onClick = onToggleExpand)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = if (expanded) {
                                stringResource(R.string.profile_search_show_less)
                            } else {
                                showMoreLabel(category, remaining)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.accentInk,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryCardDivider(
    color: Color,
    inset: Boolean,
) {
    HorizontalDivider(
        modifier = if (inset) Modifier.padding(horizontal = 16.dp) else Modifier,
        thickness = 1.dp,
        color = color,
    )
}

@Composable
private fun CategoryItemBadge(
    icon: ImageVector,
    palette: ProfileSearchCategoryPalette,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(palette.iconBadgeContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = palette.iconInk,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun QuoteResultRow(
    hit: QuoteSearchHit,
    query: String,
    palette: ProfileSearchCategoryPalette,
    onClick: () -> Unit,
) {
    val quotedAnnotated = remember(hit.quoteText, hit.quoteSpans, query, palette.highlightFill, palette.highlightInk) {
        buildQuotedHighlightedText(
            quoteText = hit.quoteText,
            quoteSpans = hit.quoteSpans,
            query = query,
            highlightFill = palette.highlightFill,
            highlightInk = palette.highlightInk,
        )
    }
    val noteAnnotated = remember(hit.noteText, hit.noteSpans, palette.highlightFill, palette.highlightInk) {
        hit.noteText?.let { note ->
            SearchMatchHighlighter.buildHighlightedAnnotatedString(
                text = note,
                spans = hit.noteSpans,
                highlightBackground = palette.highlightFill,
                highlightTextColor = palette.highlightInk,
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        CategoryItemBadge(
            icon = if (hit.noteText != null) Icons.Rounded.EditNote else Icons.Rounded.FormatQuote,
            palette = palette,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                text = quotedAnnotated,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                fontWeight = FontWeight.Medium,
                color = palette.titleInk,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            if (noteAnnotated != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(2.5.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(palette.accentInk),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.quotes_bookmarks_my_thoughts),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.subtitleInk,
                        )
                        Text(
                            text = noteAnnotated,
                            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                            color = palette.titleInk,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            val meta = passageLocationMeta(
                bookTitle = hit.bookTitle,
                bookFormat = hit.bookFormat,
                documentLocation = hit.documentLocation,
                chapterIndex = hit.chapterIndex,
            )
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                    color = palette.subtitleInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = palette.accentInk,
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .size(20.dp),
        )
    }
}

@Composable
private fun BookmarkResultRow(
    hit: BookmarkSearchHit,
    palette: ProfileSearchCategoryPalette,
    onClick: () -> Unit,
) {
    val snippetAnnotated = remember(hit.snippetText, hit.snippetSpans, palette.highlightFill, palette.highlightInk) {
        SearchMatchHighlighter.buildHighlightedAnnotatedString(
            text = hit.snippetText,
            spans = hit.snippetSpans,
            highlightBackground = palette.highlightFill,
            highlightTextColor = palette.highlightInk,
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryItemBadge(
            icon = Icons.Outlined.BookmarkBorder,
            palette = palette,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = snippetAnnotated,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                fontWeight = FontWeight.Medium,
                color = palette.titleInk,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            val meta = passageLocationMeta(
                bookTitle = hit.bookTitle,
                bookFormat = hit.bookFormat,
                documentLocation = hit.documentLocation,
                chapterIndex = hit.chapterIndex,
            )
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                    color = palette.subtitleInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = palette.accentInk,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ReadingQueueResultRow(
    hit: ReadingQueueSearchHit,
    palette: ProfileSearchCategoryPalette,
    onClick: () -> Unit,
) {
    val titleAnnotated = remember(hit.title, hit.titleSpans, palette.highlightFill, palette.highlightInk) {
        SearchMatchHighlighter.buildHighlightedAnnotatedString(
            text = hit.title,
            spans = hit.titleSpans,
            highlightBackground = palette.highlightFill,
            highlightTextColor = palette.highlightInk,
        )
    }
    val authorAnnotated = remember(hit.author, hit.authorSpans, palette.highlightFill, palette.highlightInk) {
        if (hit.author.isBlank()) null
        else SearchMatchHighlighter.buildHighlightedAnnotatedString(
            text = hit.author,
            spans = hit.authorSpans,
            highlightBackground = palette.highlightFill,
            highlightTextColor = palette.highlightInk,
        )
    }
    val noteAnnotated = remember(hit.noteSnippet, hit.noteSpans, palette.highlightFill, palette.highlightInk) {
        hit.noteSnippet?.let { snippet ->
            SearchMatchHighlighter.buildHighlightedAnnotatedString(
                text = snippet,
                spans = hit.noteSpans,
                highlightBackground = palette.highlightFill,
                highlightTextColor = palette.highlightInk,
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryItemBadge(
            icon = Icons.AutoMirrored.Rounded.MenuBook,
            palette = palette,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = titleAnnotated,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                fontWeight = FontWeight.Medium,
                color = palette.titleInk,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (authorAnnotated != null) {
                Text(
                    text = authorAnnotated,
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                    color = palette.subtitleInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (noteAnnotated != null) {
                Text(
                    text = noteAnnotated,
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                    color = palette.subtitleInk,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = palette.accentInk,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ReadingLogResultRow(
    hit: ReadingLogSearchHit,
    palette: ProfileSearchCategoryPalette,
    onClick: () -> Unit,
) {
    val titleAnnotated = remember(hit.title, hit.titleSpans, palette.highlightFill, palette.highlightInk) {
        SearchMatchHighlighter.buildHighlightedAnnotatedString(
            text = hit.title,
            spans = hit.titleSpans,
            highlightBackground = palette.highlightFill,
            highlightTextColor = palette.highlightInk,
        )
    }
    val statusText = stringResource(
        when (hit.status) {
            ReadingLogStatus.FINISHED -> R.string.reading_log_finished
            ReadingLogStatus.IN_PROGRESS -> R.string.reading_log_in_progress
            ReadingLogStatus.ABANDONED -> R.string.reading_log_abandoned
        },
    )
    val subtitleAnnotated = remember(
        hit.author,
        hit.authorSpans,
        hit.series,
        hit.seriesSpans,
        statusText,
        palette.highlightFill,
        palette.highlightInk,
    ) {
        buildLogSubtitleAnnotated(hit, statusText, palette.highlightFill, palette.highlightInk)
    }
    val reviewAnnotated = remember(hit.reviewSnippet, hit.reviewSpans, palette.highlightFill, palette.highlightInk) {
        hit.reviewSnippet?.let { snippet ->
            SearchMatchHighlighter.buildHighlightedAnnotatedString(
                text = snippet,
                spans = hit.reviewSpans,
                highlightBackground = palette.highlightFill,
                highlightTextColor = palette.highlightInk,
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryItemBadge(
            icon = Icons.AutoMirrored.Rounded.MenuBook,
            palette = palette,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = titleAnnotated,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                fontWeight = FontWeight.Medium,
                color = palette.titleInk,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitleAnnotated,
                style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                color = palette.subtitleInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (reviewAnnotated != null) {
                Text(
                    text = reviewAnnotated,
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                    color = palette.subtitleInk,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = palette.accentInk,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun AchievementResultRow(
    hit: AchievementSearchHit,
    palette: ProfileSearchCategoryPalette,
    onClick: () -> Unit,
) {
    val item = hit.achievement
    val titleAnnotated = remember(item.title, hit.titleSpans, palette.highlightFill, palette.highlightInk) {
        SearchMatchHighlighter.buildHighlightedAnnotatedString(
            text = item.title,
            spans = hit.titleSpans,
            highlightBackground = palette.highlightFill,
            highlightTextColor = palette.highlightInk,
        )
    }
    val descAnnotated = remember(item.description, hit.descriptionSpans, palette.highlightFill, palette.highlightInk) {
        SearchMatchHighlighter.buildHighlightedAnnotatedString(
            text = item.description,
            spans = hit.descriptionSpans,
            highlightBackground = palette.highlightFill,
            highlightTextColor = palette.highlightInk,
        )
    }
    val statusColor = if (item.unlocked) palette.accentInk else palette.subtitleInk

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryItemBadge(
            icon = Icons.Outlined.EmojiEvents,
            palette = palette,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = titleAnnotated,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                fontWeight = FontWeight.Medium,
                color = palette.titleInk,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = descAnnotated,
                style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                color = palette.subtitleInk,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Icon(
                    imageVector = if (item.unlocked) Icons.Outlined.CheckCircle else Icons.Rounded.Schedule,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(15.dp),
                )
                val progressText = item.progressLabel?.takeIf { it.isNotBlank() }
                    ?: "${item.currentValue}/${item.targetValue}"
                Text(
                    text = stringResource(
                        if (item.unlocked) R.string.profile_search_achievement_unlocked
                        else R.string.profile_search_achievement_progress,
                        progressText,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = statusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = palette.accentInk,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ProfileSearchStatusPlaceholder(
    icon: ImageVector,
    title: String,
    subtitle: String,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(38.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun categoryDisplayName(category: ProfileSearchCategory): String = stringResource(
    when (category) {
        ProfileSearchCategory.QUOTES -> R.string.profile_search_category_quotes
        ProfileSearchCategory.BOOKMARKS -> R.string.profile_search_category_bookmarks
        ProfileSearchCategory.READING_QUEUE -> R.string.profile_search_category_reading_queue
        ProfileSearchCategory.READING_LOG -> R.string.profile_search_category_reading_log
        ProfileSearchCategory.ACHIEVEMENTS -> R.string.profile_search_category_achievements
    },
)

private fun categoryHeaderIcon(category: ProfileSearchCategory): ImageVector = when (category) {
    ProfileSearchCategory.QUOTES -> Icons.Rounded.FormatQuote
    ProfileSearchCategory.BOOKMARKS -> Icons.Outlined.BookmarkBorder
    ProfileSearchCategory.READING_QUEUE -> Icons.Rounded.Checklist
    ProfileSearchCategory.READING_LOG -> Icons.Rounded.History
    ProfileSearchCategory.ACHIEVEMENTS -> Icons.Outlined.EmojiEvents
}

@Composable
private fun showMoreLabel(category: ProfileSearchCategory, count: Int): String = pluralStringResource(
    when (category) {
        ProfileSearchCategory.QUOTES -> R.plurals.profile_search_more_quotes
        ProfileSearchCategory.BOOKMARKS -> R.plurals.profile_search_more_bookmarks
        ProfileSearchCategory.READING_QUEUE -> R.plurals.profile_search_more_queue
        ProfileSearchCategory.READING_LOG -> R.plurals.profile_search_more_log
        ProfileSearchCategory.ACHIEVEMENTS -> R.plurals.profile_search_more_achievements
    },
    count,
    count,
)

@Composable
private fun passageLocationMeta(
    bookTitle: String,
    bookFormat: BookFormat,
    documentLocation: DocumentLocation?,
    chapterIndex: Int,
): String {
    if (bookTitle.isBlank()) return ""
    return when (documentLocation) {
        is DocumentLocation.Pdf ->
            stringResource(R.string.profile_search_meta_page, bookTitle, documentLocation.pageIndex + 1)
        is DocumentLocation.Reflowable ->
            if (documentLocation.chapterIndex >= 0) {
                stringResource(R.string.profile_search_meta_chapter, bookTitle, documentLocation.chapterIndex + 1)
            } else {
                bookTitle
            }
        null ->
            if (bookFormat != BookFormat.PDF && chapterIndex >= 0) {
                stringResource(R.string.profile_search_meta_chapter, bookTitle, chapterIndex + 1)
            } else {
                bookTitle
            }
    }
}

private fun buildQuotedHighlightedText(
    quoteText: String,
    quoteSpans: List<MatchSpan>,
    query: String,
    highlightFill: Color,
    highlightInk: Color,
): AnnotatedString {
    // Long passages are windowed around the first word match so the highlighted word is always
    // within the three visible lines instead of hiding behind the ellipsis.
    val (displayBody, bodySpans) = if (quoteText.length > 160) {
        val snippet = SearchMatchHighlighter.extractContextSnippet(
            text = quoteText,
            query = query,
            beforeChars = 40,
            afterChars = 110,
            maxFallbackChars = 150,
        )
        snippet.text to snippet.spans
    } else {
        quoteText to quoteSpans
    }
    val wrapped = "“$displayBody”"
    val shiftedSpans = bodySpans.map { MatchSpan(it.start + 1, it.endExclusive + 1) }
    return SearchMatchHighlighter.buildHighlightedAnnotatedString(
        text = wrapped,
        spans = shiftedSpans,
        highlightBackground = highlightFill,
        highlightTextColor = highlightInk,
    )
}

private fun buildLogSubtitleAnnotated(
    hit: ReadingLogSearchHit,
    statusText: String,
    highlightFill: Color,
    highlightInk: Color,
): AnnotatedString {
    val highlightStyle = SpanStyle(color = highlightInk, background = highlightFill)
    return buildAnnotatedString {
        var needsDot = false
        if (hit.author.isNotBlank()) {
            val startOffset = length
            append(hit.author)
            for (span in hit.authorSpans) {
                val s = (startOffset + span.start).coerceIn(startOffset, length)
                val e = (startOffset + span.endExclusive).coerceIn(s, length)
                if (e > s) addStyle(highlightStyle, s, e)
            }
            needsDot = true
        }
        if (hit.series.isNotBlank() && (hit.seriesSpans.isNotEmpty() || hit.author.isBlank())) {
            if (needsDot) append(" · ")
            val startOffset = length
            append(hit.series)
            for (span in hit.seriesSpans) {
                val s = (startOffset + span.start).coerceIn(startOffset, length)
                val e = (startOffset + span.endExclusive).coerceIn(s, length)
                if (e > s) addStyle(highlightStyle, s, e)
            }
            needsDot = true
        }
        if (needsDot) append(" · ")
        append(statusText)
    }
}
