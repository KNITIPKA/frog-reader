package com.example.frogreader.data

import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookIndex
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.parser.EpubParser
import com.example.frogreader.data.parser.ReaderResourceLimits
import java.io.File
import java.io.IOException

/** Resolves exact logical chapters from the source until a complete FRB is ready. */
internal class EpubSourceContentSession(
    override val index: BookIndex,
    private val source: File,
    private val imagesDir: File,
    private val isCurrentSource: () -> Boolean,
) : IndexedContentSession {
    private val modified = source.lastModified()
    private val size = source.length()
    override val contentKey = "epub-source:${source.path}"

    override fun readChapter(index: Int): Chapter? = readChapters(index, index) {}?.singleOrNull()

    override fun readChapters(first: Int, last: Int, checkActive: () -> Unit): List<Chapter>? {
        if (!sourceMatches()) return null
        return try {
            EpubParser.parseTargetWindow(source, imagesDir, ReaderResourceLimits.DEFAULT, index, first, last, checkActive)
                ?.chapters?.takeIf { sourceMatches() }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun sourceMatches(): Boolean =
        isCurrentSource() && source.isFile && source.length() == size && source.lastModified() == modified

    override fun readGlobals(): BookContent? = null
    override fun close() = Unit
}
