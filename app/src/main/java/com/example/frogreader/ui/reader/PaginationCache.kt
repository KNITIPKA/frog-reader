package com.example.frogreader.ui.reader

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.EXTERNAL_LINK_TAG
import com.example.frogreader.data.model.FOOTNOTE_TAG
import com.example.frogreader.data.model.INLINE_IMAGE_ALT_TAG
import com.example.frogreader.data.model.INLINE_IMAGE_TAG
import com.example.frogreader.data.model.LINK_TAG
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Disk cache of a book's page layout, so reopening the book skips the whole
 * measurement pass. One file per book, keyed by the pagination spec (screen
 * size + every layout-affecting setting) and by a cheap content signature —
 * any mismatch simply recomputes.
 */
object PaginationCache {

    /** Bump when the CachedPart/CachedPage shape changes. */
    private const val FORMAT_VERSION = 4

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
        val contentSignature: String,
        val pages: List<CachedPage>,
        val version: Int = 1,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun fileFor(dir: File, bookId: String): File = File(dir, "$bookId.json")

    /**
     * Stable identity of every layout-affecting model value.
     *
     * Text lengths are not enough: a replacement book can keep every length
     * while changing words, spans, table CSS or image dimensions. Loading the
     * old row widths/page cuts in that case makes measurement and drawing
     * disagree. SHA-256 is inexpensive beside pagination and also avoids the
     * deterministic collisions of a small rolling hash.
     */
    internal fun contentSignature(items: List<ReaderItem>): String {
        val hash = ContentHasher()
        hash.int(items.size)
        for (item in items) {
            hash.int(item.chapterIndex)
            when (val element = item.element) {
                is ContentElement.Paragraph -> {
                    hash.string("paragraph")
                    hash.string(element.style.name)
                    hash.annotated(element.text)
                    hash.block(element.block)
                }

                is ContentElement.Heading -> {
                    hash.string("heading")
                    hash.int(element.level)
                    hash.annotated(element.styledText)
                    hash.block(element.block)
                }

                is ContentElement.Image -> {
                    hash.string("image")
                    hash.file(element.path)
                    hash.float(element.widthFrac)
                    hash.float(element.heightEm)
                    hash.string(element.altText)
                }

                ContentElement.Divider -> hash.string("divider")

                is ContentElement.Spacer -> {
                    hash.string("spacer")
                    hash.float(element.heightEm)
                }

                is ContentElement.Table -> {
                    hash.string("table")
                    hash.block(element.block)
                    hash.int(element.rows.size)
                    for (row in element.rows) {
                        hash.boolean(row.isHeader)
                        hash.int(row.cells.size)
                        for (cell in row.cells) {
                            hash.annotated(cell.text)
                            hash.int(cell.colSpan)
                            hash.int(cell.rowSpan)
                            hash.string(cell.align?.name)
                            hash.boolean(cell.header)
                            hash.block(cell.block)
                        }
                    }
                }
            }
        }
        return hash.finish()
    }

    fun load(file: File, key: String, items: List<ReaderItem>): List<BookPage>? =
        runCatching {
            if (!file.exists()) return null
            val cached = json.decodeFromString<CachedPagination>(file.readText())
            if (cached.version != FORMAT_VERSION ||
                cached.key != key ||
                cached.itemCount != items.size ||
                cached.contentSignature != contentSignature(items)
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
                contentSignature = contentSignature(items),
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

    private class ContentHasher {
        private val digest = MessageDigest.getInstance("SHA-256")
        private val fileDigests = mutableMapOf<String, ByteArray>()

        fun int(value: Int) {
            digest.update((value ushr 24).toByte())
            digest.update((value ushr 16).toByte())
            digest.update((value ushr 8).toByte())
            digest.update(value.toByte())
        }

        fun boolean(value: Boolean) = int(if (value) 1 else 0)

        fun float(value: Float?) {
            if (value == null) {
                int(0)
            } else {
                int(1)
                int(value.toRawBits())
            }
        }

        fun string(value: String?) {
            if (value == null) {
                int(-1)
                return
            }
            val bytes = value.toByteArray(Charsets.UTF_8)
            int(bytes.size)
            digest.update(bytes)
        }

        fun annotated(text: AnnotatedString) {
            string(text.text)
            int(text.spanStyles.size)
            for (range in text.spanStyles) {
                int(range.start)
                int(range.end)
                string(range.item.toString())
            }
            for (tag in ANNOTATION_TAGS) {
                val annotations = text.getStringAnnotations(tag, 0, text.length)
                string(tag)
                int(annotations.size)
                for (range in annotations) {
                    int(range.start)
                    int(range.end)
                    string(range.item)
                    if (tag == INLINE_IMAGE_TAG) file(range.item)
                }
            }
        }

        fun block(block: com.example.frogreader.data.model.BlockStyle?) {
            string(block?.toString())
            block?.floatImage?.path?.let(::file)
        }

        /**
         * File metadata plus bounded head/tail samples catch replaced images
         * without rereading a 60 MB illustration on every cache lookup.
         */
        fun file(path: String) {
            string(path)
            val fingerprint = fileDigests.getOrPut(path) {
                val file = File(path)
                val local = MessageDigest.getInstance("SHA-256")
                if (!file.isFile) {
                    local.update(0.toByte())
                    return@getOrPut local.digest()
                }
                local.update(1.toByte())
                updateLong(local, file.length())
                updateLong(local, file.lastModified())
                runCatching {
                    RandomAccessFile(file, "r").use { input ->
                        val sample = ByteArray(FILE_SAMPLE_BYTES)
                        val head = input.read(sample)
                        if (head > 0) local.update(sample, 0, head)
                        if (file.length() > FILE_SAMPLE_BYTES) {
                            input.seek((file.length() - FILE_SAMPLE_BYTES).coerceAtLeast(0))
                            val tail = input.read(sample)
                            if (tail > 0) local.update(sample, 0, tail)
                        }
                    }
                }
                local.digest()
            }
            int(fingerprint.size)
            digest.update(fingerprint)
        }

        fun finish(): String = digest.digest().joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }

        private fun updateLong(target: MessageDigest, value: Long) {
            for (shift in 56 downTo 0 step 8) target.update((value ushr shift).toByte())
        }
    }

    private val ANNOTATION_TAGS = listOf(
        FOOTNOTE_TAG,
        LINK_TAG,
        EXTERNAL_LINK_TAG,
        INLINE_IMAGE_TAG,
        INLINE_IMAGE_ALT_TAG,
    )

    private const val FILE_SAMPLE_BYTES = 4 * 1024
}
