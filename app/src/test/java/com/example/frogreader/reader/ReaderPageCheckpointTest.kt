package com.example.frogreader.reader

import androidx.compose.ui.unit.Density
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.PageMargins
import com.example.frogreader.data.PageTurnAnimation
import com.example.frogreader.data.ReaderFont
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.ui.reader.PaginationSpec
import com.example.frogreader.ui.reader.ReaderPageCheckpoint
import com.example.frogreader.ui.reader.ReaderPageIndexCache
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderPageCheckpointTest {
    @get:Rule val temporary = TemporaryFolder()

    private val starts = listOf(1, 3, 3, 6)
    private val key = ByteArray(32) { 1 }
    private val itemCount = 8
    private val first = intArrayOf(0, 0, 1, 4, 1, 4, 2, 0)
    private val distant = intArrayOf(6, 1, 7, 0)

    @Test fun `reopens only committed chapters and remembers empty chapters and duplicate anchors`() = runTest {
        // With no graceful flush, the first completed chapter survives. The
        // next reader can reuse it without confusing it with a global prefix.
        open().use { it.record(3, distant) }
        open().use {
            assertEquals(setOf(3), it.completed.keys)
            assertArrayEquals(distant, it.completed.getValue(3))
            it.record(0, first)
            it.record(1, intArrayOf())
            it.flush()
        }
        open().use {
            assertEquals(setOf(0, 1, 3), it.completed.keys)
            assertArrayEquals(first, it.completed.getValue(0))
            assertArrayEquals(intArrayOf(), it.completed.getValue(1))
            assertArrayEquals(distant, it.completed.getValue(3))
        }
    }

    @Test fun `a cancelled pass flushes its latest batch and a successful pass removes it`() = runTest {
        val started = CompletableDeferred<Unit>()
        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            open().use {
                try {
                    it.record(0, first)
                    it.record(3, distant)
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { it.flush() }
                }
            }
        }
        started.await()
        writer.cancelAndJoin()
        open().use {
            assertEquals(setOf(0, 3), it.completed.keys)
            it.remove()
        }
        open().use { assertTrue(it.completed.isEmpty()) }
    }

    @Test fun `validates key coordinates and the complete payload before resuming`() = runTest {
        open().use { it.record(0, first) }
        val file = checkpointFiles().single()
        val snapshot = file.readBytes()
        val otherKey = ByteArray(32) { 2 }
        open(otherKey).use { assertTrue(it.completed.isEmpty()) }
        open(chapterStarts = listOf(1, 4, 4, 6)).use { assertTrue(it.completed.isEmpty()) }
        open(items = 9).use { assertTrue(it.completed.isEmpty()) }

        file.writeBytes(snapshot.copyOf(snapshot.size - 1))
        open().use { assertTrue(it.completed.isEmpty()) }
        file.writeBytes(snapshot.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        open().use { assertTrue(it.completed.isEmpty()) }
        file.writeBytes(snapshot)
        open().use { assertArrayEquals(first, it.completed.getValue(0)) }
    }

    @Test fun `superseded writers cannot overwrite or remove new checkpoints and other keys are bounded`() = runTest {
        open().use { older ->
            older.record(0, first)
            open().use { newer ->
                newer.record(3, distant)
                older.record(1, intArrayOf())
                older.flush()
                older.remove()
            }
        }
        open().use {
            assertEquals(setOf(0, 3), it.completed.keys)
            assertArrayEquals(distant, it.completed.getValue(3))
        }
        val secondKey = ByteArray(32) { 2 }
        val thirdKey = ByteArray(32) { 3 }
        open(secondKey).use { it.record(0, first) }
        assertEquals(2, checkpointFiles().size)
        open(thirdKey).use { it.record(0, first) }
        assertEquals(2, checkpointFiles().size)
        open(thirdKey).use { assertArrayEquals(first, it.completed.getValue(0)) }
    }

    @Test fun `rejects anchors beyond their chapter or out of order`() = runTest {
        open().use { checkpoint ->
            for (anchors in listOf(intArrayOf(3, 0), intArrayOf(1, -1), intArrayOf(2, 4, 2, 1), intArrayOf(0))) {
                val error = runCatching { checkpoint.record(0, anchors) }.exceptionOrNull()
                assertTrue(error is IllegalArgumentException)
            }
        }
        assertTrue(checkpointFiles().isEmpty())
    }

    @Test fun `process death recovery preserves theme-invariant checkpoint and produces identical rpi anchors`() = runTest {
        val density = Density(2f)
        val viewportWidthPx = 720
        val contentHeightPx = 1080
        val contentKey = "frb-content-generation-1"
        val environment = "rtl=false|locale=en-US|robolectric"
        val baseSettings = ReaderSettings(
            font = ReaderFont.SERIF,
            fontSizeSp = 18f,
            lineHeight = 1.5f,
            pageMargins = PageMargins.NORMAL,
            pageMarginDp = 20,
            startChaptersOnNewPage = true,
        )
        fun specFor(settings: ReaderSettings): PaginationSpec {
            val horizontalInsetPx = (settings.sideMarginDp * 2 * density.density).toInt()
            return PaginationSpec(
                contentWidthPx = viewportWidthPx - horizontalInsetPx,
                contentHeightPx = contentHeightPx,
                density = density,
                settings = settings,
                fontSize = settings.fontSizeSp,
                language = "en",
            )
        }
        fun layoutKeyFor(settings: ReaderSettings): ByteArray =
            ReaderPageIndexCache.key(contentKey, specFor(settings), coverPath = null, environment = environment)

        val chapter2 = intArrayOf(3, 0, 4, 12, 5, 0)
        val allChapters = mapOf(
            0 to first,
            1 to intArrayOf(),
            2 to chapter2,
            3 to distant,
        )
        val baseKey = layoutKeyFor(baseSettings)

        // Baseline uninterrupted pass saved directly to .rpi.
        val uninterruptedAnchors = starts.indices.flatMap { allChapters.getValue(it).asList() }.toIntArray()
        val uninterruptedRpi = File(temporary.root, "uninterrupted.rpi")
        assertTrue(ReaderPageIndexCache.save(uninterruptedRpi, baseKey, itemCount, uninterruptedAnchors))

        // Simulate partial pagination from preferredChapter = 3 outward (chapters 3 and 2 finish before process death).
        open(baseKey).use { checkpoint ->
            checkpoint.record(3, distant)
            checkpoint.record(2, chapter2)
            checkpoint.flush()
        }

        // Theme and non-layout visual changes must preserve the exact layout key and reuse the checkpointed chapters.
        for (theme in AppTheme.entries) {
            val themeAdjusted = baseSettings.copy(
                invertImages = theme == AppTheme.OLED,
                pdfTheme = theme,
                pageTurnAnimation = PageTurnAnimation.SLIDE,
                showPageCounter = true,
                showTotalPageCount = true,
                showChapterPagesLeft = true,
            )
            val themeKey = layoutKeyFor(themeAdjusted)
            assertArrayEquals("Theme $theme should preserve layout key", baseKey, themeKey)
            open(themeKey).use { resumed ->
                assertEquals(setOf(2, 3), resumed.completed.keys)
                assertArrayEquals(chapter2, resumed.completed.getValue(2))
                assertArrayEquals(distant, resumed.completed.getValue(3))
            }
        }

        // Typography and margin changes must alter the layout key and ignore the old checkpoint.
        val layoutInvalidatingVariants = listOf(
            baseSettings.copy(fontSizeSp = 20f),
            baseSettings.copy(lineHeight = 1.65f),
            baseSettings.copy(font = ReaderFont.SANS),
            baseSettings.copy(justify = true),
            baseSettings.copy(hyphenation = true),
            baseSettings.copy(pageMarginDp = 12),
            baseSettings.copy(pageMarginDp = null, pageMargins = PageMargins.WIDE),
        )
        for (variant in layoutInvalidatingVariants) {
            val variantKey = layoutKeyFor(variant)
            assertFalse(baseKey.contentEquals(variantKey))
            open(variantKey).use { fresh ->
                assertTrue(fresh.completed.isEmpty())
            }
        }

        // Reopen after simulated process death under a theme change, measure only remaining chapters outward (1, then 0),
        // and verify byte-for-byte identical .rpi output to the uninterrupted pass.
        val themeChangedKey = layoutKeyFor(baseSettings.copy(invertImages = true, pdfTheme = AppTheme.SEPIA))
        val resumedRpi = File(temporary.root, "resumed.rpi")
        open(themeChangedKey).use { checkpoint ->
            val completed = HashMap(checkpoint.completed)
            assertEquals(setOf(2, 3), completed.keys)
            val unmeasuredInOutwardOrder = listOf(1, 0).filterNot(completed::containsKey)
            assertEquals(listOf(1, 0), unmeasuredInOutwardOrder)
            for (chapter in unmeasuredInOutwardOrder) {
                val anchors = allChapters.getValue(chapter)
                checkpoint.record(chapter, anchors)
                completed[chapter] = anchors
            }
            val mergedAnchors = starts.indices.flatMap { completed.getValue(it).asList() }.toIntArray()
            assertArrayEquals(uninterruptedAnchors, mergedAnchors)
            assertTrue(ReaderPageIndexCache.save(resumedRpi, themeChangedKey, itemCount, mergedAnchors))
            checkpoint.remove()
        }
        assertTrue(checkpointFiles().isEmpty())
        assertArrayEquals(uninterruptedRpi.readBytes(), resumedRpi.readBytes())
        val loaded = requireNotNull(ReaderPageIndexCache.load(resumedRpi, themeChangedKey, itemCount))
        assertArrayEquals(uninterruptedAnchors, loaded.anchors)
    }

    private fun open(
        layoutKey: ByteArray = key,
        chapterStarts: List<Int> = starts,
        items: Int = itemCount,
    ) = ReaderPageCheckpoint.open(temporary.root, layoutKey, chapterStarts, items)

    private fun checkpointFiles(): List<File> =
        temporary.root.listFiles().orEmpty().filter { it.name.endsWith(".bin") }
}
