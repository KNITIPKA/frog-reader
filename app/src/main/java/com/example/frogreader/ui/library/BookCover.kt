package com.example.frogreader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.example.frogreader.R
import com.example.frogreader.data.model.Book

/**
 * A cover image, or a deterministic gradient plate with the title on it. The
 * hue comes from the title, so the same book always gets the same plate.
 *
 * Lives outside `LibraryScreen.kt` because the folder panel draws covers too,
 * and every other size of cover in the app is a call to this one function.
 */
@Composable
internal fun BookCover(
    book: Book,
    coverFile: java.io.File?,
    titleSize: TextUnit,
    padding: Dp,
    alignBottom: Boolean = false,
) {
    if (coverFile != null) {
        val platform = LocalPlatformContext.current
        // Cover nodes are thrown away and rebuilt constantly — on every scroll
        // and on every grid/list swap. Pinning the memory-cache key to the file
        // path (a new cover always gets a new name) lets the rebuilt node paint
        // the cached bitmap on its FIRST frame; without it Coil treats each new
        // node as a fresh load and the tile flashes empty.
        val request = remember(platform, coverFile) {
            ImageRequest.Builder(platform)
                .data(coverFile)
                .memoryCacheKey(coverFile.path)
                .placeholderMemoryCacheKey(coverFile.path)
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = stringResource(R.string.library_book_cover, book.title),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        FallbackCover(
            book = book,
            titleSize = titleSize,
            padding = padding,
            alignBottom = alignBottom,
        )
    }
}

@Composable
private fun FallbackCover(
    book: Book,
    titleSize: TextUnit,
    padding: Dp,
    alignBottom: Boolean = false,
) {
    val (top, bottom) = remember(book.title) { plateColors(book.title) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(top, bottom))),
        contentAlignment = if (alignBottom) Alignment.BottomStart else Alignment.Center,
    ) {
        if (titleSize.value > 0f) {
            Text(
                text = book.title.uppercase(),
                fontSize = titleSize,
                lineHeight = 1.3.em,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.92f),
                textAlign = if (alignBottom) TextAlign.Start else TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

/** Deterministic dark→light plate, hue derived from the title. */
private fun plateColors(title: String): Pair<Color, Color> {
    var hash = 0
    for (character in title) hash = hash * 31 + character.code
    val hue = ((hash % 360) + 360) % 360
    return Color.hsl(hue.toFloat(), 0.42f, 0.27f) to
        Color.hsl(((hue + 22) % 360).toFloat(), 0.34f, 0.47f)
}
