package com.example.frogreader.engine

import androidx.annotation.Keep
import com.example.frogreader.ui.reader.SearchMatch
import com.example.frogreader.ui.reader.SearchResults
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Native FRB chapter/book search and search-text normalization helpers.
 *
 * Names and signatures are part of the JNI ABI.
 */
@Keep
internal object RustSearchNative {
    private const val RESULT_MAGIC = 0x52535246 // "FRSR"
    private const val ERROR_MAGIC = 0x45525246 // "FRRE"

    @Keep
    external fun searchFrbFile(
        path: String,
        metadata: ByteArray,
        query: String,
        coverCount: Int,
        hideFootnotes: Boolean,
        limit: Int,
    ): ByteArray?

    @Keep
    @JvmStatic
    external fun searchFrbHandle(
        handle: Long,
        startChapter: Int,
        chapterCount: Int,
        coverCount: Int,
        query: String,
        hideFootnotes: Boolean,
        limit: Int,
    ): ByteArray?

    @Keep
    external fun searchFrbChapter(
        payload: ByteArray,
        chapterIndex: Int,
        startItem: Int,
        query: String,
        hideFootnotes: Boolean,
        limit: Int,
    ): ByteArray?

    @Keep
    external fun searchChapterTexts(
        packed: ByteArray,
        query: String,
        chapterIndex: Int,
        chapterStartItem: Int,
        limit: Int,
    ): ByteArray?

    @Keep
    external fun normalizeText(text: String): String

    @Keep
    external fun tokenizeQuery(query: String): Array<String>

    @Keep
    external fun toFts5PrefixQuery(query: String): String?

    fun decodeSearchResponse(bytes: ByteArray, query: String): SearchResults {
        if (bytes.size < 12) throw IOException("Truncated native search response")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        when (buf.int) {
            ERROR_MAGIC -> {
                val chapterIndex = buf.int
                val msgLen = buf.int
                val safeLen = msgLen.coerceIn(0, buf.remaining())
                val msg = if (safeLen > 0) {
                    val raw = ByteArray(safeLen)
                    buf.get(raw)
                    raw.decodeToString()
                } else {
                    "Native search failed"
                }
                val formatted = if (chapterIndex >= 0) {
                    "Cannot read search chapter $chapterIndex: $msg"
                } else {
                    msg
                }
                throw IOException(formatted)
            }
            RESULT_MAGIC -> {
                val totalCount = buf.int
                val matchCount = buf.int
                if (matchCount < 0) throw IOException("Invalid native search match count")
                val matches = ArrayList<SearchMatch>(minOf(matchCount, 64))
                repeat(matchCount) {
                    if (buf.remaining() < 24) throw IOException("Truncated native search match")
                    val itemIndex = buf.int
                    val chapterIndex = buf.int
                    val charStart = buf.int
                    val before = readUtf16String(buf)
                    val matched = readUtf16String(buf)
                    val after = readUtf16String(buf)
                    matches += SearchMatch(
                        itemIndex = itemIndex,
                        chapterIndex = chapterIndex,
                        charStart = charStart,
                        before = before,
                        match = matched,
                        after = after,
                    )
                }
                if (buf.hasRemaining()) throw IOException("Trailing bytes in native search response")
                return SearchResults(query, matches, totalCount)
            }
            else -> throw IOException("Invalid native search magic")
        }
    }

    private fun readUtf16String(buf: ByteBuffer): String {
        if (buf.remaining() < Int.SIZE_BYTES) throw IOException("Truncated native snippet length")
        val charLen = buf.int
        if (charLen < 0 || charLen.toLong() * 2L > buf.remaining()) {
            throw IOException("Invalid native snippet length")
        }
        if (charLen == 0) return ""
        val chars = CharArray(charLen)
        buf.asCharBuffer().get(chars)
        buf.position(buf.position() + charLen * 2)
        return String(chars)
    }
}
