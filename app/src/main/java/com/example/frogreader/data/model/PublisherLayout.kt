package com.example.frogreader.data.model

/** Border patterns the native publisher-layout renderer can reproduce. */
enum class PublisherBorderStyle {
    NONE,
    SOLID,
    DASHED,
    DOTTED,
    DOUBLE,
}

/** One physical edge of a publisher-authored box. */
data class PublisherBorderSide(
    val widthEm: Float,
    val colorArgb: Int,
    val style: PublisherBorderStyle,
)

/**
 * Native, deliberately bounded subset of the CSS box model.
 *
 * Every edge is physical. Logical CSS properties are resolved against the
 * element direction before this model is built, so pagination and painting
 * never have to repeat cascade or bidi decisions. Percent widths and
 * horizontal percentage insets remain fractions of the containing block;
 * other retained lengths are normalized to reader-root em after resolving
 * element-relative `em`/`ch`/`ex` against the element's computed font size.
 */
data class PublisherBoxStyle(
    val marginTopEm: Float = 0f,
    val marginRightEm: Float = 0f,
    val marginRightFrac: Float = 0f,
    val marginBottomEm: Float = 0f,
    val marginLeftEm: Float = 0f,
    val marginLeftFrac: Float = 0f,
    val paddingTopEm: Float = 0f,
    val paddingRightEm: Float = 0f,
    val paddingRightFrac: Float = 0f,
    val paddingBottomEm: Float = 0f,
    val paddingLeftEm: Float = 0f,
    val paddingLeftFrac: Float = 0f,
    val backgroundColorArgb: Int? = null,
    val borderTop: PublisherBorderSide? = null,
    val borderRight: PublisherBorderSide? = null,
    val borderBottom: PublisherBorderSide? = null,
    val borderLeft: PublisherBorderSide? = null,
    val widthFrac: Float? = null,
    val widthEm: Float? = null,
    /** Alignment of this box itself, independent from its text alignment. */
    val horizontalAlign: PublisherBoxAlign = PublisherBoxAlign.START,
    val breakInsideAvoid: Boolean = false,
) {
    val isDefault: Boolean
        get() = this == DEFAULT

    companion object {
        val DEFAULT = PublisherBoxStyle()
    }
}

enum class PublisherBoxAlign { START, CENTER, END }

enum class PublisherFloatSide { LEFT, RIGHT }

enum class PublisherClear { NONE, LEFT, RIGHT, BOTH }

/**
 * One publisher box over a half-open range of semantic leaf elements.
 *
 * Spans do not consume an element index. Siblings must be disjoint and
 * descendants properly nested, which lets the reader derive a box tree while
 * progress, search, selection and anchors keep using the existing flat leaf
 * coordinates. [drawStart] and [drawEnd] distinguish a complete box from a
 * continuation created when an EPUB document is sliced at a navigation
 * boundary.
 */
data class PublisherBoxSpan(
    val id: String,
    val parentId: String? = null,
    val startElement: Int,
    val endElementExclusive: Int,
    val style: PublisherBoxStyle = PublisherBoxStyle.DEFAULT,
    val floatSide: PublisherFloatSide? = null,
    val clear: PublisherClear = PublisherClear.NONE,
    val drawStart: Boolean = true,
    val drawEnd: Boolean = true,
) {
    init {
        require(id.isNotBlank()) { "Publisher box id must not be blank" }
        require(parentId != id) { "Publisher box cannot parent itself" }
        require(startElement >= 0) { "Publisher box start must be non-negative" }
        require(endElementExclusive >= startElement) {
            "Publisher box end must not precede its start"
        }
        if (endElementExclusive == startElement) {
            require(clear != PublisherClear.NONE) {
                "Only a clear marker may have an empty leaf range"
            }
            require(style.isDefault && floatSide == null) {
                "An empty clear marker cannot carry decoration or float"
            }
        }
    }

    /** True when this box contains at least one leaf in [start, endExclusive). */
    fun intersects(start: Int, endExclusive: Int): Boolean {
        require(start >= 0) { "Slice start must be non-negative" }
        require(endExclusive >= start) { "Slice end must not precede its start" }
        return if (startElement == endElementExclusive) {
            startElement >= start && startElement < endExclusive
        } else {
            startElement < endExclusive && endElementExclusive > start
        }
    }

    /**
     * Intersects this box with [start, endExclusive) and rebases it to that
     * slice's zero-based leaf coordinates. Null means the two ranges do not
     * overlap. Original outer edges are painted only when the slice contains
     * them; continuation fragments retain side decoration through [style].
     */
    fun sliceTo(
        start: Int,
        endExclusive: Int,
        destinationOffset: Int = 0,
    ): PublisherBoxSpan? {
        require(start >= 0) { "Slice start must be non-negative" }
        require(endExclusive >= start) { "Slice end must not precede its start" }
        require(destinationOffset >= 0) { "Destination offset must be non-negative" }
        if (!intersects(start, endExclusive)) return null

        if (startElement == endElementExclusive) {
            val rebased = startElement - start + destinationOffset
            return copy(startElement = rebased, endElementExclusive = rebased)
        }

        val includesStart = start <= startElement
        val includesEnd = endExclusive >= endElementExclusive
        return copy(
            startElement = maxOf(startElement, start) - start + destinationOffset,
            endElementExclusive = minOf(endElementExclusive, endExclusive) - start +
                destinationOffset,
            clear = if (includesStart) clear else PublisherClear.NONE,
            drawStart = drawStart && includesStart,
            drawEnd = drawEnd && includesEnd,
        )
    }
}

/** Slice and rebase every publisher box that intersects the requested leaf range. */
fun List<PublisherBoxSpan>.slicePublisherBoxes(
    start: Int,
    endExclusive: Int,
): List<PublisherBoxSpan> = slicePublisherBoxSpans(this, start, endExclusive)

/**
 * Intersect publisher boxes with one source leaf range and place the clipped
 * result at [destinationOffset]. Parent links are retained only when that
 * parent also survives the slice, preventing dangling hierarchy references.
 */
fun slicePublisherBoxSpans(
    spans: List<PublisherBoxSpan>,
    startElement: Int,
    endElementExclusive: Int,
    destinationOffset: Int = 0,
): List<PublisherBoxSpan> {
    require(startElement >= 0) { "Slice start must be non-negative" }
    require(endElementExclusive >= startElement) {
        "Slice end must not precede its start"
    }
    require(destinationOffset >= 0) { "Destination offset must be non-negative" }
    val sliced = spans.mapNotNull {
        it.sliceTo(startElement, endElementExclusive, destinationOffset)
    }
    if (sliced.isEmpty()) return emptyList()
    val retainedIds = sliced.asSequence().map(PublisherBoxSpan::id).toHashSet()
    return sliced.map { span ->
        span.takeIf { it.parentId == null || it.parentId in retainedIds }
            ?: span.copy(parentId = null)
    }
}
