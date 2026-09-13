package com.example.frogreader.data.parser

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.BookMetadata
import com.example.frogreader.data.model.BookNavigationEntry
import com.example.frogreader.data.model.BookNavigationTarget
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.util.ArrayDeque
import java.util.Locale

/** Standalone text files use the same native element stream as packaged books. */
internal object TextBookParser {
    private val extensions = listOf(TablesExtension.create(), StrikethroughExtension.create())
    private val markdown = Parser.builder().extensions(extensions).build()
    private val html = HtmlRenderer.builder().extensions(extensions)
        .escapeHtml(true).sanitizeUrls(true).build()

    fun inferFormat(name: String?, mimeType: String?): BookFormat? {
        val extension = name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
        return when (extension) {
            "md", "markdown" -> BookFormat.MD
            "txt" -> BookFormat.TXT
            else -> when (mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
                "text/markdown", "text/x-markdown" -> BookFormat.MD
                "text/plain" -> BookFormat.TXT
                else -> null
            }
        }
    }

    /** Strict Unicode decoding; legacy Cyrillic text falls back to Windows-1251. */
    fun readText(file: File, maxBytes: Long = ReaderResourceLimits.DEFAULT.maxTextBytes): String {
        val bytes = file.inputStream().use { it.readBytesBounded(maxBytes, "text document") }
        fun prefix(vararg values: Int) = values.size <= bytes.size &&
            values.indices.all { (bytes[it].toInt() and 0xff) == values[it] }
        val (charset, offset) = when {
            prefix(0xff, 0xfe, 0, 0) || prefix(0, 0, 0xfe, 0xff) ->
                throw IOException("UTF-32 text is not supported. Save the file as UTF-8.")
            prefix(0xef, 0xbb, 0xbf) -> Charsets.UTF_8 to 3
            prefix(0xff, 0xfe) -> Charsets.UTF_16LE to 2
            prefix(0xfe, 0xff) -> Charsets.UTF_16BE to 2
            else -> Charsets.UTF_8 to 0
        }
        fun decode(encoding: Charset): String = encoding.newDecoder()
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        val text = try {
            decode(charset)
        } catch (error: CharacterCodingException) {
            if (offset != 0) throw IOException("Invalid ${charset.name()} text", error)
            decode(Charset.forName("windows-1251"))
        }
        if (text.any { (it < ' ' && it !in "\n\r\t\u000c") || it in '\u007f'..'\u009f' }) {
            throw IOException("The file contains binary data, not readable text.")
        }
        if (text.isBlank()) throw IOException("The text document is empty.")
        return text.replace("\r\n", "\n").replace('\r', '\n').replace('\u000c', '\n')
    }

    fun parseMetadata(file: File, format: BookFormat): BookMetadata {
        val text = readText(file)
        val title = if (format == BookFormat.MD) markdownBody(text).selectFirst("h1, h2, h3, h4, h5, h6")
            ?.text()?.takeIf { it.isNotBlank() } else null
        // Filename titles are assigned by the repository before the original name is lost.
        return BookMetadata(title = title, author = null, coverBytes = null)
    }

    fun parseContent(file: File, format: BookFormat): BookContent {
        val text = readText(file)
        if (format == BookFormat.TXT) {
            val elements = mutableListOf<ContentElement>()
            val paragraph = StringBuilder()
            fun flush() {
                if (paragraph.isNotEmpty()) {
                    elements += ContentElement.Paragraph(AnnotatedString(paragraph.toString()))
                    paragraph.setLength(0)
                }
            }
            for (line in text.lineSequence()) {
                if (line.isBlank()) flush() else {
                    if (paragraph.isNotEmpty()) paragraph.append('\n')
                    paragraph.append(line)
                }
            }
            flush()
            val chapters = listOf(Chapter(null, elements))
            return BookContent(chapters, language = LanguageTag.detectFromChapters(chapters))
        }

        val body = markdownBody(text)
        val usedIds = mutableSetOf<String>()
        for (heading in body.select("h1, h2, h3, h4, h5, h6")) {
            val base = heading.text().lowercase(Locale.ROOT)
                .replace(Regex("[^\\p{L}\\p{N}\\p{M}_\\-\\s]"), "")
                .trim().replace(Regex("\\s"), "-")
            var id = base
            var suffix = 0
            while (!usedIds.add(id)) id = "$base-${++suffix}"
            heading.attr("id", id)
        }
        // A standalone SAF document grants no access to neighbouring image files.
        // Preserve descriptions without accessing arbitrary paths or the network.
        body.select("img").forEach { it.replaceWith(TextNode(it.attr("alt"))) }
        val mapper = HtmlMapper(resolveImage = { null }, resolveLink = { href ->
            if (href.startsWith('#')) {
                runCatching { "#" + java.net.URI(href).fragment }.getOrDefault(href)
            } else null
        })
        val elements = mapper.map(body)
        if (elements.isEmpty()) throw IOException("The Markdown document has no readable content.")
        val title = elements.filterIsInstance<ContentElement.Heading>().firstOrNull()?.text
        val chapters = listOf(Chapter(title, elements, publisherBoxes = mapper.publisherBoxes.toList()))
        val navigation = elements.mapIndexedNotNull { index, element ->
            (element as? ContentElement.Heading)?.let {
                BookNavigationEntry(it.text, it.level - 1, BookNavigationTarget.ReadingOrder(0, index))
            }
        }
        return BookContent(
            chapters = chapters,
            navigation = navigation,
            linkTargets = mapper.anchors.filterValues { it < elements.size }.mapKeys { "#${it.key}" }
                .mapValues { 0 to it.value },
            language = LanguageTag.detectFromChapters(chapters),
        )
    }

    private fun markdownBody(text: String): org.jsoup.nodes.Element {
        val document = markdown.parse(text)
        // The HTML renderer traverses recursively. Reject pathological nesting
        // before entering it, rather than overflowing the Android thread stack.
        val pending = ArrayDeque<Pair<Node, Int>>()
        pending.add(document to 0)
        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeLast()
            if (depth > 128) throw IOException("Markdown nesting is too deep.")
            var child = node.firstChild
            while (child != null) {
                pending.add(child to depth + 1)
                child = child.next
            }
        }
        return Jsoup.parseBodyFragment(html.render(document)).body()
    }
}
