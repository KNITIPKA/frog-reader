package com.example.frogreader.ui.reader

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.ContentElement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Disk cache of a book's page layout, so reopening the book skips the whole
 * measurement pass. One file per book, keyed by the pagination spec (screen
 * size + every layout-affecting setting) and by a cheap content signature —
 * any mismatch simply recomputes.
 */
object PaginationCache {

    /** Bump when the CachedPart/CachedPage shape changes. */
    private const val FORMAT_VERSION = 3

    @Serializable
    private class CachedPart(
        val item: Int,
        val start: Int = -1,
        val end: Int = -1,
        val paraStart: Boolean = true,
        val imageH: Int? = null,
        // Table parts: row range + the measured layout (grid is rebuilt).
        val rowS: Int = -1,
        val rowE: Int = -1,
        val cols: List<Int>? = null,
        val rowH: List<Int>? = null,
        val tScale: Float = 1f,
        val hdr: Boolean = false,
        // Side-box composite (drop cap / float); present when sbBesideW > 0.
        val sbCap: String? = null,
        val sbImg: String? = null,
        val sbLeft: Boolean = true,
        val sbW: Int = 0,
        val sbH: Int = 0,
        val sbBesideW: Int = 0,
        val sbEnd: Int = 0,
        val sbCompH: Int = 0,
        val sbCapSp: Float = 0f,
        val sbCapTop: Int = 0,
        // A float drawn as a plain block image (publisher formatting off).
        val fImg: String? = null,
    )

    @Serializable
    private class CachedPage(
        val first: Int,
        val firstChar: Int,
        val parts: List<CachedPart>,
    )

    @Serializable
    private class CachedPagination(
        val key: String,
        val itemCount: Int,
        val charSignature: Long,
        val pages: List<CachedPage>,
        val version: Int = 1,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun fileFor(dir: File, bookId: String): File = File(dir, "$bookId.json")

    /** Cheap content identity: element count is checked separately. */
    private fun signature(items: List<ReaderItem>): Long {
        var sum = 0L
        for (item in items) {
            sum = sum * 31 + when (val element = item.element) {
                is ContentElement.Paragraph -> element.text.text.length.toLong()
                is ContentElement.Heading -> element.text.length.toLong()
                is ContentElement.Table -> element.rows.sumOf { row ->
                    row.cells.sumOf { it.text.length + 1L }
                }
                else -> 1L
            }
        }
        return sum
    }

    fun load(file: File, key: String, items: List<ReaderItem>): List<BookPage>? =
        runCatching {
            if (!file.exists()) return null
            val cached = json.decodeFromString<CachedPagination>(file.readText())
            if (cached.version != FORMAT_VERSION ||
                cached.key != key ||
                cached.itemCount != items.size ||
                cached.charSignature != signature(items)
            ) {
                return null
            }
            cached.pages.map { page ->
                BookPage(
                    parts = page.parts.map { part ->
                        val element = items.getOrNull(part.item)?.element ?: return null
                        if (part.rowS >= 0) {
                            val table = element as? ContentElement.Table ?: return null
                            val cols = part.cols ?: return null
                            val rowHeights = part.rowH ?: return null
                            if (rowHeights.size != table.rows.size) return null
                            val grid = TableGrid.build(table.rows)
                            if (grid.columnCount != cols.size) return null
                            return@map PagePart(
                                itemIndex = part.item,
                                element = element,
                                rowStart = part.rowS,
                                rowEnd = part.rowE,
                                tableLayout = TableLayout(
                                    grid,
                                    cols.toIntArray(),
                                    rowHeights.toIntArray(),
                                    part.tScale,
                                ),
                                headerRepeated = part.hdr,
                                charStart = part.rowS,
                                charEnd = part.rowE,
                            )
                        }
                        val text = if (part.start >= 0) {
                            val full = when (element) {
                                is ContentElement.Paragraph -> element.text
                                is ContentElement.Heading -> element.styledText
                                else -> return null
                            }
                            if (part.end > full.length || part.start > part.end) return null
                            full.subSequence(part.start, part.end)
                        } else {
                            null
                        }
                        val sideBox = if (part.sbBesideW > 0) {
                            SideBoxSpec(
                                capText = part.sbCap,
                                imagePath = part.sbImg,
                                leftSide = part.sbLeft,
                                boxWidthPx = part.sbW,
                                boxHeightPx = part.sbH,
                                besideWidthPx = part.sbBesideW,
                                besideEndChar = part.sbEnd,
                                compositeHeightPx = part.sbCompH,
                                capFontSizeSp = part.sbCapSp,
                                capTopPx = part.sbCapTop,
                            )
                        } else {
                            null
                        }
                        PagePart(
                            itemIndex = part.item,
                            element = element,
                            text = text,
                            isParagraphStart = part.paraStart,
                            imageHeightPx = part.imageH,
                            charStart = part.start,
                            charEnd = part.end,
                            sideBox = sideBox,
                            floatImagePath = part.fImg,
                        )
                    },
                    firstItemIndex = page.first,
                    firstCharOffset = page.firstChar,
                )
            }
        }.getOrNull()

    fun save(file: File, key: String, items: List<ReaderItem>, pages: List<BookPage>) {
        runCatching {
            file.parentFile?.mkdirs()
            val cached = CachedPagination(
                key = key,
                itemCount = items.size,
                charSignature = signature(items),
                version = FORMAT_VERSION,
                pages = pages.map { page ->
                    CachedPage(
                        first = page.firstItemIndex,
                        firstChar = page.firstCharOffset,
                        parts = page.parts.map { part ->
                            CachedPart(
                                item = part.itemIndex,
                                start = if (part.text != null) part.charStart else -1,
                                end = if (part.text != null) part.charEnd else -1,
                                paraStart = part.isParagraphStart,
                                imageH = part.imageHeightPx,
                                rowS = part.rowStart,
                                rowE = part.rowEnd,
                                cols = part.tableLayout?.colWidthsPx?.toList(),
                                rowH = part.tableLayout?.rowHeightsPx?.toList(),
                                tScale = part.tableLayout?.fontScale ?: 1f,
                                hdr = part.headerRepeated,
                                sbCap = part.sideBox?.capText,
                                sbImg = part.sideBox?.imagePath,
                                sbLeft = part.sideBox?.leftSide ?: true,
                                sbW = part.sideBox?.boxWidthPx ?: 0,
                                sbH = part.sideBox?.boxHeightPx ?: 0,
                                sbBesideW = part.sideBox?.besideWidthPx ?: 0,
                                sbEnd = part.sideBox?.besideEndChar ?: 0,
                                sbCompH = part.sideBox?.compositeHeightPx ?: 0,
                                sbCapSp = part.sideBox?.capFontSizeSp ?: 0f,
                                sbCapTop = part.sideBox?.capTopPx ?: 0,
                                fImg = part.floatImagePath,
                            )
                        },
                    )
                },
            )
            file.writeText(json.encodeToString(CachedPagination.serializer(), cached))
        }
    }
}
