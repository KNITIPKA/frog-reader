package com.example.frogreader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.example.frogreader.R
import com.example.frogreader.data.model.Book
import com.example.frogreader.ui.theme.CoverCaptionInk
import com.example.frogreader.ui.theme.CoverCaptionScrim
import kotlin.math.PI
import kotlin.math.cos

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

/**
 * Title and author laid over the foot of a cover.
 *
 * Under the cover, a three-column grid had about 100dp for a title, so most
 * titles ended in "…" after a dozen characters and every author with more than
 * one name lost the rest. On the cover there is room for three lines, and the
 * tiles in a row come out the same height whatever their titles are.
 *
 * The veil is as tall as the text needs, not a fixed band: a one-line title
 * darkens a sliver of the artwork, a three-line one darkens more. Its fade-in
 * is an absolute [CaptionFade] eased over several stops — a two-stop linear
 * gradient shows a visible edge where it starts, which is exactly the "band
 * stuck on the picture" look this is meant to avoid.
 */
@Composable
internal fun BoxScope.CoverCaption(
    title: String,
    author: String?,
    modifier: Modifier = Modifier,
    titleSize: TextUnit = 12.5.sp,
    authorSize: TextUnit = 10.5.sp,
    padding: Dp = 9.dp,
    maxTitleLines: Int = 3,
) {
    Column(
        modifier = modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .drawWithCache {
                val fade = (CaptionFade.toPx() / size.height).coerceIn(0f, 0.8f)
                val stops = Array(CaptionFadeSteps + 2) { index ->
                    if (index <= CaptionFadeSteps) {
                        val t = index / CaptionFadeSteps.toFloat()
                        // Ease-in-out: no edge where it starts, no shelf where it lands.
                        val eased = (1f - cos(t * PI.toFloat())) / 2f
                        fade * t to CoverCaptionScrim.copy(alpha = CaptionVeilMid * eased)
                    } else {
                        1f to CoverCaptionScrim.copy(alpha = CaptionVeilFoot)
                    }
                }
                val brush = Brush.verticalGradient(*stops)
                onDrawBehind { drawRect(brush) }
            }
            .padding(start = padding, end = padding, top = CaptionFade, bottom = padding),
    ) {
        Text(
            text = title,
            style = CaptionStyle,
            fontSize = titleSize,
            lineHeight = 1.22.em,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.1).sp,
            color = CoverCaptionInk,
            maxLines = maxTitleLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (!author.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = author,
                style = CaptionStyle,
                fontSize = authorSize,
                lineHeight = 1.25.em,
                color = CoverCaptionInk.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** How far above the first line of text the veil starts fading in. */
private val CaptionFade = 34.dp
private const val CaptionFadeSteps = 8
/** Veil opacity where the text begins, and at the very bottom edge. */
private const val CaptionVeilMid = 0.58f
private const val CaptionVeilFoot = 0.84f

/**
 * A soft shadow under the glyphs: on a bright cover the veil alone leaves the
 * thin strokes of 10sp type fighting the picture behind them.
 */
private val CaptionStyle = TextStyle(
    shadow = Shadow(color = CoverCaptionScrim.copy(alpha = 0.45f), blurRadius = 6f),
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)
