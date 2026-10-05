package com.example.frogreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.frogreader.data.AppTheme
import com.example.frogreader.ui.theme.FrogReaderTheme
import kotlin.math.roundToInt

// Relative text volumes from the supplied FB2; page counts still come from native pagination.
private val lastWishVolumes = listOf(1565, 52588, 10252, 52673, 8504, 68713, 8156, 73682, 11809, 73725, 11696, 96014, 15406)
private val lastWishStops = lastWishVolumes.runningFold(0, Int::plus).dropLast(1)
    .map { it.toFloat() / lastWishVolumes.sum() }

@Preview(name = "Progress · selected mock", widthDp = 393, heightDp = 180)
@Composable
private fun SegmentedReaderProgressPreview() = ProgressFooterPreview(AppTheme.OLED)

@Preview(name = "Progress · Last Wish · short interludes", widthDp = 393, heightDp = 180)
@Composable
private fun LastWishReaderProgressPreview() = ProgressFooterPreview(AppTheme.OLED, stops = lastWishStops, lastWish = true)

@Preview(name = "Progress · Light · narrow large text", widthDp = 320, heightDp = 180, fontScale = 1.6f)
@Composable
private fun NarrowReaderProgressPreview() = ProgressFooterPreview(AppTheme.WHITE, stops = lastWishStops, lastWish = true)

@Preview(name = "Progress · Beige", widthDp = 393, heightDp = 180)
@Composable
private fun BeigeReaderProgressPreview() = ProgressFooterPreview(AppTheme.SEPIA)

@Preview(name = "Progress · landscape", widthDp = 760, heightDp = 180)
@Composable
private fun LandscapeReaderProgressPreview() = ProgressFooterPreview(AppTheme.OLED, stops = lastWishStops, lastWish = true)

@Preview(name = "Progress · 200 chapters", widthDp = 393, heightDp = 180)
@Composable
private fun DenseReaderProgressPreview() = ProgressFooterPreview(AppTheme.OLED, stops = List(200) { it / 200f })

@Preview(name = "Progress · two page interlude · expanded", widthDp = 320, heightDp = 90)
@Composable
private fun ExpandedReaderProgressPreview() = FocusTrackPreview()

@Preview(name = "Progress · short chapter · before and during drag", widthDp = 320, heightDp = 190)
@Composable
private fun ExpansionComparisonPreview() {
    Column {
        FocusTrackPreview(adjusting = false, caption = "Before drag")
        FocusTrackPreview(adjusting = true, caption = "During drag")
    }
}

@Preview(name = "Progress · many small chapters · expanded", widthDp = 393, heightDp = 90)
@Composable
private fun ManyShortChaptersPreview() = FocusTrackPreview(
    stops = List(38) { it / 38f }, fraction = 292f / 706f, positionCount = 707, caption = "Many short chapters",
)

@Preview(name = "Progress · after long drag right", widthDp = 393, heightDp = 90)
@Composable
private fun LongDragRightPreview() = LongDragTrackPreview(reverse = false)

@Preview(name = "Progress · after long drag left", widthDp = 393, heightDp = 90)
@Composable
private fun LongDragLeftPreview() = LongDragTrackPreview(reverse = true)

/** End state of an uninterrupted gesture, rather than starting directly on a chapter. */
@Composable
private fun LongDragTrackPreview(reverse: Boolean) {
    FrogReaderTheme(AppTheme.OLED) {
        val colors = readerColors(AppTheme.OLED)
        Surface(color = colors.chrome) {
            Column(Modifier.padding(16.dp)) {
                Text(if (reverse) "Long drag ←" else "Long drag →", color = colors.onChrome, style = MaterialTheme.typography.labelLarge)
                BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    val width = (maxWidth - 24.dp).value
                    val (book, layout) = remember(width, reverse) {
                        val starts = List(38) { (it * 706f / 38f).roundToInt() }
                        val base = ReaderProgressLayout(starts.map { it / 706f }, width, 707)
                        var fraction = if (reverse) 1f else 0f
                        var current = base.focused(fraction, true)
                        var anchor: ReaderProgressAnchor? = null
                        val positions = if (reverse) width.toInt() downTo (width * 0.18f).toInt()
                            else 0..(width * 0.82f).toInt()
                        for (pixel in positions) {
                            val finger = pixel / width
                            fraction = current.bookFraction(finger)
                            val selected = base.chapters.indexAt(fraction)
                            if (anchor?.chapterIndex != selected) anchor = ReaderProgressAnchor(selected, fraction, finger)
                            current = base.focused(fraction, true, anchor)
                        }
                        fraction to current
                    }
                    Slider(
                        value = layout.trackFraction(book), onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        thumb = { ReaderProgressThumb(colors, true) },
                        track = { ReaderSegmentedProgressTrack(book, layout, true, true, colors) },
                    )
                }
            }
        }
    }
}

@Preview(name = "Progress · expanded · RTL", widthDp = 320, heightDp = 90)
@Composable
private fun RtlExpandedReaderProgressPreview() = CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    FocusTrackPreview()
}

@Composable
private fun ProgressFooterPreview(
    theme: AppTheme,
    stops: List<Float> = listOf(0f, 0.045f, 0.12f, 0.225f, 0.30f, 0.445f, 0.49f, 408f / 706f, 0.67f, 0.73f, 0.9f),
    lastWish: Boolean = false,
) {
    FrogReaderTheme(theme) {
        val colors = readerColors(theme)
        Box(Modifier.background(colors.background).padding(16.dp)) {
            Surface(color = colors.chrome, shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    ReaderChapterFooter(
                        chapterLabel = if (lastWish) "Глас рассудка IV" else "Розділ 1. Багато зустрічей",
                        chapterParents = if (lastWish) listOf("Последнее желание") else listOf("Книга друга"),
                        bookFraction = if (lastWish) 0.4092f else 395f / 706f,
                        bookPageCount = if (lastWish) null else 707,
                        chapterStartPages = if (lastWish) null else stops.map { (it * 706).roundToInt() },
                        chapterStops = stops,
                        progressValuesReady = true,
                        colors = colors,
                        contentsSelected = false,
                        settingsSelected = false,
                        onContents = {}, onSettings = {}, onSeekBook = { false },
                    )
                }
            }
        }
    }
}

@Composable
private fun FocusTrackPreview(
    adjusting: Boolean = true,
    stops: List<Float> = listOf(0f, 100f / 201f, 102f / 201f),
    fraction: Float = 101f / 201f,
    positionCount: Int = 202,
    caption: String = "100 → 2 → 100",
) {
    FrogReaderTheme(AppTheme.OLED) {
        val colors = readerColors(AppTheme.OLED)
        Surface(color = colors.chrome) {
            Column(Modifier.padding(16.dp)) {
                Text(caption, color = colors.onChrome, style = MaterialTheme.typography.labelLarge)
                ReaderSegmentedProgressSlider(
                    fraction = fraction,
                    chapterStops = stops,
                    positionCount = positionCount,
                    adjusting = adjusting,
                    enabled = true,
                    colors = colors,
                    interactionSource = remember { MutableInteractionSource() },
                    label = "Book progress",
                    progressDescription = "101/202",
                    onValueChange = {},
                    onValueChangeFinished = {},
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}
