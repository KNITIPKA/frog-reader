package com.example.frogreader.ui.reader

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.INLINE_IMAGE_TAG
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

internal const val INLINE_IMAGE_HEIGHT_EM = 0.9f
internal const val MAX_READER_IMAGE_DECODE_PX = 960
private const val PREFETCH_AHEAD_IMAGE_LIMIT = 2
private const val PREFETCH_BEHIND_IMAGE_LIMIT = 1
private const val PREFETCH_SCROLL_ITEM_WINDOW = 20
private const val SCROLL_ACTIVE_PREFETCH_DEBOUNCE_MS = 140L
private const val SCROLL_IDLE_PREFETCH_DEBOUNCE_MS = 32L

internal data class ReaderImageTarget(
    val path: String,
    val widthPx: Int,
    val heightPx: Int,
) {
    val memoryCacheKey: String
        get() = readerImageMemoryCacheKey(path, widthPx, heightPx)
}

/**
 * Caps the decoded bitmap dimensions to [maxDimensionPx] while preserving the
 * exact aspect ratio. On high-density displays (1080p/1440p), capping raster
 * dimensions at 960px cuts software SVG rasterization work, RAM footprint, and
 * `RenderThread` GPU texture upload size by ~3x while `ContentScale.Fit`
 * renders the illustration at full visual sharpness.
 */
internal fun readerImageDecodeSize(
    widthPx: Int,
    heightPx: Int,
    maxDimensionPx: Int = MAX_READER_IMAGE_DECODE_PX,
): Pair<Int, Int> {
    val w = widthPx.coerceAtLeast(1)
    val h = heightPx.coerceAtLeast(1)
    val limit = maxDimensionPx.coerceAtLeast(1)
    val largest = maxOf(w, h)
    if (largest <= limit) return w to h
    val scale = limit.toFloat() / largest.toFloat()
    return (w * scale).roundToInt().coerceAtLeast(1) to
        (h * scale).roundToInt().coerceAtLeast(1)
}

/**
 * Stable memory-cache key for a book image at its decoded pixel box.
 * Passing an explicit key bypasses Coil's `FileUriKeyer`, which otherwise
 * executes a synchronous disk `stat()` (`lastModifiedAtMillis`) on the main
 * thread before every `MemoryCache` lookup.
 */
internal fun readerImageMemoryCacheKey(
    path: String,
    widthPx: Int,
    heightPx: Int,
): String {
    val (w, h) = readerImageDecodeSize(widthPx, heightPx)
    return "$path@${w}x$h"
}

/**
 * Builds a pre-sized [ImageRequest] shared by both `AsyncImage` and the
 * background prefetcher.
 *
 * Providing an explicit `.size(w, h)` sets a non-suspending `RealSizeResolver`
 * on the request. Without it, `AsyncImage` wraps the model in a
 * `ConstraintsSizeResolver` that suspends until the layout pass, preventing
 * `AsyncImagePainter` from drawing a `MemoryCache` hit synchronously on the
 * very first frame.
 */
internal fun buildReaderImageRequest(
    context: PlatformContext,
    path: String,
    widthPx: Int,
    heightPx: Int,
): ImageRequest {
    val (w, h) = readerImageDecodeSize(widthPx, heightPx)
    return ImageRequest.Builder(context)
        .data(File(path))
        .memoryCacheKey("$path@${w}x$h")
        .size(w, h)
        .scale(Scale.FIT)
        .precision(Precision.INEXACT)
        .build()
}

@Composable
internal fun rememberReaderImageRequest(
    path: String,
    widthPx: Int,
    heightPx: Int,
): ImageRequest {
    val context = LocalContext.current
    val (w, h) = readerImageDecodeSize(widthPx, heightPx)
    return remember(context, path, w, h) {
        buildReaderImageRequest(context, path, w, h)
    }
}

/** Height of a float image rendered as a plain full-width block (publisher formatting off). */
internal fun plainFloatImageHeightPx(
    path: String,
    contentWidthPx: Int,
    maxHeightPx: Int,
): Int {
    val aspect = imageAspectRatio(path) ?: 1.4f
    return min(
        (contentWidthPx.coerceAtLeast(1) * aspect).roundToInt(),
        maxHeightPx.coerceAtLeast(1),
    ).coerceAtLeast(1)
}

/** Target pixel dimensions for an inline `0.9em` image inside text flow. */
internal fun inlineImageDimensionsPx(
    path: String,
    fontSizePx: Float,
): Pair<Int, Int> {
    val aspect = (imageAspectRatio(path) ?: 1f).coerceAtLeast(0.05f)
    val heightPx = (fontSizePx.coerceAtLeast(1f) * INLINE_IMAGE_HEIGHT_EM).roundToInt().coerceAtLeast(1)
    val widthPx = (heightPx / aspect).roundToInt().coerceAtLeast(1)
    return widthPx to heightPx
}

/** Extracts all raw image file paths referenced by [item] without measuring layout. */
internal fun readerItemImagePaths(item: ReaderItem): List<String> {
    val result = ArrayList<String>(2)
    fun collectInline(text: AnnotatedString) {
        val marks = text.getStringAnnotations(INLINE_IMAGE_TAG, 0, text.length)
        for (mark in marks) {
            if (mark.item.isNotEmpty()) result += mark.item
        }
    }
    when (val element = item.element) {
        is ContentElement.Image -> if (element.path.isNotEmpty()) result += element.path
        is ContentElement.Paragraph -> {
            element.block?.floatImage?.path?.takeIf { it.isNotEmpty() }?.let { result += it }
            item.publisherFloat?.contents?.forEach { content ->
                val leaf = content.element
                if (leaf is ContentElement.Image && leaf.path.isNotEmpty()) {
                    result += leaf.path
                } else if (leaf is ContentElement.Paragraph) {
                    collectInline(leaf.text)
                } else if (leaf is ContentElement.Heading) {
                    collectInline(leaf.styledText)
                }
            }
            collectInline(element.text)
        }
        is ContentElement.Heading -> collectInline(element.styledText)
        is ContentElement.Table -> {
            for (row in element.rows) {
                for (cell in row.cells) {
                    collectInline(cell.text)
                }
            }
        }
        ContentElement.Divider, is ContentElement.Spacer -> Unit
    }
    return result
}

/**
 * Computes the exact [ReaderImageTarget]s that [RenderPart] will request when
 * [item] is composed in scrolling mode.
 */
internal fun readerItemImageTargets(
    item: ReaderItem,
    columnWidthPx: Int,
    viewportHeightPx: Int?,
    density: Density,
    settings: ReaderSettings,
    fontSize: Float,
): List<ReaderImageTarget> = with(density) {
    if (item.suppressedByPublisherFloat && settings.bookStyles) return emptyList()
    val rawPaths = readerItemImagePaths(item)
    if (rawPaths.isEmpty()) return emptyList()

    val targets = ArrayList<ReaderImageTarget>(rawPaths.size)
    val fontSizePx = fontSize.sp.toPx()
    val maxImageHeightPx = ReaderMetrics.maxImageHeight.roundToPx()
    val boxGeometry = publisherBoxGeometry(
        boxes = item.publisherBoxes,
        columnWidthPx = columnWidthPx,
        fontSizePx = fontSizePx,
        partStartsElement = true,
        partEndsElement = true,
        enabled = settings.bookStyles,
        viewportHeightPx = viewportHeightPx,
    )
    val boxContentWidthDp = boxGeometry.contentWidthPx.toDp()
    val (startInset, endInset) = ReaderMetrics.horizontalInsets(item.element, boxContentWidthDp, fontSize)
    val exactTextWidthPx = (boxGeometry.contentWidthPx - startInset.roundToPx() - endInset.roundToPx())
        .coerceAtLeast(1)

    fun collectInlineTargets(text: AnnotatedString, textFontSizePx: Float) {
        val marks = text.getStringAnnotations(INLINE_IMAGE_TAG, 0, text.length)
        for (mark in marks) {
            if (mark.item.isNotEmpty()) {
                val (w, h) = inlineImageDimensionsPx(mark.item, textFontSizePx)
                targets += ReaderImageTarget(mark.item, w, h)
            }
        }
    }

    when (val element = item.element) {
        is ContentElement.Image -> {
            val publisherOwnsWidth = publisherBoxesOwnImageWidth(item.publisherBoxes, settings.bookStyles)
            val widthPx = imageWidthPx(
                element = element,
                contentWidthPx = exactTextWidthPx,
                fontSizePx = fontSizePx,
                publisherOwnsWidth = publisherOwnsWidth,
            )
            val heightPx = imageHeightPx(
                element = element,
                contentWidthPx = exactTextWidthPx,
                fontSizePx = fontSizePx,
                maxHeightPx = maxImageHeightPx,
                publisherOwnsWidth = publisherOwnsWidth,
                viewportHeightPx = viewportHeightPx.takeIf { settings.bookStyles },
            )
            targets += ReaderImageTarget(element.path, widthPx, heightPx)
        }

        is ContentElement.Paragraph -> {
            val floatImage = element.block?.floatImage
            if (floatImage != null) {
                if (!settings.bookStyles) {
                    val heightPx = plainFloatImageHeightPx(
                        path = floatImage.path,
                        contentWidthPx = exactTextWidthPx,
                        maxHeightPx = maxImageHeightPx,
                    )
                    targets += ReaderImageTarget(floatImage.path, exactTextWidthPx, heightPx)
                } else if (item.publisherFloat == null) {
                    val aspect = imageAspectRatio(floatImage.path)
                    if (aspect != null) {
                        val boxW = (exactTextWidthPx * floatImage.widthFrac.coerceIn(0.1f, 0.45f)).roundToInt()
                        val boxH = (boxW * aspect).roundToInt()
                            .coerceAtMost((exactTextWidthPx * 0.9f).roundToInt())
                        if (boxW > 0 && boxH > 0) {
                            targets += ReaderImageTarget(floatImage.path, boxW, boxH)
                        }
                    }
                }
            }
            val publisherFloat = item.publisherFloat?.takeIf { settings.bookStyles }
            if (publisherFloat != null) {
                targets += publisherFloatImageTargets(
                    publisherFloat = publisherFloat,
                    containingWidthPx = exactTextWidthPx,
                    viewportHeightPx = viewportHeightPx,
                    density = density,
                    settings = settings,
                    fontSize = fontSize,
                )
            }
            val scale = element.block?.fontScale ?: 1f
            collectInlineTargets(element.text, fontSizePx * scale)
        }

        is ContentElement.Heading -> {
            val scale = element.block?.fontScale ?: ReaderMetrics.headingScale(element.level)
            collectInlineTargets(element.styledText, fontSizePx * scale)
        }

        is ContentElement.Table -> {
            for (row in element.rows) {
                for (cell in row.cells) {
                    collectInlineTargets(cell.text, fontSizePx * 0.92f)
                }
            }
        }

        ContentElement.Divider, is ContentElement.Spacer -> Unit
    }
    targets
}

private fun publisherFloatImageTargets(
    publisherFloat: ReaderPublisherFloat,
    containingWidthPx: Int,
    viewportHeightPx: Int?,
    density: Density,
    settings: ReaderSettings,
    fontSize: Float,
): List<ReaderImageTarget> = with(density) {
    if (publisherFloat.contents.isEmpty() || containingWidthPx <= 0) return emptyList()
    val style = publisherFloat.style
    val fontPx = fontSize.sp.toPx()
    val horizontalFrameExtra =
        (style.paddingLeftEm + style.paddingRightEm).coerceAtLeast(0f) * fontPx +
            (style.paddingLeftFrac + style.paddingRightFrac).coerceIn(0f, 0.45f) * containingWidthPx +
            listOf(style.borderLeft, style.borderRight).sumOf { side ->
                side?.widthEm?.coerceAtLeast(0f)?.times(fontPx)?.toDouble() ?: 0.0
            }.toFloat()
    val requestedOuterWidth = when {
        style.widthFrac != null ->
            containingWidthPx * style.widthFrac.coerceIn(0.05f, 1f) + horizontalFrameExtra
        style.widthEm != null ->
            style.widthEm.coerceAtLeast(0.05f) * fontPx + horizontalFrameExtra
        else -> containingWidthPx * 0.34f
    }
    val maximumOuterWidth = (containingWidthPx * 0.48f).roundToInt().coerceAtLeast(1)
    val minimumOuterWidth = (fontPx * 5f).roundToInt().coerceAtMost(maximumOuterWidth).coerceAtLeast(1)
    val outerWidth = requestedOuterWidth.roundToInt().coerceIn(minimumOuterWidth, maximumOuterWidth)
    if (outerWidth >= containingWidthPx) return emptyList()

    val frame = ReaderPublisherBox(
        id = "publisher-float-frame",
        parentId = null,
        style = style.copy(
            marginLeftEm = 0f,
            marginLeftFrac = 0f,
            marginRightEm = 0f,
            marginRightFrac = 0f,
            widthFrac = null,
            widthEm = null,
        ),
        floatSide = publisherFloat.side,
        startsAtElement = true,
        endsAtElement = true,
    )
    val frameGeometry = publisherBoxGeometry(
        boxes = listOf(frame),
        columnWidthPx = outerWidth,
        fontSizePx = fontPx,
        partStartsElement = true,
        partEndsElement = true,
        enabled = true,
    )
    val innerWidth = frameGeometry.contentWidthPx.coerceAtLeast(1)
    val maxImageHeightPx = ReaderMetrics.maxImageHeight.roundToPx()
    val result = ArrayList<ReaderImageTarget>(1)
    for (content in publisherFloat.contents) {
        val element = content.element as? ContentElement.Image ?: continue
        val geometry = publisherBoxGeometry(
            boxes = content.publisherBoxes,
            columnWidthPx = innerWidth,
            fontSizePx = fontPx,
            partStartsElement = true,
            partEndsElement = true,
            enabled = true,
        )
        val contentWidthDp = geometry.contentWidthPx.toDp()
        val (startInset, endInset) = ReaderMetrics.horizontalInsets(element, contentWidthDp, fontSize)
        val exactWidthPx = (geometry.contentWidthPx - startInset.roundToPx() - endInset.roundToPx())
            .coerceAtLeast(1)
        val ownsWidth = publisherBoxesOwnImageWidth(content.publisherBoxes, enabled = true) ||
            (style.widthFrac != null || style.widthEm != null)
        val widthPx = imageWidthPx(
            element = element,
            contentWidthPx = exactWidthPx,
            fontSizePx = fontPx,
            publisherOwnsWidth = ownsWidth,
        )
        val heightPx = imageHeightPx(
            element = element,
            contentWidthPx = geometry.contentWidthPx,
            fontSizePx = fontPx,
            maxHeightPx = maxImageHeightPx,
            viewportHeightPx = viewportHeightPx,
            publisherOwnsWidth = ownsWidth,
        )
        result += ReaderImageTarget(element.path, widthPx, heightPx)
    }
    result
}

/**
 * Computes the exact [ReaderImageTarget]s that [PageView] will render on [page].
 */
internal fun bookPageImageTargets(
    page: BookPage,
    columnWidthPx: Int,
    viewportHeightPx: Int?,
    density: Density,
    settings: ReaderSettings,
    fontSize: Float,
): List<ReaderImageTarget> = with(density) {
    val targets = ArrayList<ReaderImageTarget>(2)
    val fontSizePx = fontSize.sp.toPx()
    val maxImageHeightPx = ReaderMetrics.maxImageHeight.roundToPx()

    fun collectInlineTargets(text: AnnotatedString, textFontSizePx: Float) {
        val marks = text.getStringAnnotations(INLINE_IMAGE_TAG, 0, text.length)
        for (mark in marks) {
            if (mark.item.isNotEmpty()) {
                val (w, h) = inlineImageDimensionsPx(mark.item, textFontSizePx)
                targets += ReaderImageTarget(mark.item, w, h)
            }
        }
    }

    for (part in page.parts) {
        val boxGeometry = publisherBoxGeometry(
            boxes = part.publisherBoxes,
            columnWidthPx = columnWidthPx,
            fontSizePx = fontSizePx,
            partStartsElement = part.publisherStartsElement,
            partEndsElement = part.publisherEndsElement,
            enabled = settings.bookStyles,
            viewportHeightPx = viewportHeightPx,
        )
        val boxContentWidthDp = boxGeometry.contentWidthPx.toDp()
        val (startInset, endInset) = ReaderMetrics.horizontalInsets(part.element, boxContentWidthDp, fontSize)
        val exactTextWidthPx = (boxGeometry.contentWidthPx - startInset.roundToPx() - endInset.roundToPx())
            .coerceAtLeast(1)

        if (part.floatImagePath != null) {
            val h = part.imageHeightPx ?: plainFloatImageHeightPx(
                path = part.floatImagePath,
                contentWidthPx = columnWidthPx,
                maxHeightPx = maxImageHeightPx,
            )
            targets += ReaderImageTarget(part.floatImagePath, columnWidthPx, h)
            continue
        }

        val sideBox = part.sideBox
        if (sideBox != null) {
            if (sideBox.imagePath != null) {
                targets += ReaderImageTarget(
                    path = sideBox.imagePath,
                    widthPx = sideBox.boxWidthPx.coerceAtLeast(1),
                    heightPx = sideBox.boxHeightPx.coerceAtLeast(1),
                )
            } else if (sideBox.publisherFloat != null) {
                targets += publisherFloatImageTargets(
                    publisherFloat = sideBox.publisherFloat,
                    containingWidthPx = exactTextWidthPx,
                    viewportHeightPx = viewportHeightPx,
                    density = density,
                    settings = settings,
                    fontSize = fontSize,
                )
            }
        }

        when (val element = part.element) {
            is ContentElement.Image -> {
                val publisherOwnsWidth = publisherBoxesOwnImageWidth(part.publisherBoxes, settings.bookStyles)
                val w = imageWidthPx(
                    element = element,
                    contentWidthPx = exactTextWidthPx,
                    fontSizePx = fontSizePx,
                    publisherOwnsWidth = publisherOwnsWidth,
                )
                val h = part.imageHeightPx ?: imageHeightPx(
                    element = element,
                    contentWidthPx = exactTextWidthPx,
                    fontSizePx = fontSizePx,
                    maxHeightPx = maxImageHeightPx,
                    publisherOwnsWidth = publisherOwnsWidth,
                    viewportHeightPx = viewportHeightPx.takeIf { settings.bookStyles },
                )
                targets += ReaderImageTarget(element.path, w, h)
            }

            is ContentElement.Paragraph -> {
                val scale = element.block?.fontScale ?: 1f
                collectInlineTargets(part.text ?: element.text, fontSizePx * scale)
            }

            is ContentElement.Heading -> {
                val scale = element.block?.fontScale ?: ReaderMetrics.headingScale(element.level)
                collectInlineTargets(part.text ?: element.styledText, fontSizePx * scale)
            }

            is ContentElement.Table -> {
                val start = part.rowStart.coerceAtLeast(0)
                val end = if (part.rowEnd >= 0) part.rowEnd.coerceAtMost(element.rows.size) else element.rows.size
                for (r in start until end) {
                    for (cell in element.rows[r].cells) {
                        collectInlineTargets(cell.text, fontSizePx * 0.92f)
                    }
                }
            }

            ContentElement.Divider, is ContentElement.Spacer -> Unit
        }
    }
    targets
}

/**
 * Selects which item indices around `[firstVisible..lastVisible]` should be
 * scanned for image prefetching, ordered from closest to farthest, with the
 * active scroll direction prioritized first at each step.
 */
internal fun selectScrollPrefetchIndices(
    itemCount: Int,
    firstVisible: Int,
    lastVisible: Int,
    scrollingBackward: Boolean,
    windowSize: Int = PREFETCH_SCROLL_ITEM_WINDOW,
): List<Int> {
    if (itemCount <= 0 || windowSize <= 0) return emptyList()
    val clampedFirst = firstVisible.coerceIn(0, itemCount - 1)
    val clampedLast = lastVisible.coerceIn(clampedFirst, itemCount - 1)
    val result = ArrayList<Int>(windowSize * 2)
    for (step in 1..windowSize) {
        val ahead = clampedLast + step
        val behind = clampedFirst - step
        if (scrollingBackward) {
            if (behind >= 0) result += behind
            if (ahead < itemCount) result += ahead
        } else {
            if (ahead < itemCount) result += ahead
            if (behind >= 0) result += behind
        }
    }
    return result
}

/**
 * Executes a single prefetch target sequentially on the caller's background
 * coroutine, skipping work if the target is already in Coil's `MemoryCache`.
 */
private suspend fun executePrefetchTarget(
    context: PlatformContext,
    target: ReaderImageTarget,
) {
    if (target.path.isEmpty() || target.widthPx <= 0 || target.heightPx <= 0) return
    val imageLoader = SingletonImageLoader.get(context)
    val key = MemoryCache.Key(target.memoryCacheKey)
    if (imageLoader.memoryCache?.get(key) != null) return
    imageLoader.execute(
        buildReaderImageRequest(
            context = context,
            path = target.path,
            widthPx = target.widthPx,
            heightPx = target.heightPx,
        ),
    )
}

/**
 * Low-impact background image geometry pre-warmer and sequential bitmap
 * prefetcher for [ScrollReader] and [LinkedDocumentOverlay].
 *
 * Uses `collectLatest` with scroll-aware debouncing and executes requests one
 * by one on [SvgRasterDiskCache.svgWorkerDispatcher] so active scrolling is
 * never contended by parallel XML/bitmap decoding jobs.
 */
@Composable
internal fun ReaderScrollImagePrefetchEffect(
    items: List<ReaderItem>,
    listState: LazyListState,
    settings: ReaderSettings,
    fontSize: Float,
    contentWidth: Dp,
    viewportHeight: Dp?,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val columnWidthPx = with(density) { contentWidth.roundToPx() }
    val viewportHeightPx = with(density) { viewportHeight?.roundToPx() }
    if (columnWidthPx <= 0 || items.isEmpty()) return

    LaunchedEffect(items, listState, settings, fontSize, columnWidthPx, viewportHeightPx) {
        val warmedPaths = HashSet<String>()
        var previousFirst = listState.firstVisibleItemIndex
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            val first = visible.firstOrNull()?.index ?: listState.firstVisibleItemIndex
            val last = visible.lastOrNull()?.index ?: first
            Triple(first, last, listState.isScrollInProgress)
        }
            .distinctUntilChanged()
            .collectLatest { (first, last, scrolling) ->
                val scrollingBackward = first < previousFirst
                previousFirst = first
                // Wait for fast flings/drags to settle before decoding ahead so
                // UI composition and RenderThread have 100% of CPU bandwidth.
                delay(if (scrolling) SCROLL_ACTIVE_PREFETCH_DEBOUNCE_MS else SCROLL_IDLE_PREFETCH_DEBOUNCE_MS)

                withContext(SvgRasterDiskCache.svgWorkerDispatcher) {
                    val clampedFirst = first.coerceIn(0, items.lastIndex)
                    val clampedLast = last.coerceIn(clampedFirst, items.lastIndex)

                    // 1. Warm geometry once per unique image path in the local window.
                    val geomStart = (clampedFirst - PREFETCH_SCROLL_ITEM_WINDOW).coerceAtLeast(0)
                    val geomEnd = (clampedLast + PREFETCH_SCROLL_ITEM_WINDOW).coerceAtMost(items.lastIndex)
                    for (idx in geomStart..geomEnd) {
                        for (path in readerItemImagePaths(items[idx])) {
                            if (warmedPaths.add(path)) {
                                imageAspectRatio(path)
                            }
                        }
                    }

                    // 2. Collect closest ahead/behind targets and decode them one by one.
                    val primaryLimit = PREFETCH_AHEAD_IMAGE_LIMIT
                    val secondaryLimit = PREFETCH_BEHIND_IMAGE_LIMIT
                    var aheadCount = 0
                    var behindCount = 0
                    val aheadMax = if (scrollingBackward) secondaryLimit else primaryLimit
                    val behindMax = if (scrollingBackward) primaryLimit else secondaryLimit

                    val indices = selectScrollPrefetchIndices(
                        itemCount = items.size,
                        firstVisible = clampedFirst,
                        lastVisible = clampedLast,
                        scrollingBackward = scrollingBackward,
                    )
                    for (idx in indices) {
                        val isBehind = idx < clampedFirst
                        if (isBehind && behindCount >= behindMax) continue
                        if (!isBehind && aheadCount >= aheadMax) continue
                        val targets = readerItemImageTargets(
                            item = items[idx],
                            columnWidthPx = columnWidthPx,
                            viewportHeightPx = viewportHeightPx,
                            density = density,
                            settings = settings,
                            fontSize = fontSize,
                        )
                        if (targets.isEmpty()) continue
                        for (target in targets) {
                            executePrefetchTarget(context, target)
                        }
                        if (isBehind) behindCount += targets.size else aheadCount += targets.size
                        if (aheadCount >= aheadMax && behindCount >= behindMax) break
                    }
                }
            }
    }
}

/**
 * Sequential page-turn image prefetcher for [PagedReader].
 */
@Composable
internal fun ReaderPagedImagePrefetchEffect(
    pages: List<BookPage>,
    pagerState: PagerState,
    settings: ReaderSettings,
    contentWidth: Dp,
    viewportHeightPx: Int,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val columnWidthPx = with(density) { contentWidth.roundToPx() }
    if (columnWidthPx <= 0 || pages.isEmpty()) return

    LaunchedEffect(pages, pagerState, settings, columnWidthPx, viewportHeightPx) {
        snapshotFlow { pagerState.currentPage to pagerState.isScrollInProgress }
            .distinctUntilChanged()
            .collectLatest { (currentPage, scrolling) ->
                if (scrolling) delay(SCROLL_ACTIVE_PREFETCH_DEBOUNCE_MS)
                withContext(SvgRasterDiskCache.svgWorkerDispatcher) {
                    val center = currentPage.coerceIn(0, pages.lastIndex)
                    val candidatePages = listOf(
                        center + 1,
                        center - 1,
                        center + 2,
                        center - 2,
                    ).filter { it in pages.indices }.distinct()

                    var prefetched = 0
                    for (pageIndex in candidatePages) {
                        val targets = bookPageImageTargets(
                            page = pages[pageIndex],
                            columnWidthPx = columnWidthPx,
                            viewportHeightPx = viewportHeightPx,
                            density = density,
                            settings = settings,
                            fontSize = settings.fontSizeSp,
                        )
                        for (target in targets) {
                            executePrefetchTarget(context, target)
                            prefetched++
                            if (prefetched >= PREFETCH_AHEAD_IMAGE_LIMIT + PREFETCH_BEHIND_IMAGE_LIMIT) return@withContext
                        }
                    }
                }
            }
    }
}
