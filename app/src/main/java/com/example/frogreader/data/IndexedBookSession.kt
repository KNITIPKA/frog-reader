package com.example.frogreader.data

import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookIndex
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.engine.RustBridge
import com.example.frogreader.engine.RustSearchNative
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/** A bounded reading-order window in book coordinates, excluding the reader cover. */
internal data class BookChapterWindow(
    val firstChapterIndex: Int,
    val chapters: List<Chapter>,
    val firstElement: Int,
    /** Element count of the complete book, not just this window. */
    val totalElements: Int,
)

/** Owns the native generation and its extracted-resource pin until explicit close. */
internal class IndexedBookSession private constructor(
    private var native: IndexedContentSession,
    private val resources: Closeable,
    private val ioDispatcher: CoroutineDispatcher,
) : Closeable {
    val index: BookIndex = native.index
    val startedFromEpubSource: Boolean = native is EpubSourceContentSession
    @Volatile var contentKey: String = native.contentKey
        private set
    private val lock = Any()
    private var closed = false

    suspend fun readChapter(chapterIndex: Int): Chapter? = withContext(ioDispatcher) {
        val raw = synchronized(lock) {
            if (closed) return@withContext null
            native.readChapterBlockBytes(chapterIndex)
                ?: return@withContext native.readChapter(chapterIndex)
        }
        val expected = index.chapters.getOrNull(chapterIndex) ?: return@withContext null
        val chapter = try {
            FrbModelCodec.decode(raw, Chapter.serializer())
        } catch (_: IOException) {
            return@withContext null
        } catch (_: SerializationException) {
            return@withContext null
        } catch (_: IllegalArgumentException) {
            return@withContext null
        }
        if (chapter.elements.size != expected.elementCount ||
            chapter.title != expected.title ||
            chapter.depth != expected.depth ||
            !contentResourcesExist(BookContent(listOf(chapter)))
        ) {
            return@withContext null
        }
        chapter
    }

    suspend fun readChapterBlockBytes(chapterIndex: Int): ByteArray? = withContext(ioDispatcher) {
        synchronized(lock) { if (closed) null else native.readChapterBlockBytes(chapterIndex) }
    }

    internal suspend fun searchNativeChapterRange(
        startChapter: Int,
        chapterCount: Int,
        coverCount: Int,
        query: String,
        hideFootnotes: Boolean,
        limit: Int,
    ): ByteArray? = withContext(ioDispatcher) {
        synchronized(lock) {
            if (closed) return@synchronized null
            native.withNativeHandle { handle ->
                RustBridge.shared.withFallback(true, { null }) {
                    RustSearchNative.searchFrbHandle(
                        handle = handle,
                        startChapter = startChapter,
                        chapterCount = chapterCount,
                        coverCount = coverCount,
                        query = query,
                        hideFootnotes = hideFootnotes,
                        limit = limit,
                    )
                }
            }
        }
    }

    suspend fun readGlobals(): BookContent? = withContext(ioDispatcher) {
        synchronized(lock) { if (closed) null else native.readGlobals() }
    }

    /** Reads at most three chapters, without walking or decoding an earlier prefix. */
    suspend fun loadWindow(targetFlatElement: Int, neighbors: Int = 1): BookChapterWindow? {
        require(neighbors in 0..1) { "A reading window supports zero or one neighbor" }
        if (targetFlatElement !in 0 until index.totalElements || index.chapters.isEmpty()) return null
        // Upper-bound search also skips empty chapters sharing the next start.
        var low = 0
        var high = index.chapters.size
        while (low < high) {
            val middle = (low + high).ushr(1)
            if (index.chapters[middle].startElement <= targetFlatElement) low = middle + 1
            else high = middle
        }
        val targetChapter = low - 1
        if (targetChapter < 0) return null
        val target = index.chapters[targetChapter]
        if (targetFlatElement - target.startElement !in 0 until target.elementCount) return null
        val first = (targetChapter - neighbors).coerceAtLeast(0)
        val last = (targetChapter + neighbors).coerceAtMost(index.chapters.lastIndex)
        return withContext(ioDispatcher) {
            val context = currentCoroutineContext()
            synchronized(lock) {
                if (closed) return@synchronized null
                val chapters = native.readChapters(first, last, context::ensureActive) ?: return@synchronized null
                BookChapterWindow(first, chapters, index.chapters[first].startElement, index.totalElements)
            }
        }
    }

    /** Adopt the durable FRB for later IO, without replacing the visible source window. */
    suspend fun adoptPreparedContent(openNative: () -> IndexedContentSession?): Boolean = withContext(ioDispatcher) {
        val replacement = openNative() ?: return@withContext false
        var accepted = false
        try {
            synchronized(lock) {
                val source = native as? EpubSourceContentSession
                if (closed || source?.sourceMatches() != true ||
                    replacement.index.chapters != index.chapters || replacement.index.epubSourceRanges != index.epubSourceRanges
                ) return@synchronized false
                native.close()
                native = replacement
                contentKey = replacement.contentKey
                accepted = true
                true
            }
        } finally {
            if (!accepted) replacement.close()
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            try { native.close() } finally { resources.close() }
        }
    }

    internal companion object {
        /**
         * Keep ownership outside withContext: cancellation can discard its return
         * value after native open has completed. Every undelivered session closes.
         */
        suspend fun open(
            resources: Closeable,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            openNative: () -> IndexedContentSession?,
        ): IndexedBookSession? {
            var opened: IndexedBookSession? = null
            var delivered = false
            var failure: Throwable? = null
            try {
                withContext(ioDispatcher) {
                    openNative()?.let { opened = IndexedBookSession(it, resources, ioDispatcher) }
                }
                delivered = opened != null
                return opened
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                if (!delivered) {
                    try {
                        withContext(NonCancellable + ioDispatcher) {
                            opened?.close() ?: resources.close()
                        }
                    } catch (closeError: Throwable) {
                        if (failure == null) throw closeError
                        failure.addSuppressed(closeError)
                    }
                }
            }
        }
    }
}
