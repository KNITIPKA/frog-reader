package com.example.frogreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.frogreader.data.AppTheme
import com.example.frogreader.ui.theme.FrogReaderTheme
import kotlin.math.roundToInt

// Relative text volumes from the supplied FB2; page counts still come from native pagination.
private val lastWishVolumes = listOf(1565, 52588, 10252, 52673, 8504, 68713, 8156, 73682, 11809, 73725, 11696, 96014, 15406)
private val lastWishStops = lastWishVolumes.runningFold(0, Int::plus).dropLast(1)
    .map { it.toFloat() / lastWishVolumes.sum() }

@Preview(name = "Progress · rectangular reference · 56 of 147", widthDp = 393, heightDp = 64)
@Composable
private fun RectangularProgressReferencePreview() {
    FrogReaderTheme(AppTheme.OLED) {
        val colors = readerColors(AppTheme.OLED)
        val starts = listOf(0, 7, 14, 21, 29, 37, 46, 60, 71, 82, 93, 104, 115, 126, 135, 139, 143)
        Box(Modifier.background(colors.background).padding(horizontal = 12.dp)) {
            Surface(color = colors.chrome, shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp)) {
                    ReaderSegmentedProgressSlider(
                        fraction = 55f / 146f, chapterStops = starts.map { it / 146f }, positionCount = 147,
                        adjusting = false, enabled = true, colors = colors,
                        interactionSource = remember { MutableInteractionSource() },
                        label = "Book progress", progressDescription = "56/147",
                        onValueChange = {}, onValueChangeFinished = {},
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(26.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("4 pages left in chapter", color = colors.onChromeVariant, style = MaterialTheme.typography.labelSmall)
                        Text("56/147", color = colors.onChromeVariant, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Preview(name = "Progress · remaining caption motion states", widthDp = 393, heightDp = 320)
@Composable
private fun RemainingCaptionMotionPreview() = CaptionMotionStates(AppTheme.OLED)

@Preview(name = "Progress · caption motion · Light narrow large text", widthDp = 320, heightDp = 390, fontScale = 1.6f)
@Composable
private fun NarrowCaptionMotionPreview() = CaptionMotionStates(AppTheme.WHITE)

@Preview(name = "Progress · caption motion · Beige", widthDp = 393, heightDp = 320)
@Composable
private fun BeigeCaptionMotionPreview() = CaptionMotionStates(AppTheme.SEPIA)

@Preview(name = "Progress · motion blur · transition frames", widthDp = 393, heightDp = 210)
@Composable
private fun CaptionBlurFramesPreview() = CaptionBlurFrames(AppTheme.OLED)

@Preview(name = "Progress · motion blur · Light narrow large text", widthDp = 320, heightDp = 270, fontScale = 1.6f)
@Composable
private fun NarrowCaptionBlurFramesPreview() = CaptionBlurFrames(AppTheme.WHITE)

@Preview(name = "Progress · motion blur · Beige transition frames", widthDp = 393, heightDp = 210)
@Composable
private fun BeigeCaptionBlurFramesPreview() = CaptionBlurFrames(AppTheme.SEPIA)

@Composable
private fun CaptionBlurFrames(theme: AppTheme) {
    FrogReaderTheme(theme) {
        val colors = readerColors(theme)
        Surface(Modifier.fillMaxSize(), color = colors.chrome) {
            Column(Modifier.padding(16.dp)) {
                for ((phase, blurAmount) in listOf("Чётко" to 0f, "Переход · motion blur" to 0.5f, "Быстрое движение" to 1f)) {
                    Text(phase, style = MaterialTheme.typography.labelLarge, color = colors.onChrome)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        ReaderProgressCaptionLayer(
                            "3 pages left in chapter", "3 pages left in chapter", colors, { blurAmount },
                            Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text("653/1289", color = colors.onChromeVariant, style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum",
                        ))
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/** Static blur states; the live gesture/animation is checked on the phone. */
@Composable
private fun CaptionMotionStates(theme: AppTheme) {
    FrogReaderTheme(theme) {
        val colors = readerColors(theme)
        Surface(Modifier.fillMaxSize(), color = colors.chrome) {
            Column(Modifier.padding(16.dp)) {
                val states = listOf(
                    Triple("Медленное движение", ReaderProgressCaptionPresentation("3 pages left in chapter"), 525),
                    Triple("Быстрое движение", ReaderProgressCaptionPresentation("Last page of the chapter", blurred = true), 653),
                    Triple("Пауза или отпускание", ReaderProgressCaptionPresentation("Last page of the chapter"), 653),
                )
                for ((phase, caption, page) in states) {
                    Text(phase, style = MaterialTheme.typography.labelLarge, color = colors.onChrome)
                    ReaderSegmentedProgressSlider(
                        fraction = (page - 1) / 1288f, chapterStops = List(100) { it / 100f }, positionCount = 1289,
                        adjusting = caption.blurred, enabled = true, colors = colors,
                        interactionSource = remember { MutableInteractionSource() },
                        label = "Book progress", progressDescription = "$page/1289",
                        onValueChange = {}, onValueChangeFinished = {},
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        ReaderProgressCaptionText(caption, caption.text, colors, Modifier.weight(1f, fill = false))
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text("$page/1289", color = colors.onChromeVariant, style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum",
                        ))
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

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

@Preview(name = "Progress · 30 chapters · phone fallback", widthDp = 393, heightDp = 180)
@Composable
private fun ThirtyChapterProgressPreview() = ThirtyChapterFooter()

@Preview(name = "Progress · 30 chapters · wide segmented", widthDp = 760, heightDp = 180)
@Composable
private fun ThirtyChapterLandscapeProgressPreview() = ThirtyChapterFooter()

@Preview(name = "Progress · Light · inset fallback ticks", widthDp = 393, heightDp = 180)
@Composable
private fun LightFallbackTicksPreview() = FallbackTicksFooter(AppTheme.WHITE)

@Preview(name = "Progress · Beige · inset fallback ticks", widthDp = 393, heightDp = 180)
@Composable
private fun BeigeFallbackTicksPreview() = FallbackTicksFooter(AppTheme.SEPIA)

@Preview(name = "Progress · fallback ticks · narrow large text", widthDp = 320, heightDp = 180, fontScale = 1.6f)
@Composable
private fun NarrowFallbackTicksPreview() = FallbackTicksFooter(AppTheme.WHITE)

@Composable
private fun FallbackTicksFooter(theme: AppTheme) = ProgressFooterPreview(
    theme,
    // Representative boundaries for the supplied Light-theme screenshot.
    stops = List(30) {
        when (it) {
            25 -> 250f / 294f
            26 -> 260f / 294f
            else -> (it * 294f / 30f).roundToInt() / 294f
        }
    },
    bookFraction = 252f / 294f,
    bookPageCount = 295,
    chapterLabel = "XVII",
    chapterParents = emptyList(),
)

@Composable
private fun ThirtyChapterFooter() = ProgressFooterPreview(
    AppTheme.OLED,
    // Representative 30-chapter fixture matching the screenshot's page/caption;
    // the actual Frankenstein chapter boundaries depend on native pagination.
    stops = List(30) {
        when (it) {
            15 -> 162f / 314f
            16 -> 170f / 314f
            else -> (it * 314f / 30f).roundToInt() / 314f
        }
    },
    bookFraction = 162f / 314f,
    bookPageCount = 315,
    chapterLabel = "Chapter XIII",
    chapterParents = emptyList(),
)

@Preview(name = "Progress · clustered short chapters · fallback", widthDp = 393, heightDp = 180)
@Composable
private fun ClusteredShortChapterProgressPreview() {
    val volumes = listOf(30, 30) + List(6) { 1 } + List(5) { 30 }
    val stops = volumes.runningFold(0, Int::plus).dropLast(1).map { it.toFloat() / volumes.sum() }
    ProgressFooterPreview(AppTheme.OLED, stops = stops, bookFraction = 63f / 216f, bookPageCount = 217,
        chapterLabel = "Short interlude", chapterParents = emptyList())
}

@Preview(name = "Progress · 100 chapters · continuous fallback", widthDp = 393, heightDp = 180)
@Composable
private fun HundredChapterProgressPreview() = ProgressFooterPreview(
    AppTheme.OLED,
    stops = List(100) { if (it == 17) 210f / 1288f else (it * 1288f / 100f).roundToInt() / 1288f },
    bookFraction = 209f / 1288f,
    bookPageCount = 1289,
    chapterLabel = "2",
    chapterParents = listOf("Часть 1", "Глава 7. Воссоединение"),
)

@Preview(name = "Progress · 100 chapters · landscape drag", widthDp = 760, heightDp = 90)
@Composable
private fun DenseLandscapeDragPreview() = FocusTrackPreview(
    stops = List(100) { it / 100f }, fraction = 209f / 1288f, positionCount = 1289, caption = "100 chapters · drag",
)

@Preview(name = "Progress · 200 chapters · continuous drag edges", widthDp = 320, heightDp = 360)
@Composable
private fun DenseDragEdgesPreview() = DenseEdgeTracks()

@Preview(name = "Progress · 200 chapters · continuous drag edges · RTL", widthDp = 320, heightDp = 360)
@Composable
private fun RtlDenseDragEdgesPreview() = CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    DenseEdgeTracks()
}

@Composable
private fun DenseEdgeTracks() {
    Column {
        for (fraction in listOf(0f, 0.5f, 1f)) {
            FocusTrackPreview(stops = List(200) { it / 200f } + 1f, fraction = fraction,
                positionCount = 1289, caption = "200 chapters · ${(fraction * 100).roundToInt()}%")
        }
    }
}

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

@Preview(name = "Progress · cursor at capsule edges", widthDp = 320, heightDp = 320)
@Composable
private fun CapsuleEdgesPreview() = CapsuleEdgeTracks()

@Preview(name = "Progress · cursor at capsule edges · RTL", widthDp = 320, heightDp = 320)
@Composable
private fun RtlCapsuleEdgesPreview() = CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    CapsuleEdgeTracks()
}

@Composable
private fun CapsuleEdgeTracks() {
    Column {
        FocusTrackPreview(adjusting = false, fraction = 100f / 201f, caption = "First page of a chapter")
        FocusTrackPreview(adjusting = false, fraction = 1f, caption = "Last page of the book")
        FocusTrackPreview(adjusting = false, stops = listOf(0f, 0.6f, 1f), fraction = 1f,
            positionCount = 101, caption = "Final one-page chapter")
    }
}

@Preview(name = "Progress · 38 small chapters · fallback", widthDp = 393, heightDp = 90)
@Composable
private fun ManyShortChaptersPreview() = FocusTrackPreview(
    stops = List(38) { it / 38f }, fraction = 292f / 706f, positionCount = 707, caption = "38 chapters · fallback",
)

@Preview(name = "Progress · fallback after long drag right", widthDp = 393, heightDp = 90)
@Composable
private fun LongDragRightPreview() = LongDragTrackPreview(reverse = false)

@Preview(name = "Progress · fallback after long drag left", widthDp = 393, heightDp = 90)
@Composable
private fun LongDragLeftPreview() = LongDragTrackPreview(reverse = true)

@Preview(name = "Progress · wide segmented after long drag right", widthDp = 760, heightDp = 90)
@Composable
private fun WideLongDragRightPreview() = LongDragTrackPreview(reverse = false)

@Preview(name = "Progress · wide segmented after long drag left", widthDp = 760, heightDp = 90)
@Composable
private fun WideLongDragLeftPreview() = LongDragTrackPreview(reverse = true)

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
                        thumb = { ReaderProgressThumb() },
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
    bookFraction: Float = if (lastWish) 0.4092f else 395f / 706f,
    bookPageCount: Int? = if (lastWish) null else 707,
    chapterLabel: String = if (lastWish) "Глас рассудка IV" else "Розділ 1. Багато зустрічей",
    chapterParents: List<String> = if (lastWish) listOf("Последнее желание") else listOf("Книга друга"),
) {
    FrogReaderTheme(theme) {
        val colors = readerColors(theme)
        Box(Modifier.background(colors.background).padding(16.dp)) {
            Surface(color = colors.chrome, shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    ReaderChapterFooter(
                        chapterLabel = chapterLabel,
                        chapterParents = chapterParents,
                        bookFraction = bookFraction,
                        bookPageCount = bookPageCount,
                        chapterStartPages = bookPageCount?.let { count -> stops.map { (it * (count - 1)).roundToInt() } },
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
