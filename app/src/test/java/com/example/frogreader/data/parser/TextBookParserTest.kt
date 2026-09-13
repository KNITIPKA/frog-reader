package com.example.frogreader.data.parser

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.example.frogreader.data.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.charset.Charset

class TextBookParserTest {
    @get:Rule val temp = TemporaryFolder()

    private fun file(name: String, text: String) = File(temp.root, name).apply { writeText(text) }
    private fun content(text: String, format: BookFormat = BookFormat.MD) =
        BookParsers.parseContent(file("source", text), format, temp.root)

    @Test fun `TXT keeps literal markup indentation and line breaks`() {
        val content = content("  <b>Literal & text</b>\r\n# Not a heading\r\n\r\n**Not bold**\rLast line", BookFormat.TXT)
        val paragraphs = content.chapters.single().elements.filterIsInstance<ContentElement.Paragraph>()
        assertEquals(listOf("  <b>Literal & text</b>\n# Not a heading", "**Not bold**\nLast line"), paragraphs.map { it.text.text })
        assertTrue(paragraphs.all { it.text.spanStyles.isEmpty() })
        assertTrue(content.navigation.isEmpty())
        assertNull(BookParsers.parseMetadata(file("random-id.txt", "Hello"), BookFormat.TXT).title)
    }

    @Test fun `Markdown renders formatting code lists quotes tables and navigation`() {
        val content = content("""
            # **Книжка**

            A **bold** and *italic* word, ~~old~~ and `code`.

            > A quote

            - First
            - Second

            3. Third
            4. Fourth

            ```kotlin
              val x = "<tag>"
                println(x)
            ```

            | One | Two |
            | --- | --- |
            | A | B |

            ## Розділ

            [Back](#книжка) and [Web](https://example.com).

            ---
        """.trimIndent())
        val elements = content.chapters.single().elements
        assertEquals(listOf("Книжка", "Розділ"), content.navigation.map { it.title })
        assertEquals(listOf(0, 1), content.navigation.map { it.depth })
        for (entry in content.navigation) {
            val target = entry.target as BookNavigationTarget.ReadingOrder
            assertEquals(entry.title, (elements[target.elementIndex] as ContentElement.Heading).text)
        }
        assertEquals(0 to 0, content.linkTargets["#книжка"])
        val paragraphs = elements.filterIsInstance<ContentElement.Paragraph>()
        val styled = paragraphs.first { it.text.text.contains("bold") }.text
        val bold = styled.text.indexOf("bold")
        assertTrue(styled.spanStyles.any { it.start <= bold && it.end >= bold + 4 && it.item.fontWeight == FontWeight.Bold })
        assertTrue(styled.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
        assertTrue(styled.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
        assertTrue(paragraphs.any { it.text.text.contains("A quote") && it.style == ParagraphStyle.QUOTE })
        assertTrue(paragraphs.any { it.text.text.contains("First") })
        assertTrue(paragraphs.any { it.text.text.contains("3.") && it.text.text.contains("Third") })
        assertTrue(paragraphs.any { it.text.text.contains("  val x = \"<tag>\"\n    println(x)") })
        assertEquals("A", elements.filterIsInstance<ContentElement.Table>().single().rows.last().cells.first().text.text)
        assertTrue(elements.contains(ContentElement.Divider))
        val links = paragraphs.first { it.text.text.contains("Back") }.text
        assertEquals("#книжка", links.getStringAnnotations(LINK_TAG, 0, links.length).single().item)
        assertEquals("https://example.com", links.getStringAnnotations(EXTERNAL_LINK_TAG, 0, links.length).single().item)
    }

    @Test fun `setext and duplicate headings get stable targets and metadata`() {
        val source = file("test.md", "Title\n=====\n\n## Repeat\n\nText\n\n## Repeat\n\n[Again](#repeat-1)")
        assertEquals("Title", BookParsers.parseMetadata(source, BookFormat.MD).title)
        val content = BookParsers.parseContent(source, BookFormat.MD, temp.root)
        val destination = content.linkTargets.getValue("#repeat-1")
        assertEquals("Repeat", (content.chapters[destination.first].elements[destination.second] as ContentElement.Heading).text)
        assertNotEquals(content.linkTargets["#repeat"], destination)
    }

    @Test fun `HTML stays literal images keep alt text and unsafe links are inert`() {
        val elements = content("<script>alert(1)</script>\n\n![Diagram](../../secret.png)\n\n[Bad](javascript:alert(1))").chapters.single().elements
        val text = elements.filterIsInstance<ContentElement.Paragraph>().joinToString("\n") { it.text.text }
        assertTrue(text.contains("<script>alert(1)</script>"))
        assertTrue(text.contains("Diagram"))
        assertTrue(elements.none { it is ContentElement.Image })
        assertTrue(elements.filterIsInstance<ContentElement.Paragraph>().all {
            it.text.getStringAnnotations(EXTERNAL_LINK_TAG, 0, it.text.length).isEmpty()
        })
    }

    @Test fun `Unicode BOMs and legacy Cyrillic decode without replacement characters`() {
        val text = "Українська книжка — їжак і ґанок"
        val encodings = listOf(
            Charsets.UTF_8 to byteArrayOf(),
            Charsets.UTF_8 to byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()),
            Charsets.UTF_16LE to byteArrayOf(0xff.toByte(), 0xfe.toByte()),
            Charsets.UTF_16BE to byteArrayOf(0xfe.toByte(), 0xff.toByte()),
            Charset.forName("windows-1251") to byteArrayOf(),
        )
        for ((charset, bom) in encodings) {
            val source = file("encoding.txt", "").apply { writeBytes(bom + text.toByteArray(charset)) }
            assertEquals(charset.name(), text, TextBookParser.readText(source))
        }
    }

    @Test fun `detection uses original name or MIME and preserves bytes`() {
        for ((name, mime, expected) in listOf(
            Triple("Notes.MD", "text/plain", BookFormat.MD),
            Triple("notes.markdown", null, BookFormat.MD),
            Triple("notes.TXT", "application/octet-stream", BookFormat.TXT),
            Triple(null, "text/markdown; charset=UTF-8", BookFormat.MD),
            Triple(null, "text/plain", BookFormat.TXT),
        )) {
            val source = file("temporary.bin", "# Book\n\nHello")
            val bytes = source.readBytes()
            val (format, stored) = BookParsers.detectAndStore(source, temp.root, "stored", name, mime)
            assertEquals(expected, format)
            assertEquals(expected.name.lowercase(), stored.extension)
            assertArrayEquals(bytes, stored.readBytes())
        }
    }

    @Test fun `explicit text names preserve content that resembles ebook signatures`() {
        for ((name, text, expected) in listOf(
            Triple("notes.txt", "PK is a character in this story.", BookFormat.TXT),
            Triple("notes.md", "# XML example\n\n`<FictionBook>` is a tag.", BookFormat.MD),
        )) {
            val source = file(name, text)
            val (format, stored) = BookParsers.detectAndStore(source, temp.root, "stored")
            assertEquals(expected, format)
            assertEquals(text, stored.readText())
        }
    }

    @Test fun `percent encoded fragments navigate to Unicode headings`() {
        val content = content("# Книга\n\n[Back](#%D0%BA%D0%BD%D0%B8%D0%B3%D0%B0)")
        val link = content.chapters.single().elements.filterIsInstance<ContentElement.Paragraph>().single().text
        val target = link.getStringAnnotations(LINK_TAG, 0, link.length).single().item
        assertEquals(0 to 0, content.linkTargets[target])
    }

    @Test fun `binary empty oversized and deeply nested files are rejected`() {
        val binary = file("bad.txt", "hello\u0000world")
        assertThrows(IOException::class.java) { BookParsers.detectAndStore(binary, temp.root, "rejected") }
        assertTrue(binary.exists())
        assertFalse(File(temp.root, "rejected.txt").exists())
        assertThrows(IOException::class.java) { content(" \n\t", BookFormat.TXT) }
        assertThrows(IOException::class.java) { TextBookParser.readText(file("large.txt", "12345"), 4) }
        assertThrows(IOException::class.java) { content("> ".repeat(140) + "Too deep") }
        assertThrows(IOException::class.java) { BookParsers.detectAndStore(file("unknown.bin", "hello"), temp.root, "unknown") }
        val malformed = file("broken.txt", "").apply { writeBytes(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 65)) }
        assertThrows(IOException::class.java) { TextBookParser.readText(malformed) }
    }
}
