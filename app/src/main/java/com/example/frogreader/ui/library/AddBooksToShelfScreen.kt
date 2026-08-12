package com.example.frogreader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.frogreader.R
import com.example.frogreader.data.model.Book
import com.example.frogreader.ui.theme.LocalFrogColors
import androidx.compose.foundation.layout.ExperimentalLayoutApi

/**
 * Pick books to put on a shelf.
 *
 * A full-screen dialog rather than a route, exactly as ScanFolderScreen is: it
 * is a detour off the library that ends by handing an answer back, and giving
 * it a nav destination would put it in the back stack of a screen it is
 * conceptually part of.
 *
 * [candidates] is everything not already on this shelf — a book belongs to at
 * most one shelf, so picking one here quietly moves it out of the shelf it was
 * in, and the row says so.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddBooksToShelfScreen(
    shelfName: String,
    candidates: List<Book>,
    shelfNameOf: (Book) -> String?,
    coverOf: (Book) -> java.io.File?,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val frog = LocalFrogColors.current

    var query by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<String>() }

    val shown = remember(candidates, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            candidates
        } else {
            candidates.filter {
                it.title.contains(needle, ignoreCase = true) ||
                    it.author?.contains(needle, ignoreCase = true) == true
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // Or the dialog stops short of the system bars and the activity
            // shows through as a dark band above the header.
            decorFitsSystemWindows = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(scheme.surface)
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
                    .background(Brush.verticalGradient(listOf(frog.headerTop, frog.headerBottom)))
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
                            text = stringResource(R.string.shelf_add_books),
                            fontSize = 19.sp,
                            lineHeight = 23.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp,
                            color = frog.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = shelfName,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = frog.ink2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                FrogSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    hint = stringResource(R.string.shelf_add_books_search),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }

            if (candidates.isEmpty() || shown.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (candidates.isEmpty()) {
                            stringResource(R.string.shelf_add_books_empty)
                        } else {
                            stringResource(R.string.library_search_empty)
                        },
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 14.dp,
                        bottom = 14.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id }) { book ->
                        CandidateRow(
                            book = book,
                            coverFile = coverOf(book),
                            inShelf = shelfNameOf(book),
                            selected = book.id in picked,
                            onToggle = {
                                if (!picked.remove(book.id)) picked += book.id
                            },
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            ) {
                MorphingButton(
                    onClick = { onConfirm(picked.toList()) },
                    color = if (picked.isEmpty()) scheme.surfaceContainerHigh else scheme.primary,
                    enabled = picked.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text(
                        text = if (picked.isEmpty()) {
                            stringResource(R.string.shelf_add_books_none)
                        } else {
                            stringResource(R.string.shelf_add_books_count, picked.size)
                        }.uppercase(),
                        fontSize = 12.5.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                        color = if (picked.isEmpty()) scheme.onSurfaceVariant else scheme.onPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(
    book: Book,
    coverFile: java.io.File?,
    inShelf: String?,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val frog = LocalFrogColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) frog.folder else scheme.surfaceContainer)
            .clickable(onClick = onToggle)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 42.dp, height = 63.dp)
                .clip(RoundedCornerShape(9.dp)),
        ) {
            BookCover(book = book, coverFile = coverFile, titleSize = 7.sp, padding = 5.dp)
        }

        Column(modifier = Modifier.weight(1f)) {
            TileTitle(text = book.title, fontSize = 13.5.sp)
            book.author?.let { author ->
                Spacer(Modifier.height(2.dp))
                TileSubtitle(text = author, fontSize = 11.5.sp)
            }
            // A book lives on one shelf at a time, so say which one it is about
            // to leave rather than silently moving it.
            if (inShelf != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.shelf_add_books_moves_from, inShelf).uppercase(),
                    fontSize = 8.5.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.7.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(2.dp))
        SelectionCheck(selected = selected)
    }
}
