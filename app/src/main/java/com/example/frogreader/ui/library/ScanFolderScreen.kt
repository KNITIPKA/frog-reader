package com.example.frogreader.ui.library

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.R
import com.example.frogreader.data.DuplicateMatch
import com.example.frogreader.data.FolderScanner
import com.example.frogreader.ui.theme.LocalFrogColors

/**
 * What was found in the folder the user picked, and which of it to add.
 *
 * Built out of the library's own controls — the gradient header with its glass
 * search pill, the 20dp list row, the morphing button — rather than stock
 * Material ones. A screen reached from the library in one tap that answers with
 * outlined text fields, square checkboxes and a hairline-bordered card does not
 * read as the same app; it reads as a system picker that happens to be inside
 * it. Nothing here is decoration for its own sake: every value below is lifted
 * from LibraryScreen so the two cannot drift.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun ScanFolderScreen(
    treeUri: Uri,
    onDismiss: () -> Unit,
    onPickAnotherFolder: () -> Unit,
    /** Reported once a batch finishes, so the library can say what happened. */
    onFinished: (added: Int, failed: Int) -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as FrogReaderApp
    val frog = LocalFrogColors.current
    val scheme = MaterialTheme.colorScheme

    // Keyed on the folder: picking a different one starts a different scan
    // rather than appending to this one's results.
    val state = remember(treeUri) {
        ScanFolderState(
            repository = app.bookRepository,
            scope = scope,
            cacheDir = context.cacheDir,
        )
    }

    LaunchedEffect(treeUri) { state.start(context, treeUri) }
    DisposableEffect(treeUri) { onDispose { state.dispose() } }

    val visible = state.visibleRows
    val importing = state.importing
    val selected = state.selectedCount

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // Or the dialog stops short of the system bars and the activity
            // shows through as a dark band above the header — the app looks
            // like it changed theme on the way in.
            decorFitsSystemWindows = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(scheme.surface)
                // Drawing under the system bars is what keeps the header's
                // gradient continuous with the status bar; the keyboard still
                // has to push the list and the button up rather than cover them.
                .imePadding(),
        ) {
            // ---------------------------------------------------------- header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
                    .background(Brush.verticalGradient(listOf(frog.headerTop, frog.headerBottom)))
                    // Same inset the library header takes, and for the same
                    // reason: measured whether or not the bar is on screen.
                    .padding(WindowInsets.statusBarsIgnoringVisibility.asPaddingValues())
                    .padding(bottom = 14.dp),
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GlassIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.dup_action_cancel),
                        onClick = onDismiss,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = FolderScanner.folderName(treeUri)
                                ?: stringResource(R.string.scan_folder_fallback),
                            fontSize = 19.sp,
                            lineHeight = 23.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp,
                            color = frog.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (state.scanning) {
                                stringResource(R.string.scan_looking, state.rows.size)
                            } else {
                                stringResource(
                                    R.string.scan_found,
                                    state.rows.size,
                                    state.alreadyInLibraryCount,
                                )
                            },
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = frog.ink2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (state.rows.isNotEmpty()) {
                    FrogSearchField(
                        query = state.query,
                        onQueryChange = { state.query = it },
                        hint = stringResource(R.string.scan_search_placeholder),
                        modifier = Modifier
                            .padding(start = 16.dp, end = 16.dp, top = 14.dp)
                            .fillMaxWidth(),
                    )
                }
            }

            // ----------------------------------------------------------- body
            // Boxed with a weight so every branch shares one rule about how
            // much height it may take, and none of them can push the add
            // button off the bottom of the screen.
            Box(modifier = Modifier.weight(1f)) {
                when {
                state.accessLost -> ScanEmpty(
                    icon = Icons.Rounded.FolderOff,
                    title = stringResource(R.string.scan_access_lost),
                    subtitle = stringResource(R.string.scan_access_lost_hint),
                    actionLabel = stringResource(R.string.scan_pick_again),
                    onAction = onPickAnotherFolder,
                )

                state.rows.isEmpty() && state.scanning -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LoadingIndicator(Modifier.size(48.dp))
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = stringResource(R.string.scan_title),
                            fontSize = 13.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }

                state.rows.isEmpty() -> ScanEmpty(
                    icon = Icons.Rounded.FolderOff,
                    title = stringResource(R.string.scan_empty),
                    subtitle = stringResource(R.string.scan_empty_hint),
                    actionLabel = stringResource(R.string.scan_pick_again),
                    onAction = onPickAnotherFolder,
                )

                else -> Column {
                    val allTicked = visible.isNotEmpty() &&
                        visible.all { !it.selectable || it.selected }

                    // The library's section header, to the pixel: uppercase
                    // label on the left, control on the right.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionLabel(
                            text = if (selected > 0) {
                                stringResource(R.string.scan_selected_count, selected)
                            } else {
                                stringResource(R.string.scan_section_found)
                            },
                        )
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(scheme.surfaceContainerHigh)
                                .clickable(enabled = importing == null) {
                                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    state.setAllSelected(!allTicked)
                                }
                                .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            SelectionCheck(selected = allTicked, enabled = importing == null)
                            Text(
                                text = stringResource(
                                    if (allTicked) R.string.scan_clear_all else R.string.scan_select_all,
                                ).uppercase(),
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.9.sp,
                                color = frog.ink2,
                                maxLines = 1,
                            )
                        }
                    }

                    if (visible.isEmpty()) {
                        ScanEmpty(
                            icon = Icons.Rounded.Search,
                            title = stringResource(R.string.scan_empty_search),
                            subtitle = null,
                            actionLabel = null,
                            onAction = {},
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                bottom = 16.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(visible, key = { it.id }) { row ->
                                ScanRowCard(
                                    row = row,
                                    enabled = importing == null,
                                    onToggle = {
                                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                        state.toggleSelected(row.id)
                                    },
                                    onRetry = { state.retry(row.id) },
                                )
                            }
                        }
                    }
                }
                }
            }

            // ------------------------------------------------------ add button
            if (state.rows.isNotEmpty() && !state.accessLost) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(scheme.surface)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    if (importing != null) {
                        Text(
                            text = stringResource(
                                R.string.scan_adding,
                                importing.done + 1,
                                importing.total,
                            ),
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.6.sp,
                            color = frog.ink2,
                        )
                        Spacer(Modifier.height(7.dp))
                        LinearProgressIndicator(
                            progress = {
                                if (importing.total == 0) 0f
                                else importing.done.toFloat() / importing.total
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    MorphingButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            state.addSelected { added, failed ->
                                // Close on success and let the library report
                                // it: the books are there now, and staying on a
                                // list where every row says "already added"
                                // helps nobody. A run with failures stays put so
                                // the rows that went wrong can be seen.
                                onFinished(added, failed)
                                if (failed == 0) onDismiss()
                            }
                        },
                        enabled = selected > 0 && importing == null,
                        color = if (selected > 0 && importing == null) scheme.primary else frog.chip,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        Text(
                            text = if (selected > 0) {
                                stringResource(R.string.scan_add_n, selected).uppercase()
                            } else {
                                stringResource(R.string.scan_add_none).uppercase()
                            },
                            fontSize = 11.5.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp,
                            color = if (selected > 0 && importing == null) {
                                scheme.onPrimary
                            } else {
                                frog.ink2
                            },
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        val conflict by state.conflicts.current.collectAsStateWithLifecycle()
        conflict?.let { pending ->
            DuplicateBookDialog(
                conflict = pending,
                onChoice = { choice, applyToRest -> state.answerConflict(choice, applyToRest) },
            )
        }
    }
}

/**
 * One found book.
 *
 * The library's list row, down to the 20dp corner, the surfaceContainer fill,
 * the 48×72 cover at 10dp and the tiny letterspaced meta line — with the
 * progress fill swapped for a selection state, because that is the one thing
 * this list is for.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScanRowCard(
    row: ScanRow,
    enabled: Boolean,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
) {
    val frog = LocalFrogColors.current
    val scheme = MaterialTheme.colorScheme
    val failed = row.state == ScanRowState.FAILED

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (row.selected) frog.folder else scheme.surfaceContainer)
            .clickable(enabled = enabled && row.selectable, onClick = onToggle),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(scheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (row.cover != null) {
                    AsyncImage(
                        model = row.cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Rounded.AutoStories,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = frog.ink2.copy(alpha = 0.5f),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    fontSize = 13.5.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (failed) scheme.onSurface.copy(alpha = 0.5f) else scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = row.author ?: stringResource(R.string.import_author_unknown),
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    text = scanMetaLine(row).uppercase(),
                    fontSize = 8.5.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.7.sp,
                    color = when (row.state) {
                        ScanRowState.IN_LIBRARY -> scheme.primary
                        ScanRowState.FAILED -> scheme.error
                        else -> frog.ink2.copy(alpha = 0.6f)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box(
                modifier = Modifier.size(34.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (row.state) {
                    ScanRowState.PENDING -> LoadingIndicator(Modifier.size(18.dp))
                    ScanRowState.FAILED -> Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.scan_retry),
                        tint = frog.ink2,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable(enabled = enabled, onClick = onRetry),
                    )

                    else -> SelectionCheck(selected = row.selected, enabled = enabled)
                }
            }
        }
    }
}

/** "EPUB · 2.5 MB · ALREADY ADDED" — one line, the way a library row reads. */
@Composable
private fun scanMetaLine(row: ScanRow): String {
    val size = formatFileSize(row.sizeBytes)
    val status = when (row.state) {
        ScanRowState.IN_LIBRARY -> stringResource(
            if (row.match == DuplicateMatch.SAME_FILE) {
                R.string.scan_state_in_library
            } else {
                R.string.scan_state_similar
            },
        )

        ScanRowState.FAILED -> stringResource(R.string.scan_state_failed)
        ScanRowState.PENDING -> stringResource(R.string.scan_state_reading)
        ScanRowState.READY -> null
    }
    return listOfNotNull("${row.format.name} · $size", status).joinToString(" · ")
}

@Composable
private fun ScanEmpty(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    val frog = LocalFrogColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(frog.chip),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = frog.ink2,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                fontSize = 16.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            if (actionLabel != null) {
                Spacer(Modifier.height(18.dp))
                MorphingButton(
                    onClick = onAction,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .height(44.dp)
                        .width(180.dp),
                ) {
                    Text(
                        text = actionLabel.uppercase(),
                        fontSize = 11.5.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}
