package com.example.frogreader.ui.profile

import com.example.frogreader.ui.components.FrogSnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import android.graphics.Bitmap
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frogreader.R
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.ReadingStats
import com.example.frogreader.data.model.Book
import java.time.LocalDate
import com.example.frogreader.data.model.ReadingStatus
import com.example.frogreader.ui.library.FrogHeaderContent
import com.example.frogreader.ui.library.FrogHeroCorner
import com.example.frogreader.ui.library.FrogHeroPadding
import com.example.frogreader.ui.library.frogHeroHeight
import com.example.frogreader.ui.theme.LocalFrogColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    contentPadding: PaddingValues = PaddingValues(),
    onOpenSettings: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenAchievements: (() -> Unit)? = null,
    onOpenQuotesAndBookmarks: () -> Unit = {},
    onOpenReadingQueue: () -> Unit = {},
    onOpenReadingLog: () -> Unit = {},
    isActive: Boolean = true,
    viewModel: ProfileViewModel = viewModel(factory = ProfileViewModel.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val nameSaveError by viewModel.nameSaveError.collectAsStateWithLifecycle()
    val nameSaveErrorMessage = stringResource(R.string.profile_save_error)
    val snackbar = remember { SnackbarHostState() }
    val notificationScope = rememberCoroutineScope()
    LaunchedEffect(nameSaveError, nameSaveErrorMessage) {
        if (nameSaveError) {
            viewModel.dismissNameSaveError()
            notificationScope.launch {
                snackbar.showSnackbar(nameSaveErrorMessage, duration = SnackbarDuration.Long)
            }
        }
    }
    val books by viewModel.books.collectAsStateWithLifecycle()
    val readingQueue by viewModel.readingQueue.collectAsStateWithLifecycle()
    val readingLog by viewModel.readingLog.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val nowMillis by viewModel.nowMillis.collectAsStateWithLifecycle()
    val avatar by viewModel.avatar.collectAsStateWithLifecycle()
    val avatarEditor by viewModel.avatarEditor.collectAsStateWithLifecycle()
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::chooseAvatar)
    }
    val onPickAvatar = {
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    Box(Modifier.fillMaxSize()) {
        ProfileDashboard(
            settings = settings, books = books, stats = stats, today = today, avatar = avatar,
            nowMillis = nowMillis,
            readingQueueCount = readingQueue.size,
            readingLogCount = readingLog.size,
            contentPadding = contentPadding,
            isActive = isActive,
            onOpenSettings = onOpenSettings, onOpenStats = onOpenStats,
            onOpenAchievements = onOpenAchievements,
            onOpenQuotesAndBookmarks = onOpenQuotesAndBookmarks, onOpenReadingQueue = onOpenReadingQueue, onOpenReadingLog = onOpenReadingLog,
            onPickAvatar = onPickAvatar, onSaveGoal = viewModel::saveDailyGoal,
            onSaveName = viewModel::updateName,
        )
        FrogSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)
            .padding(bottom = contentPadding.calculateBottomPadding() + 16.dp))
    }

    if (avatarEditor.visible) {
        AvatarEditor(avatarEditor, onDismiss = viewModel::dismissAvatarEditor, onSave = viewModel::saveAvatar)
    }

}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfileDashboard(
    settings: AppSettings,
    books: List<Book>,
    stats: ReadingStats,
    today: LocalDate,
    avatar: Bitmap?,
    nowMillis: Long = System.currentTimeMillis(),
    readingQueueCount: Int = 0,
    readingLogCount: Int? = null,
    contentPadding: PaddingValues = PaddingValues(),
    onOpenSettings: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenAchievements: (() -> Unit)? = null,
    onOpenQuotesAndBookmarks: () -> Unit = {},
    onOpenReadingQueue: () -> Unit = {},
    onOpenReadingLog: () -> Unit = {},
    onPickAvatar: () -> Unit = {},
    onSaveName: (String) -> Unit = {},
    onSaveGoal: suspend (Int) -> Unit = {},
    isActive: Boolean = true,
) {
    val readMinutes = remember(stats) { stats.dailySeconds.values.sum() / 60 }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var nameEditorBounds by remember { mutableStateOf<Rect?>(null) }
    var dashboardPosition by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().padding(contentPadding)
            .onGloballyPositioned { dashboardPosition = it.positionInRoot() }
            .pointerInput(focusManager, keyboard) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val bounds = nameEditorBounds
                    if (bounds != null && !bounds.contains(down.position + dashboardPosition)) {
                        // Buttons and empty space do not necessarily take focus.
                        // Observe without consuming, so their own action still runs.
                        focusManager.clearFocus()
                        keyboard?.hide()
                    }
                }
            },
    ) {
        val systemPadding = WindowInsets.systemBarsIgnoringVisibility
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal).asPaddingValues()
        val compact = maxHeight - systemPadding.calculateTopPadding() < 760.dp
        FillRemainingColumn(
            modifier = Modifier.fillMaxSize()
                // Normally all cards fit. Preserve access with large accessibility
                // fonts, a landscape window, or the keyboard rather than clipping.
                .verticalScroll(rememberScrollState()),
        ) {
            FrogHeaderContent(
                query = "",
                onQueryChange = {},
                searchHint = stringResource(R.string.profile_search_hint),
                searchEnabled = false,
                onOpenSettings = onOpenSettings,
            ) {
                ProfileCard(
                    name = settings.profileName,
                    avatar = avatar,
                    readMinutes = readMinutes,
                    lastReadAtMillis = stats.lastReadAtMillis,
                    nowMillis = nowMillis,
                    onPickAvatar = onPickAvatar,
                    onOpenStats = onOpenStats,
                    onOpenAchievements = onOpenAchievements,
                    onSaveName = onSaveName,
                    onNameEditorBoundsChanged = { nameEditorBounds = it },
                    isActive = isActive,
                )
            }
            // The sections card stretches down to the dock; it shrinks back as the
            // goal ruler expands, and the page scrolls once it reaches natural size.
            FillRemainingColumn(
                modifier = Modifier.padding(WindowInsets.systemBarsIgnoringVisibility
                    .only(WindowInsetsSides.Horizontal).asPaddingValues())
                    .padding(horizontal = 16.dp).padding(bottom = 8.dp),
                spacing = if (compact) 8.dp else 12.dp,
                maxFillHeight = ProfileSectionsMaxHeight,
            ) {
                ReadingActivityCard(
                    stats = stats,
                    today = today,
                    goalMinutes = settings.dailyGoalMinutes,
                    onSaveGoal = onSaveGoal,
                    compact = compact,
                )
                ProfileSectionsCard(
                    quotes = books.sumOf { it.quotes.size },
                    bookmarks = books.sumOf { it.bookmarks.size },
                    toRead = readingQueueCount,
                    finished = readingLogCount ?: books.count { it.status == ReadingStatus.FINISHED },
                    onOpenQuotesAndBookmarks = onOpenQuotesAndBookmarks,
                    onOpenReadingQueue = onOpenReadingQueue,
                    onOpenReadingLog = onOpenReadingLog,
                    compact = compact,
                )
            }
        }
    }
}

/** Caps how far the sections card stretches on tall (tablet) windows. */
private val ProfileSectionsMaxHeight = 360.dp

// Placeholder until achievements exist; shown as "5 of 32" on the profile card.
private const val PlaceholderAchievementsUnlocked = 5
private const val PlaceholderAchievementsTotal = 32

/**
 * A column whose last child receives at least the height left over by the others,
 * so it can grow into empty space. Unlike `weight`, it never squeezes the last
 * child below its natural height: when space runs out the column simply grows
 * (and the parent scrolls).
 */
@Composable
private fun FillRemainingColumn(
    modifier: Modifier = Modifier,
    spacing: Dp = 0.dp,
    maxFillHeight: Dp = Dp.Infinity,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val head = measurables.dropLast(1).map { it.measure(loose) }
        val used = head.sumOf { it.height } + gap * (measurables.size - 1).coerceAtLeast(0)
        val cap = if (maxFillHeight == Dp.Infinity) Int.MAX_VALUE else maxFillHeight.roundToPx()
        val fill = (constraints.minHeight - used).coerceIn(0, cap)
        val last = measurables.lastOrNull()?.measure(loose.copy(minHeight = fill))
        val placeables = head + listOfNotNull(last)
        val width = constraints.constrainWidth(placeables.maxOfOrNull { it.width } ?: 0)
        val height = constraints.constrainHeight(used + (last?.height ?: 0))
        layout(width, height) {
            var y = 0
            placeables.forEach { placeable ->
                placeable.placeRelative(0, y)
                y += placeable.height + gap
            }
        }
    }
}

@Composable
internal fun ProfileCard(
    name: String,
    avatar: Bitmap?,
    onSaveName: (String) -> Unit,
    onPickAvatar: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenAchievements: (() -> Unit)? = null,
    readMinutes: Long = 0,
    achievementsUnlocked: Int = PlaceholderAchievementsUnlocked,
    achievementsTotal: Int = PlaceholderAchievementsTotal,
    lastReadAtMillis: Long? = null,
    nowMillis: Long = System.currentTimeMillis(),
    onNameEditorBoundsChanged: (Rect?) -> Unit = {},
    isActive: Boolean = true,
) {
    val frog = LocalFrogColors.current
    val scheme = MaterialTheme.colorScheme
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var nameEditRequested by rememberSaveable { mutableStateOf(false) }
    val changeAvatarLabel = stringResource(R.string.profile_photo_change)
    Surface(
        shape = RoundedCornerShape(FrogHeroCorner),
        shadowElevation = 2.dp,
        color = frog.profileBottom,
        contentColor = frog.onProfile,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.profileLandscape(frog)
                .height(frogHeroHeight()).padding(FrogHeroPadding),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(84.dp)
                        .border(1.dp, scheme.primaryContainer, CircleShape)
                        .padding(2.dp).clip(CircleShape).background(scheme.primaryContainer)
                        .clickable(role = Role.Button, onClickLabel = changeAvatarLabel, onClick = onPickAvatar),
                    contentAlignment = Alignment.Center,
                ) {
                    if (avatar != null) {
                        Image(
                            bitmap = remember(avatar) { avatar.asImageBitmap() },
                            contentDescription = changeAvatarLabel,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else if (name.isBlank()) {
                        Icon(Icons.Rounded.Person, changeAvatarLabel, Modifier.size(32.dp), tint = scheme.onPrimaryContainer)
                    } else {
                        val initial = String(Character.toChars(name.codePointAt(0)))
                        Text(initial.uppercase(), color = scheme.onPrimaryContainer,
                            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                ) {
                    ProfileName(
                        name = name,
                        onSave = onSaveName,
                        modifier = Modifier.fillMaxWidth(),
                        maxHeight = 56.dp,
                        editRequested = nameEditRequested,
                        onEditRequestHandled = { nameEditRequested = false },
                        onEditorBoundsChanged = onNameEditorBoundsChanged,
                        isActive = isActive,
                    )
                    ProfileLastRead(lastReadAtMillis, nowMillis)
                }
                Box(Modifier.align(Alignment.Top)) {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.MoreVert, stringResource(R.string.profile_more), Modifier.size(20.dp))
                    }
                    ProfileOptionsMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        onChangeName = { menuOpen = false; nameEditRequested = true },
                        onChangeAvatar = { menuOpen = false; onPickAvatar() },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileActionTile(
                    icon = Icons.Rounded.EmojiEvents,
                    title = stringResource(R.string.profile_achievements),
                    summary = stringResource(R.string.profile_coming_soon),
                    color = frog.profileLandscape.action,
                    contentColor = frog.profileLandscape.onAction,
                    iconColor = frog.profileLandscape.actionIcon,
                    iconBackground = frog.profileLandscape.actionIconBackground,
                    secondaryColor = frog.profileLandscape.actionSecondary,
                    border = BorderStroke(1.dp, frog.profileLandscape.actionBorder),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = onOpenAchievements ?: {},
                    enabled = false,
                )
                ProfileActionTile(
                    icon = Icons.Rounded.BarChart,
                    title = stringResource(R.string.profile_stats),
                    summary = stringResource(R.string.profile_coming_soon),
                    color = frog.profileLandscape.action,
                    contentColor = frog.profileLandscape.onAction,
                    iconColor = frog.profileLandscape.actionIcon,
                    iconBackground = frog.profileLandscape.actionIconBackground,
                    secondaryColor = frog.profileLandscape.actionSecondary,
                    border = BorderStroke(1.dp, frog.profileLandscape.actionBorder),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = onOpenStats,
                    enabled = false,
                )
            }
        }
    }
}

@Composable
private fun ProfileActionTile(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    color: Color,
    contentColor: Color,
    iconColor: Color,
    iconBackground: Color,
    secondaryColor: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    border: BorderStroke? = null,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = color,
        contentColor = contentColor,
        border = border,
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .heightIn(min = 48.dp)
                .padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(30.dp).background(iconBackground, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(18.dp), tint = iconColor)
            }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 14.sp, stepSize = 0.5.sp),
                )
                if (!summary.isNullOrBlank()) {
                    Text(
                        text = summary,
                        color = secondaryColor,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
