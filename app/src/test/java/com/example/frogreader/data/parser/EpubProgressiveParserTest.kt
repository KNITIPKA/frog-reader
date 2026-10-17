package com.example.frogreader.data.parser

import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookNavigationTarget
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.PageProgression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubProgressiveParserTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `preview remains a stable prefix when later fragments and linked notes arrive`() {
        val first = buildString {
            append("""<div style="border:1px solid #123456;padding:0.2em">""")
            repeat(32) { index ->
                append("<p id=\"p$index\">Block $index.")
                if (index == 0) {
                    append("""<a epub:type="noteref" href="#note">1</a>""")
                    append("""<a epub:type="noteref" href="notes.xhtml#later">2</a>""")
                    append("""<a href="tail.xhtml#end">jump</a>""")
                }
                append("</p>")
            }
            append("""<aside epub:type="footnote" id="note"><p>Local note.</p></aside></div>""")
        }
        val file = epub(
            "stable-prefix.epub",
            listOf(
                Doc("main.xhtml", first),
                Doc("append.xhtml", paragraphs(10, "Append")),
                Doc("notes.xhtml", """<aside epub:type="footnote" id="later"><p>Later note.</p></aside>""", linear = false),
                Doc("tail.xhtml", """<p>Tail preamble.</p><p id="end">Tail end.</p>"""),
            ),
            listOf("main.xhtml" to "Main", "notes.xhtml#later" to "Notes", "tail.xhtml#end" to "End"),
        )
        val previews = mutableListOf<BookContent>()
        val final = EpubParser.parseContent(
            file,
            temp.newFolder(),
            ReaderResourceLimits.DEFAULT,
            onPreview = { snapshot ->
                assertEquals(33, snapshot.totalElements)
                assertEquals(listOf("Main"), snapshot.navigation.map { it.title })
                assertTrue(snapshot.linkedDocuments.isEmpty())
                previews += snapshot
            },
        )

        val preview = previews.single()
        assertEquals(33, preview.chapters.single().elements.size)
        assertEquals(listOf(44, 1), final.chapters.map { it.elements.size })
        assertEquals(
            final.chapters.flatMap { it.elements }.take(preview.totalElements),
            preview.chapters.flatMap { it.elements },
        )
        assertEquals("en", preview.language)
        assertEquals(PageProgression.RTL, preview.pageProgression)
        assertNull(preview.publisherPublication)
        assertEquals(setOf("OPS/main.xhtml#note"), preview.notes.keys)
        assertEquals("Local note.", preview.notes.getValue("OPS/main.xhtml#note").text)
        assertFalse(preview.linkTargets.containsKey("OPS/tail.xhtml#end"))
        assertFalse(preview.notes.containsKey("OPS/notes.xhtml#later"))
        assertTrue(preview.linkedDocuments.isEmpty())
        assertTrue(preview.linkedDocumentTargets.isEmpty())
        assertEquals("Later note.", final.notes.getValue("OPS/notes.xhtml#later").text)
        assertEquals(1, final.linkedDocuments.size)
        assertEquals(listOf("Main", "Notes", "End"), final.navigation.map { it.title })
        for ((_, target) in preview.linkTargets) {
            assertTrue(target.first in preview.chapters.indices)
            assertTrue(target.second in preview.chapters[target.first].elements.indices)
        }
        val target = preview.navigation.single().target as BookNavigationTarget.ReadingOrder
        assertEquals(0, target.chapterIndex)
        assertEquals(0, target.elementIndex)
        assertEquals(
            final.chapters[0].publisherBoxes.take(preview.chapters[0].publisherBoxes.size),
            preview.chapters[0].publisherBoxes,
        )
    }

    @Test
    fun `preview is delivered before a later required chapter exceeds its limit`() {
        val file = epub(
            "later-error.epub",
            listOf(
                Doc("main.xhtml", paragraphs(32, "Ready")),
                Doc("bad.xhtml", "<p>${"x".repeat(8_000)}</p>"),
            ),
        )
        val previews = mutableListOf<BookContent>()

        assertThrows(ResourceLimitException::class.java) {
            EpubParser.parseContent(
                file,
                temp.newFolder(),
                ReaderResourceLimits.DEFAULT.copy(maxChapterBytes = 4_096),
                onPreview = previews::add,
            )
        }
        assertEquals(32, previews.single().totalElements)
        assertEquals("Ready 0.", (previews.single().chapters.single().elements.first() as ContentElement.Paragraph).text.text)
    }

    @Test
    fun `preview waits for saved element and following readable blocks`() {
        val file = epub(
            "saved-position.epub",
            (0 until 6).map { Doc(if (it == 0) "main.xhtml" else "part$it.xhtml", paragraphs(16, "Part $it")) },
        )
        val previews = mutableListOf<BookContent>()
        val final = EpubParser.parseContent(
            file,
            temp.newFolder(),
            ReaderResourceLimits.DEFAULT,
            onPreview = previews::add,
            previewElementIndex = 25,
        )

        assertEquals(64, previews.single().totalElements)
        assertEquals(96, final.totalElements)
        assertEquals(final.chapters.single().elements.take(64), previews.single().chapters.single().elements)
    }

    @Test
    fun `unreachable saved element cannot overflow into a premature preview`() {
        val file = epub("far-position.epub", listOf(Doc("main.xhtml", paragraphs(40, "Small"))))
        val previews = mutableListOf<BookContent>()
        val final = EpubParser.parseContent(
            file,
            temp.newFolder(),
            ReaderResourceLimits.DEFAULT,
            onPreview = previews::add,
            previewElementIndex = Int.MAX_VALUE,
        )

        assertTrue(previews.isEmpty())
        assertEquals(40, final.totalElements)
    }

    private fun paragraphs(count: Int, prefix: String): String =
        (0 until count).joinToString("") { "<p>$prefix $it.</p>" }

    private data class Doc(val path: String, val body: String, val linear: Boolean = true)

    private fun epub(
        name: String,
        documents: List<Doc>,
        navigation: List<Pair<String, String>> = listOf("main.xhtml" to "Main"),
    ): File {
        val file = temp.newFile(name)
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            entry(
                "META-INF/container.xml",
                """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            entry(
                "OPS/content.opf",
                """<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0">
                  <metadata><dc:title>Progressive fixture</dc:title><dc:language>en</dc:language></metadata>
                  <manifest><item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>
                    ${documents.mapIndexed { index, doc -> """<item id="d$index" href="${doc.path}" media-type="application/xhtml+xml"/>""" }.joinToString("")}
                  </manifest>
                  <spine page-progression-direction="rtl">${documents.mapIndexed { index, doc -> """<itemref idref="d$index" linear="${if (doc.linear) "yes" else "no"}"/>""" }.joinToString("")}</spine>
                </package>""",
            )
            entry(
                "OPS/nav.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol>
                  ${navigation.joinToString("") { (path, title) -> """<li><a href="$path">$title</a></li>""" }}
                </ol></nav></body></html>""",
            )
            documents.forEach { document ->
                entry(
                    "OPS/${document.path}",
                    """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>${document.body}</body></html>""",
                )
            }
        }
        return file
    }
}
