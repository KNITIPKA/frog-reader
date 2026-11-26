package com.example.frogreader.reader

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.BlockStyle
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.FloatImage
import com.example.frogreader.engine.RustBridge
import com.example.frogreader.ui.reader.BookPage
import com.example.frogreader.ui.reader.PagePart
import com.example.frogreader.ui.reader.ReaderItem
import com.example.frogreader.ui.reader.ReaderPageLayoutCache
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderPageLayoutCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    private val file get() = ReaderPageLayoutCache.fileFor(temporary.root, "book")
    private val key = ByteArray(32) { 1 }
    private val otherKey = ByteArray(32) { 2 }
    private val itemCount = 800

    @Test
    fun `repeated anchors preserve ordinals across frames and source groups`() = runTest {
        assertTrue("Tests require the real host JNI library", RustBridge.shared.isAvailable)
        val floatPath = "/test/publisher-float.jpg"
        val paragraph = ContentElement.Paragraph(
            AnnotatedString("Publisher text"),
            block = BlockStyle(floatImage = FloatImage(floatPath, widthFrac = 0.3f, left = true)),
        )
        val floatPage = BookPage(
            listOf(PagePart(255, paragraph, imageHeightPx = 84, floatImagePath = floatPath)),
            firstItemIndex = 255,
        )
        val textPage = BookPage(
            listOf(PagePart(255, paragraph, paragraph.text, charStart = 0, charEnd = paragraph.text.length)),
            firstItemIndex = 255,
        )
        val followingPage = BookPage(
            listOf(PagePart(256, ContentElement.Divider), PagePart(257, ContentElement.Divider)),
            firstItemIndex = 256,
        )
        val pages = (0 until 31).map(::dividerPage) + listOf(floatPage, textPage, followingPage, dividerPage(512))
        requireNotNull(ReaderPageLayoutCache.build(file, key, itemCount) { emit -> pages.forEach(emit) })
        val index = requireNotNull(ReaderPageLayoutCache.loadIndex(file, key, itemCount))
        assertEquals(pages.size, index.size)
        assertEquals(31, index.firstPageAtOrAfter(255))
        assertEquals(32, index.pageAt(255, 0))
        assertNull(ReaderPageLayoutCache.loadIndex(file, otherKey, itemCount))
        assertNull(ReaderPageLayoutCache.loadIndex(file, key, itemCount + 1))
        assertNull(ReaderPageLayoutCache.loadBatches(file, otherKey, itemCount, index, 31, 34))

        val batches = requireNotNull(ReaderPageLayoutCache.loadBatches(file, key, itemCount, index, 31, 34))
        val required = batches.flatMap { it.requiredItemIndices.asList() }.toSortedSet()
        assertEquals(setOf(255, 256, 257), required)
        val sparse = object : AbstractList<ReaderItem>() {
            override val size = itemCount
            override fun get(index: Int): ReaderItem = when (index) {
                255 -> ReaderItem(0, paragraph)
                256, 257 -> ReaderItem(1, ContentElement.Divider)
                else -> error("Unexpected read of source item $index")
            }
        }
        val restored = batches.flatMap { requireNotNull(it.restore(sparse)) }
        assertEquals(listOf(255, 255, 256), restored.map(BookPage::firstItemIndex))
        assertEquals(floatPath, restored[0].parts.single().floatImagePath)
        assertEquals(84, restored[0].parts.single().imageHeightPx)
        assertEquals(paragraph.text, restored[1].parts.single().text)
        assertEquals(listOf(256, 257), restored[2].parts.map(PagePart::itemIndex))
    }

    @Test
    fun `a damaged page group is isolated from the anchor index and distant pages`() = runTest {
        requireNotNull(ReaderPageLayoutCache.build(file, key, itemCount) { emit ->
            emit(dividerPage(10))
            emit(dividerPage(700))
        })
        corruptPayload(file, block = 2) // First geometry group, after the two FRB prefix blocks.

        val index = requireNotNull(ReaderPageLayoutCache.loadIndex(file, key, itemCount))
        assertArrayEquals(intArrayOf(10, 0, 700, 0), index.anchors)
        assertNull(ReaderPageLayoutCache.loadBatches(file, key, itemCount, index, 0, 1))
        val distant = requireNotNull(ReaderPageLayoutCache.loadBatches(file, key, itemCount, index, 1, 2))
        assertArrayEquals(intArrayOf(700, 0), distant.single().anchors)
        assertTrue(RustBridge.shared.isAvailable)

        corruptPayload(file, block = lastBlock(file))
        assertNull(ReaderPageLayoutCache.loadIndex(file, key, itemCount))
    }

    @Test
    fun `failed and cancelled builds leave the published layout readable`() = runTest {
        requireNotNull(ReaderPageLayoutCache.build(file, key, itemCount) { it(dividerPage(10)) })
        val original = file.readBytes()
        val failed = ReaderPageLayoutCache.build(file, otherKey, itemCount) { emit ->
            emit(dividerPage(20))
            emit(dividerPage(700))
            assertArrayEquals(
                intArrayOf(10, 0),
                requireNotNull(ReaderPageLayoutCache.loadIndex(file, key, itemCount)).anchors,
            )
            assertNull(ReaderPageLayoutCache.loadIndex(file, otherKey, itemCount))
            throw IOException("Interrupted layout producer")
        }
        assertNull(failed)
        assertArrayEquals(original, file.readBytes())

        var wrotePartialLayout = false
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
            ReaderPageLayoutCache.build(file, otherKey, itemCount) { emit ->
                emit(dividerPage(20))
                emit(dividerPage(700))
                wrotePartialLayout = true
                awaitCancellation()
            }
        }
        assertTrue(wrotePartialLayout)
        cancelled.cancelAndJoin()
        assertArrayEquals(original, file.readBytes())
        assertEquals(listOf(file.name), file.parentFile!!.list()!!.toList())

        requireNotNull(ReaderPageLayoutCache.build(file, otherKey, itemCount) { it(dividerPage(700)) })
        assertNull(ReaderPageLayoutCache.loadIndex(file, key, itemCount))
        assertArrayEquals(
            intArrayOf(700, 0),
            requireNotNull(ReaderPageLayoutCache.loadIndex(file, otherKey, itemCount)).anchors,
        )
    }

    @Test
    fun `empty and multi-group layout blocks round-trip across batch boundaries without scratch block files`() = runTest {
        val pages = (0 until 35).map { dividerPage(it * 2) } +
            listOf(dividerPage(520), dividerPage(521), dividerPage(540))
        val built = requireNotNull(
            ReaderPageLayoutCache.build(file, key, itemCount) { emit ->
                pages.forEach(emit)
            },
        )
        assertEquals(pages.size, built.size)
        assertEquals(listOf(file.name), file.parentFile!!.list()!!.toList())

        val loadedIndex = requireNotNull(ReaderPageLayoutCache.loadIndex(file, key, itemCount))
        assertArrayEquals(built.anchors, loadedIndex.anchors)

        // Span across two batches in group 0, an empty group 1 (items 256..511),
        // group 2 (items 512..767), and trailing empty group 3 (items 768..799).
        val allBatches = requireNotNull(
            ReaderPageLayoutCache.loadBatches(file, key, itemCount, loadedIndex, 0, pages.size),
        )
        assertEquals(pages.size, allBatches.sumOf { it.size })
        assertArrayEquals(
            pages.flatMap { listOf(it.firstItemIndex, it.firstCharOffset) }.toIntArray(),
            allBatches.flatMap { it.anchors.asList() }.toIntArray(),
        )

        val crossEmptyGroupSlice = requireNotNull(
            ReaderPageLayoutCache.loadBatches(file, key, itemCount, loadedIndex, 33, 37),
        )
        assertArrayEquals(
            intArrayOf(66, 0, 68, 0, 520, 0, 521, 0),
            crossEmptyGroupSlice.flatMap { it.anchors.asList() }.toIntArray(),
        )
    }

    @Test
    fun `out-of-order and out-of-range page coordinates are rejected without replacing published layout`() = runTest {
        requireNotNull(ReaderPageLayoutCache.build(file, key, itemCount) { it(dividerPage(12)) })
        val published = file.readBytes()

        // Decreasing group order (group 1 -> group 0) must fail.
        assertNull(
            ReaderPageLayoutCache.build(file, otherKey, itemCount) { emit ->
                emit(dividerPage(300))
                emit(dividerPage(10))
            },
        )
        // Negative item index must fail.
        assertNull(
            ReaderPageLayoutCache.build(file, otherKey, itemCount) { emit ->
                emit(dividerPage(-1))
            },
        )
        // Item index at or beyond itemCount must fail.
        assertNull(
            ReaderPageLayoutCache.build(file, otherKey, itemCount) { emit ->
                emit(dividerPage(itemCount))
            },
        )
        // Empty emission must fail.
        assertNull(
            ReaderPageLayoutCache.build(file, otherKey, itemCount) { },
        )

        assertArrayEquals(published, file.readBytes())
        assertEquals(listOf(file.name), file.parentFile!!.list()!!.toList())
    }

    private fun dividerPage(item: Int) = BookPage(listOf(PagePart(item, ContentElement.Divider)), item)

    private fun lastBlock(file: File): Int {
        val header = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return header.getInt(20) + 1 // Chapter-like entries plus two prefix blocks, minus one.
    }

    private fun corruptPayload(file: File, block: Int) {
        val bytes = file.readBytes()
        val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val indexStart = view.getInt(12) + view.getInt(24)
        val entryStart = indexStart + block * view.getInt(28)
        val payload = Math.toIntExact(view.getLong(entryStart))
        val length = Math.toIntExact(view.getLong(entryStart + 8))
        assertTrue(length > 0)
        bytes.fill(0, payload, payload + length)
        file.writeBytes(bytes)
    }
}
