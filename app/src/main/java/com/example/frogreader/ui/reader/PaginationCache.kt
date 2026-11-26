package com.example.frogreader.ui.reader

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.CachedTableLayout
import com.example.frogreader.data.model.EXTERNAL_LINK_TAG
import com.example.frogreader.data.model.FOOTNOTE_TAG
import com.example.frogreader.data.model.HIDDEN_TEXT_TAG
import com.example.frogreader.data.model.INLINE_IMAGE_ALT_TAG
import com.example.frogreader.data.model.INLINE_FONT_FAMILY_TAG
import com.example.frogreader.data.model.INLINE_MATH_TAG
import com.example.frogreader.data.model.INLINE_RUBY_TAG
import com.example.frogreader.data.model.INLINE_TEXT_BOX_TAG
import com.example.frogreader.data.model.INLINE_IMAGE_TAG
import com.example.frogreader.data.model.LINK_TAG
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.decodeFromStream
import java.security.MessageDigest

/**
 * Shared disk geometry for measured pages. Full layouts are bound to the
 * pagination spec and content signature. Indexed layouts bind individual
 * batches to their source and spec in the layout store.
 */
object PaginationCache {

    /** Bump when the CachedPart/CachedPage shape changes. */
    internal const val FORMAT_VERSION = 8
    // A pathological layout must never create an unbounded disposable file.
    private const val MAX_FILE_BYTES = 16L * 1024 * 1024

    @Serializable
    private class CachedPart(
        val item: Int,
        val start: Int = -1,
        val end: Int = -1,
        val paraStart: Boolean = true,
        val imageH: Int? = null,
        // Table parts retain a row range; layout is shared by source item.
        val rowS: Int = -1,
        val rowE: Int = -1,
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
        val sbFloatHeights: List<Int>? = null,
        val sbStacked: Boolean = false,
        // A float drawn as a plain block image (publisher formatting off).
        val fImg: String? = null,
        val pbStart: Boolean = true,
        val pbEnd: Boolean = true,
        val vScroll: Boolean = false,
    )

    @Serializable
    private class CachedPage(
        val first: Int,
        val firstChar: Int,
        val parts: List<CachedPart>,
    )

    @Serializable
    private class CachedPagination(
        val key: String? = null,
        val itemCount: Int? = null,
        val contentSignature: String? = null,
        val pages: List<CachedPage>,
        val version: Int = 1,
        val tables: Map<Int, CachedTableLayout> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Page geometry in global source coordinates. The layout store binds a batch to its content. */
    internal class Batch private constructor(
        private val pages: List<CachedPage>,
        private val tables: Map<Int, CachedTableLayout>,
    ) {
        val size: Int get() = pages.size

        val anchors: IntArray
            get() = IntArray(size * 2).also { anchors ->
                pages.forEachIndexed { index, page ->
                    anchors[index * 2] = page.first
                    anchors[index * 2 + 1] = page.firstChar
                }
            }

        // Float children and inline publisher content are already resolved in
        // the prepared source item. Only page anchors and part sources are read.
        val requiredItemIndices: IntArray
            get() = sortedSetOf<Int>().apply {
                for (page in pages) {
                    add(page.first)
                    page.parts.forEach { add(it.item) }
                }
            }.toIntArray()

        fun slice(fromPage: Int, toPageExclusive: Int): Batch =
            Batch(pages.subList(fromPage, toPageExclusive), tables)

        fun restore(items: List<ReaderItem>): List<BookPage>? = restorePages(pages, tables, items)

        companion object {
            internal fun read(bytes: ByteArray, offset: Int, length: Int): Batch? {
                if (length.toLong() !in 1L..MAX_FILE_BYTES || offset < 0 || offset > bytes.size - length) return null
                val cached = readCached { bytes.inputStream(offset, length) } ?: return null
                if (cached.key != null || cached.itemCount != null || cached.contentSignature != null) return null
                if (cached.pages.any { page ->
                        page.first < 0 || page.firstChar < 0 || page.parts.isEmpty() ||
                            page.parts.any { it.item < 0 }
                    }
                ) return null
                return Batch(cached.pages, cached.tables)
            }
        }
    }

    internal fun encodeBatch(pages: List<BookPage>, destination: File) {
        writeCached(destination, cachePages(pages), sync = false)
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    internal fun encodeBatchBytes(pages: List<BookPage>): ByteArray {
        val raw = ByteArrayOutputStream()
        val bounded = object : FilterOutputStream(raw) {
            private var written = 0L
            override fun write(value: Int) {
                if (++written > MAX_FILE_BYTES) throw IOException("Layout cache exceeds limit")
                out.write(value)
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (length < 0 || length > MAX_FILE_BYTES - written) throw IOException("Layout cache exceeds limit")
                written += length
                out.write(bytes, offset, length)
            }
        }
        json.encodeToStream(CachedPagination.serializer(), cachePages(pages), bounded)
        return raw.toByteArray()
    }

    internal fun readBatch(bytes: ByteArray, offset: Int, length: Int): Batch? =
        Batch.read(bytes, offset, length)

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
            hash.boolean(item.suppressedByPublisherFloat)
            hash.string(item.publisherClearBefore.name)
            hash.int(item.publisherBoxes.size)
            item.publisherBoxes.forEach(hash::publisherBox)
            item.publisherFloat?.let { float ->
                hash.string(float.side.name)
                hash.string(float.style.toString())
                hash.int(float.contents.size)
                float.contents.forEach { content ->
                    hash.int(content.sourceItemIndex)
                    hash.int(content.publisherBoxes.size)
                    content.publisherBoxes.forEach(hash::publisherBox)
                }
            } ?: hash.int(-1)
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
                    hash.float(element.widthEm)
                    hash.float(element.heightEm)
                    hash.float(element.maxHeightEm)
                    hash.float(element.maxHeightViewportFrac)
                    hash.float(element.maxWidthEm)
                    hash.float(element.maxWidthFrac)
                    hash.boolean(element.darkModeInvert)
                    hash.string(element.darkModeBackgroundArgb?.toString())
                    hash.boolean(element.darkModeBackgroundCurrentColor)
                    hash.string(element.altText)
                    hash.boolean(element.spaceBeforeSpecified)
                    hash.boolean(element.spaceAfterSpecified)
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
                    hash.string(element.publisherRowLayout?.name)
                    hash.boolean(element.centerCellsVertically)
                    hash.float(element.stackCellsBelowCssPx)
                    hash.string(element.publisherBox?.toString())
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
                            hash.string(cell.publisherBox?.toString())
                            hash.boolean(cell.publisherPaddingSpecified)
                            hash.boolean(cell.publisherBorderSpecified)
                            hash.string(cell.narrowStyle?.toString())
                        }
                    }
                }
            }
        }
        return hash.finish()
    }

    fun load(file: File, key: String, items: List<ReaderItem>): List<BookPage>? {
        val cached = readCached(file) ?: return null
        if (cached.key != key || cached.itemCount != items.size ||
            cached.contentSignature != contentSignature(items)
        ) return null
        return restorePages(cached.pages, cached.tables, items)
    }

    private fun readCached(file: File): CachedPagination? {
        if (!file.isFile || file.length() !in 1L..MAX_FILE_BYTES) return null
        return readCached { file.inputStream().buffered() }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private inline fun readCached(openInput: () -> InputStream): CachedPagination? = try {
        openInput().use { json.decodeFromStream<CachedPagination>(it) }
            .takeIf { it.version == FORMAT_VERSION }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun restorePages(
        pages: List<CachedPage>,
        tables: Map<Int, CachedTableLayout>,
        items: List<ReaderItem>,
    ): List<BookPage>? {
        // Reuse the rebuilt grid across all fragments of the same table.
        val tableLayouts = HashMap<Int, TableLayout>()
        return pages.map { page ->
            val anchor = items.getOrNull(page.first)?.takeUnless { it.isLoading } ?: return null
            if (page.firstChar !in 0..anchor.element.anchorEnd()) return null
            val parts = page.parts.map { part ->
                val item = items.getOrNull(part.item)?.takeUnless { it.isLoading } ?: return null
                val layout = if (part.rowS >= 0) {
                    val table = item.element as? ContentElement.Table ?: return null
                    if (part.rowS !in table.rows.indices ||
                        part.rowE !in (part.rowS + 1)..table.rows.size
                    ) return null
                    tableLayouts[part.item] ?: restoreTable(tables[part.item], table)
                        ?.also { tableLayouts[part.item] = it } ?: return null
                } else {
                    if (part.rowS != -1 || part.rowE != -1) return null
                    null
                }
                restorePart(part, item, layout) ?: return null
            }
            BookPage(parts, page.first, page.firstChar)
        }
    }

    private fun ContentElement.anchorEnd(): Int = when (this) {
        is ContentElement.Paragraph -> text.length
        is ContentElement.Heading -> styledText.length
        is ContentElement.Table -> rows.size
        else -> 0
    }

    private fun restoreTable(cached: CachedTableLayout?, table: ContentElement.Table): TableLayout? {
        cached ?: return null
        val widths = cached.cols
        val heights = cached.rowH
        val offsets = cached.offsets
        val width = cached.width ?: return null
        if (heights.size != table.rows.size || heights.any { it < 0 } ||
            offsets.size != widths.size + 1 || offsets.last() != width || width < 0 ||
            widths.any { it < 0 } || offsets.first() < 0 ||
            (1 until offsets.size).any { offsets[it - 1] > offsets[it] } ||
            !cached.scale.isFinite() || cached.scale <= 0f
        ) return null
        val grid = TableGrid.build(table.rows)
        if (grid.columnCount.coerceAtLeast(1) != widths.size) return null
        val stacked = cached.stackedCells?.let { cells ->
            if (cells.size != table.rows.size || cells.indices.any { row ->
                    cells[row].size != table.rows[row].cells.size ||
                        cells[row].any { it < 0 } || cells[row].sumOf(Int::toLong) != heights[row].toLong()
                }
            ) return null
            cells.map { it.toIntArray() }
        }
        return TableLayout(
            grid,
            widths.toIntArray(),
            heights.toIntArray(),
            cached.scale,
            offsets.toIntArray(),
            width,
            stacked,
        )
    }

    private fun restorePart(part: CachedPart, item: ReaderItem, layout: TableLayout?): PagePart? {
        val element = item.element
        val text = if (part.start >= 0) {
            val full = when (element) {
                is ContentElement.Paragraph -> element.text
                is ContentElement.Heading -> element.styledText
                else -> return null
            }
            if (part.end !in part.start..full.length) return null
            full.subSequence(part.start, part.end)
        } else {
            if (part.start != -1 || part.end != -1) return null
            null
        }
        val sideBox = if (part.sbBesideW > 0) {
            val floatHeights = part.sbFloatHeights.orEmpty()
            val float = item.publisherFloat
            if (element !is ContentElement.Paragraph || text == null ||
                part.sbEnd !in part.start..element.text.length ||
                part.sbW < 0 || part.sbH < 0 || part.sbCompH < 0 ||
                !part.sbCapSp.isFinite() || part.sbCapSp < 0f ||
                floatHeights.size != (float?.contents?.size ?: 0) ||
                floatHeights.any { it < 0 } || (part.sbStacked && float == null)
            ) return null
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
                publisherFloat = float,
                publisherFloatItemHeightsPx = floatHeights,
                publisherStacked = part.sbStacked,
            )
        } else {
            if (part.sbBesideW != 0) return null
            null
        }
        return PagePart(
            itemIndex = part.item,
            element = element,
            text = text,
            isParagraphStart = part.paraStart,
            imageHeightPx = part.imageH,
            charStart = if (layout == null) part.start else part.rowS,
            charEnd = if (layout == null) part.end else part.rowE,
            rowStart = part.rowS,
            rowEnd = part.rowE,
            tableLayout = layout,
            headerRepeated = part.hdr,
            sideBox = sideBox,
            floatImagePath = part.fImg,
            publisherBoxes = if (part.fImg == null) item.publisherBoxes else emptyList(),
            publisherStartsElement = part.pbStart,
            publisherEndsElement = part.pbEnd,
            allowsVerticalScroll = part.vScroll,
        )
    }

    @Synchronized
    fun save(file: File, key: String, items: List<ReaderItem>, pages: List<BookPage>) {
        val temporary = File(file.path + ".tmp")
        runCatching {
            file.parentFile?.mkdirs()
            val cached = cachePages(pages, key, items.size, contentSignature(items))
            writeCached(temporary, cached, sync = true)
            Files.move(temporary.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        temporary.delete()
    }

    private fun cachePages(
        pages: List<BookPage>,
        key: String? = null,
        itemCount: Int? = null,
        signature: String? = null,
    ): CachedPagination {
        val tables = linkedMapOf<Int, CachedTableLayout>()
        pages.forEach { page ->
            page.parts.forEach { part ->
                part.tableLayout?.let { layout ->
                    if (part.itemIndex !in tables) {
                        tables[part.itemIndex] = CachedTableLayout(
                            layout.colWidthsPx.toList(), layout.rowHeightsPx.toList(), layout.fontScale,
                            layout.columnOffsetsPx.toList(), layout.tableWidthPx,
                            layout.stackedCellHeightsPx?.map { it.toList() },
                        )
                    }
                }
            }
        }
        return CachedPagination(
            key = key,
            itemCount = itemCount,
            contentSignature = signature,
            version = FORMAT_VERSION,
            tables = tables,
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
                            sbFloatHeights = part.sideBox
                                ?.publisherFloatItemHeightsPx
                                ?.takeIf(List<Int>::isNotEmpty),
                            sbStacked = part.sideBox?.publisherStacked == true,
                            fImg = part.floatImagePath,
                            pbStart = part.publisherStartsElement,
                            pbEnd = part.publisherEndsElement,
                            vScroll = part.allowsVerticalScroll,
                        )
                    },
                )
            },
        )
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun writeCached(destination: File, cached: CachedPagination, sync: Boolean) {
        FileOutputStream(destination).use { output ->
            val bounded = object : FilterOutputStream(output) {
                private var written = 0L
                override fun write(value: Int) {
                    if (++written > MAX_FILE_BYTES) throw IOException("Layout cache exceeds limit")
                    out.write(value)
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    written += length
                    if (written > MAX_FILE_BYTES) throw IOException("Layout cache exceeds limit")
                    out.write(bytes, offset, length)
                }
            }
            json.encodeToStream(CachedPagination.serializer(), cached, bounded)
            if (sync) output.fd.sync()
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

        fun publisherBox(box: ReaderPublisherBox) {
            string(box.id)
            string(box.parentId)
            string(box.style.toString())
            string(box.floatSide?.name)
            boolean(box.startsAtElement)
            boolean(box.endsAtElement)
            // Inline content is derived from the source leaves, which are
            // hashed separately. Its runtime width cache is not identity.
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
        HIDDEN_TEXT_TAG,
        INLINE_FONT_FAMILY_TAG,
        INLINE_MATH_TAG,
        INLINE_RUBY_TAG,
        INLINE_TEXT_BOX_TAG,
    )

    private const val FILE_SAMPLE_BYTES = 4 * 1024
}
