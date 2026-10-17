package com.example.frogreader.parser

import com.example.frogreader.data.model.BookNavigationTarget
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.parser.EpubParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubFragmentedSpineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `many spine fragments keep merged content anchors notes and clipped boxes`() {
        val count = 180
        val last = count - 1
        val epub = tempFolder.newFile("fragmented.epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
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
                  <metadata><dc:title>Fragmented spine</dc:title><dc:language>en</dc:language></metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>
                    ${(0 until count).joinToString("") { """<item id="part$it" href="part$it.xhtml" media-type="application/xhtml+xml"/>""" }}
                  </manifest>
                  <spine>${(0 until count).joinToString("") { """<itemref idref="part$it"/>""" }}</spine>
                </package>""",
            )
            entry(
                "OPS/nav.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
                  <nav epub:type="toc"><ol><li><a href="part0.xhtml">Main</a>
                    <ol><li><a href="part$last.xhtml#note">Final</a></li></ol>
                  </li></ol></nav>
                </body></html>""",
            )
            for (index in 0 until count) {
                entry(
                    "OPS/part$index.xhtml",
                    """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
                      <div style="border:1px solid #123456;padding:0.2em">
                        <p id="text">Fragment $index.<a epub:type="noteref" href="#note">1</a><a href="part$index.xhtml">file</a><a href="#after">jump</a></p>
                        <aside epub:type="footnote" id="note"><p>Note $index.</p></aside>
                        <p id="after">After $index.</p>
                      </div>
                    </body></html>""",
                )
            }
        }

        val content = EpubParser.parseContent(epub, tempFolder.newFolder())
        assertEquals(listOf("Main", "Final"), content.chapters.map { it.title })
        assertEquals(listOf(0, 1), content.chapters.map { it.depth })
        assertEquals(listOf(last * 3 + 1, 2), content.chapters.map { it.elements.size })
        assertEquals(
            (0 until count).flatMap {
                listOf("Fragment $it.1filejump", "Note $it.", "After $it.")
            },
            content.chapters.flatMap { chapter ->
                chapter.elements.map { (it as ContentElement.Paragraph).text.text }
            },
        )

        for (index in 0 until count) {
            val path = "OPS/part$index.xhtml"
            assertEquals(0 to index * 3, content.linkTargets[path])
            assertEquals(
                if (index == last) 1 to 0 else 0 to (index * 3 + 1),
                content.linkTargets["$path#note"],
            )
            assertEquals(
                if (index == last) 1 to 1 else 0 to (index * 3 + 2),
                content.linkTargets["$path#after"],
            )
            assertEquals("Note $index.", content.notes.getValue("$path#note").text)
        }
        assertEquals(0, (content.navigation[0].target as BookNavigationTarget.ReadingOrder).chapterIndex)
        val finalTarget = content.navigation[1].target as BookNavigationTarget.ReadingOrder
        assertEquals(1, finalTarget.chapterIndex)
        assertEquals(0, finalTarget.elementIndex)

        val mainBoxes = content.chapters[0].publisherBoxes.filter { it.style.borderTop != null }
        assertEquals(count, mainBoxes.size)
        for (index in 0 until last) {
            assertEquals(index * 3, mainBoxes[index].startElement)
            assertEquals(index * 3 + 3, mainBoxes[index].endElementExclusive)
            assertTrue(mainBoxes[index].drawStart)
            assertTrue(mainBoxes[index].drawEnd)
        }
        val preambleBox = mainBoxes.last()
        assertEquals(last * 3, preambleBox.startElement)
        assertEquals(last * 3 + 1, preambleBox.endElementExclusive)
        assertTrue(preambleBox.drawStart)
        assertFalse(preambleBox.drawEnd)
        val finalBox = content.chapters[1].publisherBoxes.single { it.style.borderTop != null }
        assertEquals(0, finalBox.startElement)
        assertEquals(2, finalBox.endElementExclusive)
        assertFalse(finalBox.drawStart)
        assertTrue(finalBox.drawEnd)
    }
}
