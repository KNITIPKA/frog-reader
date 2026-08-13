package com.example.frogreader.ui.reader.selection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.text.TextLayoutResult
import com.example.frogreader.data.model.ContentElement

/**
 * One rendered `Text` and the slice of the book it draws.
 *
 * Paged mode splits an element across pages, so a fragment is usually a
 * *piece* of an element: [charStart] is where that piece begins inside the
 * element's own text, which is what turns a position inside this `Text` into a
 * book anchor and back.
 */
@Stable
class TextFragment(
    val itemIndex: Int,
    val charStart: Int,
    val length: Int,
    /**
     * Which generation of the layout this fragment belongs to. The pager is
     * rebuilt wholesale on every committed pinch step (`key(current.key, …)`),
     * so for one frame the old and the new subtree are both registered; the
     * newer epoch wins the hit test.
     */
    val epoch: Int,
) {
    /**
     * State, not a plain field: the highlight is painted from a `drawBehind`
     * that reads this, so the first layout result repaints the fragment
     * without recomposing anything.
     */
    var layout: TextLayoutResult? by mutableStateOf(null)

    /** Where this fragment sits on screen. Null until it is placed. */
    var coords: LayoutCoordinates? = null

    private var cacheOwner: TextLayoutResult? = null
    private val paths = HashMap<CharSpan, Path>(4)

    /**
     * The outline of [span] inside this fragment, cached — during a drag only
     * the fragment under the finger changes shape, and re-tracing every
     * paragraph's path each frame is exactly the cost this design exists to
     * avoid.
     */
    fun pathFor(layout: TextLayoutResult, span: CharSpan): Path {
        if (cacheOwner !== layout) {
            cacheOwner = layout
            paths.clear()
        }
        return paths.getOrPut(span) { layout.getPathForRange(span.start, span.end) }
    }

    /** True while this fragment holds [anchor]'s character. */
    fun holds(anchor: BookAnchor): Boolean =
        anchor.itemIndex == itemIndex &&
            anchor.charOffset >= charStart &&
            anchor.charOffset <= charStart + length
}

/**
 * Everything the reader needs to know about the current selection: what is
 * selected (in book coordinates), which fragments are on screen right now, and
 * how to get from a finger position to a character and back.
 *
 * Owned by `ReaderContent`, above both reading modes, so a page turn, a scroll
 * or a re-pagination never disturbs it.
 */
@Stable
class SelectionController {

    /**
     * Read from draw lambdas and pointer coroutines, so a drag repaints the
     * page without recomposing it.
     */
    var selection by mutableStateOf<BookSelection?>(null)
        private set

    /** True while handles and the action pill should be on screen. */
    var active by mutableStateOf(false)
        private set

    /** True while a finger is moving an edge — the pill hides, the page follows. */
    var dragging by mutableStateOf(false)
        internal set

    /**
     * The element texts, for word boundaries. Set by `ReaderContent` whenever
     * the book changes.
     */
    var textAt: (Int) -> String? = { null }

    /** The coordinate space every position in this class is expressed in. */
    internal var space: LayoutCoordinates? = null

    /**
     * Where the dragging finger was last seen. The auto-advance loop re-reads
     * the character under it after every page turn — the finger has not moved,
     * but the text beneath it has.
     */
    internal var lastPoint: Offset? = null

    /** How the current drag extends the selection (by word, or by character). */
    internal var extend: ((Offset) -> Unit)? = null

    /** Set by whichever reading mode is on screen. */
    var advance: SelectionAutoAdvance? = null

    /**
     * Whether the page is moving right now — a swipe, a fling, a page turn.
     * The action pill rides along with the text while it is, instead of being
     * held on screen.
     */
    var surfaceMoving: () -> Boolean = { false }

    internal val spaceSize: androidx.compose.ui.unit.IntSize?
        get() = liveSpace()?.size

    /** Re-reads the character under a finger that has not moved. */
    internal fun reExtend() {
        val point = lastPoint ?: return
        extend?.invoke(point)
    }

    /**
     * What the magnifier should show, or [Offset.Unspecified] for "nothing".
     *
     * The caret of the edge being moved rather than the finger itself: the
     * point of the loupe is to show the character the selection has actually
     * landed on, which is under the fingertip and never visible.
     */
    fun magnifierSource(): Offset {
        if (!dragging) return Offset.Unspecified
        val point = lastPoint ?: return Offset.Unspecified
        val carets = listOfNotNull(
            caretOf(SelectionEdge.START),
            caretOf(SelectionEdge.END),
        )
        val nearest = carets.minByOrNull { (it.center - point).getDistance() }
        return nearest?.center ?: Offset.Unspecified
    }

    private val fragments = ArrayList<TextFragment>()
    private var epochCounter = 0

    /** A fresh generation number for a whole subtree of fragments. */
    fun newEpoch(): Int = ++epochCounter

    fun register(fragment: TextFragment) {
        fragments += fragment
    }

    fun unregister(fragment: TextFragment) {
        fragments.remove(fragment)
    }

    fun set(value: BookSelection?) {
        selection = value?.takeUnless { it.isEmpty }
        active = selection != null
    }

    fun clear() {
        selection = null
        active = false
        dragging = false
    }

    // ------------------------------------------------------------- hit testing

    /**
     * The character at [point], or null when nothing selectable is on screen.
     * [point] is in this controller's space — the reader Box, which is also
     * what the gesture detector reports.
     */
    fun anchorAt(point: Offset, maxVerticalGap: Float = Float.MAX_VALUE): BookAnchor? {
        val space = liveSpace() ?: return null
        val visible = visibleFragments(space)
        if (visible.isEmpty()) return null
        val index = SelectionHitRules.pick(visible.map { it.second }, point, maxVerticalGap)
        if (index < 0) return null
        val fragment = visible[index].first
        val coords = fragment.coords ?: return null
        val layout = fragment.layout ?: return null
        val local = coords.localPositionOf(space, point)
        val offset = layout.getOffsetForPosition(local).coerceIn(0, fragment.length)
        return BookAnchor(fragment.itemIndex, fragment.charStart + offset)
    }

    /**
     * The whole word under [point] — what a long press selects, or null when
     * the press landed too far from any text (on an illustration, say) to mean
     * a word at all.
     */
    fun wordAt(point: Offset, maxVerticalGap: Float = Float.MAX_VALUE): BookSelection? {
        val anchor = anchorAt(point, maxVerticalGap) ?: return null
        val text = textAt(anchor.itemIndex) ?: return null
        val span = SelectionText.wordAt(text, anchor.charOffset)
        if (span.isEmpty) return null
        return BookSelection.of(anchor.itemIndex, span)
    }

    /**
     * The caret line of one edge of the selection, or null when that character
     * is on a page that is not currently shown. Handles hang from its bottom;
     * the action pill is placed off its top.
     */
    fun caretOf(edge: SelectionEdge): Rect? {
        val current = selection ?: return null
        val space = liveSpace() ?: return null
        val anchor = if (edge == SelectionEdge.START) current.start else current.end
        // An anchor sits at the boundary between two fragments as often as
        // not; prefer the one that really draws the character.
        //
        // Written as a plain loop, and the geometry only queried for fragments
        // that could hold the anchor at all: this runs in the DRAW phase, once
        // per handle per frame, for as long as a selection is on screen.
        var fragment: TextFragment? = null
        var best = Int.MAX_VALUE
        for (candidate in fragments) {
            if (candidate.layout == null || !candidate.holds(anchor)) continue
            if (rectOf(space, candidate).isEmpty) continue // on another page
            val score = if (edge == SelectionEdge.END) {
                anchor.charOffset - candidate.charStart
            } else {
                candidate.charStart + candidate.length - anchor.charOffset
            }
            if (score < best || (score == best && candidate.epoch > (fragment?.epoch ?: -1))) {
                best = score
                fragment = candidate
            }
        }
        if (fragment == null) return null
        val coords = fragment.coords ?: return null
        val layout = fragment.layout ?: return null
        val local = (anchor.charOffset - fragment.charStart).coerceIn(0, fragment.length)
        val caret = layout.getCursorRect(local)
        return Rect(
            space.localPositionOf(coords, caret.topLeft),
            space.localPositionOf(coords, caret.bottomRight),
        )
    }

    /**
     * Center of the round handle for [edge], or null when its character is on
     * a page that is not currently shown — a handle only ever appears at the
     * character it actually marks.
     */
    fun handleCenter(edge: SelectionEdge, radiusPx: Float): Offset? =
        caretOf(edge)?.let { SelectionHitRules.handleCenter(it, edge, radiusPx) }

    /**
     * What the action pill hangs off: the selection as painted on ONE page —
     * the one holding its first visible character.
     *
     * Not everything visible. Mid page turn, a selection that spans the break
     * has text on both pages at once, and a box drawn around both has its
     * centre somewhere in the gap between them: the pill jumped old page →
     * between → new page in three frames, which is exactly as ragged as it
     * sounds. Fragments of one page share a column, so grouping by column
     * keeps the pill on one page and lets it ride out with it.
     *
     * In scroll mode every fragment shares the one column, so this is simply
     * the whole visible selection.
     */
    fun toolbarBounds(): Rect? {
        val current = selection ?: return null
        val space = liveSpace() ?: return null

        // Pass one: the first visible character of the selection picks the
        // column. Plain loops for the same reason [caretOf] uses one — this
        // runs on every frame the pill is on screen.
        var column: Rect? = null
        var first: BookAnchor? = null
        for (fragment in fragments) {
            if (fragment.layout == null) continue
            val span = current.intersect(
                fragment.itemIndex, fragment.charStart, fragment.length,
            ) ?: continue
            val rect = rectOf(space, fragment)
            if (rect.isEmpty) continue // on another page
            val at = BookAnchor(fragment.itemIndex, fragment.charStart + span.start)
            if (first == null || at < first) {
                first = at
                column = rect
            }
        }
        val anchor = column ?: return null

        // Pass two: everything painted in that column.
        var result: Rect? = null
        for (fragment in fragments) {
            val span = current.intersect(
                fragment.itemIndex, fragment.charStart, fragment.length,
            ) ?: continue
            val layout = fragment.layout ?: continue
            val coords = fragment.coords ?: continue
            val rect = rectOf(space, fragment)
            if (rect.isEmpty || !SelectionHitRules.sharesColumn(anchor, rect)) continue
            val box = fragment.pathFor(layout, span).getBounds()
            if (box.isEmpty) continue
            val inSpace = Rect(
                space.localPositionOf(coords, box.topLeft),
                space.localPositionOf(coords, box.bottomRight),
            )
            result = result?.expandToInclude(inSpace) ?: inSpace
        }
        return result
    }

    /** The selected text, read out of the book rather than off the screen. */
    fun selectedText(count: Int, elementAt: (Int) -> ContentElement): String {
        val current = selection ?: return ""
        return SelectionText.extract(current, count, elementAt)
    }

    private fun liveSpace(): LayoutCoordinates? = space?.takeIf { it.isAttached }

    /**
     * Fragments that can actually be pointed at, freshest generation first.
     *
     * Off-screen pages composed by `beyondViewportPageCount` and prefetched
     * list items are excluded geometrically: their bounding box clips away to
     * nothing, so they simply lose the hit test.
     */
    private fun visibleFragments(space: LayoutCoordinates): List<Pair<TextFragment, Rect>> =
        fragments
            .mapNotNull { fragment ->
                if (fragment.layout == null) return@mapNotNull null
                val rect = rectOf(space, fragment)
                if (rect.isEmpty) null else fragment to rect
            }
            .sortedByDescending { it.first.epoch }

    private fun rectOf(space: LayoutCoordinates, fragment: TextFragment): Rect {
        val coords = fragment.coords ?: return Rect.Zero
        if (!coords.isAttached) return Rect.Zero
        return space.localBoundingBoxOf(coords, clipBounds = true)
    }
}

private fun Rect.expandToInclude(other: Rect) = Rect(
    left = minOf(left, other.left),
    top = minOf(top, other.top),
    right = maxOf(right, other.right),
    bottom = maxOf(bottom, other.bottom),
)

/**
 * Everything a rendered `Text` needs in order to take part in selection:
 * whose selection it is, which layout generation it belongs to, and the two
 * colors it paints with. Passed down as one value so `RenderPart` — which
 * already carries twenty parameters — grows by one.
 */
@Stable
class ReaderHighlights(
    val controller: SelectionController,
    val epoch: Int,
    /** Saved quotes, already resolved to book coordinates. */
    val quotes: List<BookSelection>,
    val quoteColor: Color,
    val selectionColor: Color,
)

/**
 * One layout generation for a whole reading surface.
 *
 * Call it inside the subtree that gets rebuilt as a unit — the pager's
 * `key(paginationKey, partial)` block, or the scrolling list. Everything
 * composed under it shares an epoch, so when a settings change swaps one
 * subtree for another the fragments of the outgoing one lose the hit test
 * during the frame both are alive.
 */
@Composable
fun rememberReaderHighlights(
    controller: SelectionController,
    quotes: List<BookSelection>,
    quoteColor: Color,
    selectionColor: Color,
): ReaderHighlights {
    val epoch = remember { controller.newEpoch() }
    return remember(epoch, quotes, quoteColor, selectionColor) {
        ReaderHighlights(controller, epoch, quotes, quoteColor, selectionColor)
    }
}

/**
 * Registers one rendered `Text` for as long as it is composed. Null
 * [highlights] (a preview outside the reader) means no fragment, and
 * [readerHighlights] downstream turns into a no-op.
 */
@Composable
fun rememberTextFragment(
    highlights: ReaderHighlights?,
    itemIndex: Int,
    charStart: Int,
    length: Int,
): TextFragment? {
    if (highlights == null) return null
    val controller = highlights.controller
    val fragment = remember(itemIndex, charStart, length, highlights.epoch) {
        TextFragment(itemIndex, charStart, length, highlights.epoch)
    }
    DisposableEffect(fragment) {
        controller.register(fragment)
        onDispose { controller.unregister(fragment) }
    }
    return fragment
}

/**
 * Paints the saved quotes and the live selection that fall inside this
 * fragment, in that order.
 *
 * A draw node and nothing else: it must not touch measurement, because the
 * paginated `Text` is handed the exact pixel width it was measured with and a
 * single pixel of difference re-wraps a word and clips the page.
 *
 * Deliberately not a background `SpanStyle` — that rebuilds the
 * `AnnotatedString` and re-lays out the paragraph, which at 120 Hz during a
 * drag is not affordable. Reading the selection inside the draw lambda keeps a
 * whole drag in the draw phase, with no recomposition at all.
 */
fun Modifier.readerHighlights(
    fragment: TextFragment?,
    highlights: ReaderHighlights?,
): Modifier {
    if (fragment == null || highlights == null) return this
    return this
        .onPlaced { fragment.coords = it }
        .drawBehind {
            val layout = fragment.layout ?: return@drawBehind
            for (quote in highlights.quotes) {
                val span = quote.intersect(
                    fragment.itemIndex, fragment.charStart, fragment.length,
                ) ?: continue
                drawPath(fragment.pathFor(layout, span), highlights.quoteColor)
            }
            val selected = highlights.controller.selection?.intersect(
                fragment.itemIndex, fragment.charStart, fragment.length,
            ) ?: return@drawBehind
            drawPath(fragment.pathFor(layout, selected), highlights.selectionColor)
        }
}
