package com.example.frogreader.ui.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.frogreader.data.model.Book
import com.example.frogreader.ui.library.BookCover
import com.example.frogreader.ui.nav.sharedBookCover
import kotlinx.coroutines.delay
import java.io.File

/** How wide the cover stands on the opening screen. */
private val OpeningCoverWidth = 172.dp

/**
 * The book is only allowed to look busy after this long. A cached open lands
 * well inside it, so re-opening never flashes a spinner.
 */
private const val SlowOpenMillis = 450L

/**
 * What the reader shows while the book is being parsed: its cover, its title,
 * its author.
 *
 * This exists to give the shared-element morph something to land on. The cover
 * inside the page content only appears once the book is `Ready`, and only when
 * the reader happens to be looking at the first page — so opening a book from
 * the middle, which is most opens, had no matching element at all and fell
 * back to a plain zoom of the whole screen.
 *
 * Everything here comes from the library index, which is already in memory:
 * nothing on this screen waits for the parse it is covering.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ReaderOpeningScreen(
    book: Book?,
    coverFile: File?,
    colors: ReaderColors,
    modifier: Modifier = Modifier,
) {
    // Painted, not transparent: this sits ON TOP of the reading surface while
    // the page is laid out underneath, and the text must not show through.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        if (book == null) {
            LoadingIndicator()
            return@Box
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(OpeningCoverWidth)
                    .aspectRatio(2f / 3f)
                    .sharedBookCover(book.id)
                    .shadow(18.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp)),
            ) {
                BookCover(book = book, coverFile = coverFile, titleSize = 15.sp, padding = 14.dp)
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            book.author?.let { author ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.secondaryText,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(36.dp))
            var slow by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(SlowOpenMillis)
                slow = true
            }
            // Reserved height either way, so the cover does not shift upward
            // the moment the indicator appears.
            val indicator by animateFloatAsState(
                targetValue = if (slow) 1f else 0f,
                animationSpec = tween(180),
                label = "openingIndicator",
            )
            Box(Modifier.height(48.dp), contentAlignment = Alignment.TopCenter) {
                LoadingIndicator(modifier = Modifier.graphicsLayer { alpha = indicator })
            }
        }
    }
}
