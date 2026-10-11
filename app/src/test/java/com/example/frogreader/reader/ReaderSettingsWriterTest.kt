package com.example.frogreader.ui.reader

import com.example.frogreader.data.PageTurnAnimation
import com.example.frogreader.data.ReaderSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSettingsWriterTest {
    @Test
    fun `rapid counter changes retain the selected mode while hidden`() = runTest {
        val release = CompletableDeferred<Unit>()
        val saved = mutableListOf<ReaderSettings>()
        val writer = ReaderSettingsWriter(this) {
            release.await()
            saved += it
        }
        val current = ReaderSettings()
        writer.update(current) { it.copy(showChapterPagesLeft = true) }
        runCurrent()
        writer.update(current) { it.copy(showPageCounter = false) }

        assertEquals(false, writer.pending.value?.showPageCounter)
        assertEquals(true, writer.pending.value?.showChapterPagesLeft)
        release.complete(Unit)
        runCurrent()
        assertEquals(current.copy(showPageCounter = false, showChapterPagesLeft = true), saved.last())
        assertNull(writer.pending.value)
    }

    @Test
    fun `selection is immediately visible while a slow write is suspended`() = runTest {
        val release = CompletableDeferred<Unit>()
        val saved = mutableListOf<ReaderSettings>()
        val writer = ReaderSettingsWriter(this) { settings ->
            release.await()
            saved += settings
        }
        val current = ReaderSettings()
        val requested = current.copy(pageTurnAnimation = PageTurnAnimation.PAGE_CURL)

        writer.update(current) { requested }
        assertEquals(requested, writer.pending.value)
        runCurrent()
        assertEquals(requested, writer.pending.value)
        assertEquals(emptyList<ReaderSettings>(), saved)

        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(requested), saved)
        assertNull(writer.pending.value)
    }

    @Test
    fun `older completion preserves newest selection and queued middle choices are skipped`() = runTest {
        val firstWrite = CompletableDeferred<Unit>()
        val lastWrite = CompletableDeferred<Unit>()
        val started = mutableListOf<PageTurnAnimation>()
        val completed = mutableListOf<PageTurnAnimation>()
        val writer = ReaderSettingsWriter(this) { settings ->
            val animation = settings.pageTurnAnimation
            started += animation
            if (animation == PageTurnAnimation.PAGE_CURL) firstWrite.await() else lastWrite.await()
            completed += animation
        }
        val current = ReaderSettings()
        writer.update(current) { it.copy(pageTurnAnimation = PageTurnAnimation.PAGE_CURL) }
        runCurrent()
        writer.update(current) { it.copy(pageTurnAnimation = PageTurnAnimation.NONE) }
        runCurrent()
        writer.update(current) { it.copy(pageTurnAnimation = PageTurnAnimation.CASCADE) }
        runCurrent()
        assertEquals(PageTurnAnimation.CASCADE, writer.pending.value?.pageTurnAnimation)
        assertEquals(listOf(PageTurnAnimation.PAGE_CURL), started)

        firstWrite.complete(Unit)
        runCurrent()
        assertEquals(listOf(PageTurnAnimation.PAGE_CURL, PageTurnAnimation.CASCADE), started)
        assertEquals(PageTurnAnimation.CASCADE, writer.pending.value?.pageTurnAnimation)

        lastWrite.complete(Unit)
        runCurrent()
        assertEquals(listOf(PageTurnAnimation.PAGE_CURL, PageTurnAnimation.CASCADE), completed)
        assertNull(writer.pending.value)
    }

    @Test
    fun `rapid edits preserve changes made before persisted state catches up`() = runTest {
        val release = CompletableDeferred<Unit>()
        val saved = mutableListOf<ReaderSettings>()
        val writer = ReaderSettingsWriter(this) {
            release.await()
            saved += it
        }
        val current = ReaderSettings()
        writer.update(current) { it.copy(fontSizeSp = 22f) }
        writer.update(current) { it.copy(pageTurnAnimation = PageTurnAnimation.PAGE_CURL) }

        assertEquals(22f, writer.pending.value?.fontSizeSp)
        release.complete(Unit)
        runCurrent()
        assertEquals(
            current.copy(fontSizeSp = 22f, pageTurnAnimation = PageTurnAnimation.PAGE_CURL),
            saved.last(),
        )
    }

    @Test
    fun `closing the reader still saves its latest queued choice`() = runTest {
        val owner = Job(coroutineContext[Job])
        val release = CompletableDeferred<Unit>()
        val saved = mutableListOf<PageTurnAnimation>()
        val writer = ReaderSettingsWriter(CoroutineScope(coroutineContext + owner)) {
            release.await()
            saved += it.pageTurnAnimation
        }
        val current = ReaderSettings()
        writer.update(current) { it.copy(pageTurnAnimation = PageTurnAnimation.PAGE_CURL) }
        writer.update(current) { it.copy(pageTurnAnimation = PageTurnAnimation.NONE) }
        owner.cancel()
        release.complete(Unit)
        runCurrent()

        assertEquals(listOf(PageTurnAnimation.PAGE_CURL, PageTurnAnimation.NONE), saved)
        assertNull(writer.pending.value)
    }

    @Test
    fun `repeated selection does not schedule another write`() = runTest {
        val saved = mutableListOf<ReaderSettings>()
        val writer = ReaderSettingsWriter(this) { saved += it }
        val current = ReaderSettings(pageTurnAnimation = PageTurnAnimation.SLIDE)
        writer.update(current) { it.copy(pageTurnAnimation = PageTurnAnimation.SLIDE) }
        runCurrent()
        assertEquals(emptyList<ReaderSettings>(), saved)
        assertNull(writer.pending.value)
    }

    @Test
    fun `failed write releases pending state instead of keeping an unsaved choice`() {
        lateinit var writer: ReaderSettingsWriter
        assertThrows(IOException::class.java) {
            runTest {
                writer = ReaderSettingsWriter(this) { throw IOException("Cannot save settings") }
                writer.update(ReaderSettings()) { it.copy(pageTurnAnimation = PageTurnAnimation.PAGE_CURL) }
                runCurrent()
            }
        }
        assertNull(writer.pending.value)
    }
}
