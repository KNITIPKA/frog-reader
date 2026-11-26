package com.example.frogreader.data.pdf.compat

import android.os.Bundle
import java.io.IOException

/** Small, primitive-only messages between the reader and the isolated native text worker. */
internal object PdfTextProtocol {
    const val KEY_OPERATION = "operation"
    const val OP_INFO = 0
    const val OP_CONTENT = 1
    const val OP_SELECTION = 2
    const val OP_SEARCH = 3
    const val OP_IMPORT_METADATA = 4
    const val OP_OUTLINE = 5
    const val KEY_PAGE = "page"
    const val KEY_PAGE_COUNT = "pageCount"
    const val KEY_WIDTH = "width"
    const val KEY_HEIGHT = "height"
    const val KEY_QUERY = "query"
    const val KEY_START = "start"
    const val KEY_STOP = "stop"

    // Keep each response well below Binder's shared 1 MiB transaction buffer.
    const val MAX_TEXT_CHARS = 120_000
    const val MAX_RECTS = 8_192
    const val MAX_MATCHES = 2_000
    const val MAX_SEARCH_RECTS = 16_384
    const val MAX_NATIVE_CHARS = 1_000_000
    const val MAX_QUERY_CHARS = 1_024

    fun encodeBoundary(value: PdfTextBoundary): Bundle = Bundle().apply {
        if ((value.x == null) != (value.y == null) || value.x?.isFinite() == false || value.y?.isFinite() == false) {
            throw IOException("Invalid PDF selection boundary")
        }
        putInt("index", value.index)
        if (value.x != null && value.y != null) {
            putFloat("x", value.x)
            putFloat("y", value.y)
        }
        putBoolean("rtl", value.isRtl)
    }

    fun decodeBoundary(bundle: Bundle): PdfTextBoundary = PdfTextBoundary(
        index = bundle.getInt("index"),
        x = if (bundle.containsKey("x")) bundle.getFloat("x") else null,
        y = if (bundle.containsKey("y")) bundle.getFloat("y") else null,
        isRtl = bundle.getBoolean("rtl"),
    )

    fun encodePage(value: PdfTextPageData): Bundle = Bundle().apply {
        requireText(value.text)
        putString("text", value.text)
        putFloatArray("bounds", encodeRects(value.bounds, MAX_RECTS))
    }

    fun decodePage(bundle: Bundle): PdfTextPageData = PdfTextPageData(
        bundle.getString("text").orEmpty().also(::requireText),
        decodeRects(bundle.getFloatArray("bounds") ?: floatArrayOf(), MAX_RECTS),
    )

    fun encodeSelection(value: PdfTextSelectionData?): Bundle = Bundle().apply {
        putBoolean("present", value != null)
        if (value != null) {
            requireText(value.text)
            putBundle(KEY_START, encodeBoundary(value.start))
            putBundle(KEY_STOP, encodeBoundary(value.stop))
            putString("text", value.text)
            putFloatArray("bounds", encodeRects(value.bounds, MAX_RECTS))
        }
    }

    fun decodeSelection(bundle: Bundle): PdfTextSelectionData? {
        if (!bundle.getBoolean("present")) return null
        return PdfTextSelectionData(
            decodeBoundary(bundle.getBundle(KEY_START) ?: throw IOException("Missing PDF selection start")),
            decodeBoundary(bundle.getBundle(KEY_STOP) ?: throw IOException("Missing PDF selection end")),
            bundle.getString("text").orEmpty().also(::requireText),
            decodeRects(bundle.getFloatArray("bounds") ?: floatArrayOf(), MAX_RECTS),
        )
    }

    fun encodeMatches(values: List<PdfTextMatchData>): Bundle {
        if (values.size > MAX_MATCHES) throw IOException("Too many PDF matches on one page")
        if (values.sumOf { it.bounds.size.toLong() } > MAX_SEARCH_RECTS) throw IOException("PDF search results are too large")
        val indices = IntArray(values.size) { values[it].textStartIndex }
        val offsets = IntArray(values.size + 1)
        val rects = ArrayList<PdfTextRect>()
        values.forEachIndexed { index, match ->
            offsets[index] = rects.size
            rects.addAll(match.bounds)
        }
        offsets[values.size] = rects.size
        return Bundle().apply {
            putIntArray("indices", indices)
            putIntArray("offsets", offsets)
            putFloatArray("bounds", encodeRects(rects, MAX_SEARCH_RECTS))
        }
    }

    fun decodeMatches(bundle: Bundle): List<PdfTextMatchData> {
        val indices = bundle.getIntArray("indices") ?: intArrayOf()
        val offsets = bundle.getIntArray("offsets") ?: intArrayOf(0)
        val rects = decodeRects(bundle.getFloatArray("bounds") ?: floatArrayOf(), MAX_SEARCH_RECTS)
        if (indices.size > MAX_MATCHES || offsets.size != indices.size + 1 ||
            indices.any { it < 0 } ||
            offsets.first() != 0 || offsets.last() != rects.size ||
            (1 until offsets.size).any { index -> offsets[index - 1] < 0 || offsets[index] < offsets[index - 1] }
        ) throw IOException("Invalid PDF search response")
        return indices.mapIndexed { index, nativeIndex ->
            PdfTextMatchData(nativeIndex, rects.subList(offsets[index], offsets[index + 1]))
        }
    }

    private fun encodeRects(rects: List<PdfTextRect>, limit: Int): FloatArray {
        if (rects.size > limit) throw IOException("PDF text has too many rectangles")
        return FloatArray(rects.size * 4).also { values ->
            rects.forEachIndexed { index, rect ->
                if (!validRect(rect.left, rect.top, rect.right, rect.bottom)) throw IOException("Invalid PDF text rectangle")
                values[index * 4] = rect.left
                values[index * 4 + 1] = rect.top
                values[index * 4 + 2] = rect.right
                values[index * 4 + 3] = rect.bottom
            }
        }
    }

    private fun decodeRects(values: FloatArray, limit: Int): List<PdfTextRect> {
        if (values.size % 4 != 0 || values.size / 4 > limit || values.any { !it.isFinite() }) {
            throw IOException("Invalid PDF text rectangles")
        }
        return (values.indices step 4).map { offset ->
            if (!validRect(values[offset], values[offset + 1], values[offset + 2], values[offset + 3])) {
                throw IOException("Invalid PDF text rectangle")
            }
            PdfTextRect(values[offset], values[offset + 1], values[offset + 2], values[offset + 3])
        }
    }

    private fun validRect(left: Float, top: Float, right: Float, bottom: Float): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() && right > left && bottom > top

    private fun requireText(text: String) {
        if (text.length > MAX_TEXT_CHARS) throw IOException("PDF text selection is too large")
    }
}

internal data class PdfTextRect(val left: Float, val top: Float, val right: Float, val bottom: Float)
internal data class PdfTextBoundary(
    val index: Int = 0,
    val x: Float? = null,
    val y: Float? = null,
    val isRtl: Boolean = false,
)
internal data class PdfTextPageData(val text: String, val bounds: List<PdfTextRect>)
internal data class PdfTextSelectionData(
    val start: PdfTextBoundary,
    val stop: PdfTextBoundary,
    val text: String,
    val bounds: List<PdfTextRect>,
)
internal data class PdfTextMatchData(val textStartIndex: Int, val bounds: List<PdfTextRect>)
