package com.example.frogreader.data.parser

import android.content.Context
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.ChunkedContentCache
import com.example.frogreader.data.StoreFixture
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookChapterIndex
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookIndex
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.BookNavigationTarget
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.EpubSourceRange
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock

class EpubTargetedWindowTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `targeted chapters preserve split merged and repeated spine occurrences`() {
        val file = publication()
        val images = temporary.newFolder("images")
        val content = EpubParser.parseContent(file, images)
        assertEquals(listOf("Lead", "First", "Second", "Plate"), content.chapters.map { it.title })
        assertEquals(listOf(2, 2, 7, 1), content.chapters.map { it.elements.size })
        assertEquals(
            listOf(
                EpubSourceRange(0, "OPS/lead.xhtml", 0, 0, 0, 1),
                EpubSourceRange(2, "OPS/split.xhtml", 0, 0, 1, 1),
                EpubSourceRange(2, "OPS/split.xhtml", 1, 1, 0, 2),
                EpubSourceRange(2, "OPS/split.xhtml", 3, 2, 0, 2),
                EpubSourceRange(3, "OPS/merge.xhtml", 0, 2, 2, 1),
                EpubSourceRange(4, "OPS/repeated.xhtml", 0, 2, 3, 2),
                EpubSourceRange(5, "OPS/repeated.xhtml", 0, 2, 5, 2),
                EpubSourceRange(6, "OPS/plate.svg", 0, 3, 0, 1),
            ),
            content.epubSourceRanges,
        )
        assertTrue(content.chapters[1].publisherBoxes.isNotEmpty())
        assertTrue(content.chapters[2].publisherBoxes.map { it.id }.distinct().size > 1)
        // The index must remain useful when extraction has also been evicted.
        images.listFiles().orEmpty().forEach { assertTrue(it.delete()) }

        val limits = ReaderResourceLimits.DEFAULT.copy(maxChapterBytes = 4_096)
        val error = assertThrows(ResourceLimitException::class.java) {
            EpubParser.parseContent(file, temporary.newFolder("full-under-limit"), limits)
        }
        assertEquals(ResourceLimitKind.ENTRY_SIZE, error.kind)

        val window = requireNotNull(EpubParser.parseTargetWindow(file, images, limits, index(content), 1, 3))
        assertEquals(1, window.firstChapterIndex)
        assertEquals(2, window.firstElement)
        assertEquals(content.totalElements, window.totalElements)
        assertEquals(3, window.chapters.size)
        for ((expected, actual) in content.chapters.drop(1).zip(window.chapters)) {
            assertArrayEquals(digest(expected), digest(actual))
        }
        assertTrue(images.listFiles().orEmpty().any { it.isFile })
    }

    @Test fun `targeted parser rejects a different spine path or logical slice`() {
        val file = publication()
        val images = temporary.newFolder("images")
        val index = index(EpubParser.parseContent(file, images))
        val ranges = index.epubSourceRanges
        val wrongPath = ranges.map { if (it.spineOrdinal == 4) it.copy(path = "OPS/merge.xhtml") else it }
        val wrongSlice = ranges.map { if (it.chapterIndex == 1) it.copy(documentStart = 0) else it }
        val limits = ReaderResourceLimits.DEFAULT

        assertNull(EpubParser.parseTargetWindow(file, images, limits, index.copy(epubSourceRanges = wrongPath), 2, 2))
        assertNull(EpubParser.parseTargetWindow(file, images, limits, index.copy(epubSourceRanges = wrongSlice), 1, 1))
    }

    @Test fun `first-open contents seek reads its fragment before the sequential prefix`() {
        val file = publication()
        val images = temporary.newFolder("images")
        val complete = EpubParser.parseContent(file, images)
        val limited = ReaderResourceLimits.DEFAULT.copy(maxChapterBytes = 4_096)
        val prefixError = assertThrows(ResourceLimitException::class.java) {
            EpubParser.parseContent(file, images, limited)
        }
        assertEquals(ResourceLimitKind.ENTRY_SIZE, prefixError.kind)

        val structure = requireNotNull(EpubParser.readOpeningStructure(file, limited))
        assertEquals(listOf(0, 2, 3, 4, 5, 6), structure.documents.map { it.spineOrdinal })
        assertEquals(listOf("Lead", "First", "Second", "Plate"), structure.navigation.map { it.title })
        val sourceNavigation = structure.navigation.single { it.title == "Second" }
        val destination = sourceNavigation.destination
        assertEquals(1, sourceNavigation.depth)
        assertEquals(EpubSourceDestination(2, "OPS/split.xhtml", "second"), destination)
        val window = requireNotNull(EpubParser.parseOpeningDocument(file, images, limited, destination))
        assertEquals(3, window.firstElement)
        val openedElements = window.content.chapters.flatMap { it.elements }
        val exactTarget = complete.navigation.single { it.title == "Second" }.target as BookNavigationTarget.ReadingOrder
        assertEquals(
            complete.chapters[exactTarget.chapterIndex].elements[exactTarget.elementIndex],
            openedElements[window.firstElement],
        )
        assertEquals(
            complete.epubSourceRanges.filter { it.spineOrdinal == 2 }.flatMap { range ->
                complete.chapters[range.chapterIndex].elements.subList(
                    range.chapterStart, range.chapterStart + range.elementCount,
                )
            },
            openedElements,
        )
        assertNull(EpubParser.parseOpeningDocument(file, images, limited, destination.copy(fragment = "missing")))

        val sizes = ZipFile(file).use { zip -> structure.documents.map { zip.getEntry(it.path).size } }
        val repeated = structure.documents.single { it.spineOrdinal == 5 }
        val occurrenceIndex = structure.documents.indexOf(repeated)
        val midpoint = ((sizes.take(occurrenceIndex).sum() + sizes[occurrenceIndex] * 0.5) / sizes.sum()).toFloat()
        assertEquals(midpoint, structure.fraction(repeated, 0.5f), 1e-5f)
        val (selected, localFraction) = structure.locate(midpoint)
        assertEquals(repeated, selected)
        assertEquals(0.5f, localFraction, 1e-5f)
        assertEquals(structure.documents.last() to 1f, structure.locate(1f))
    }

    @Test fun `repository reads distant source windows and adopts the finished cache in place`() = runBlocking {
        val file = publication()
        val source = file.copyTo(File(temporary.root, "books/${file.name}").also { it.parentFile!!.mkdirs() })
        val images = File(temporary.root, "images/book/${file.nameWithoutExtension}")
        val book = Book(id = "book", title = "Targeted spine fixture", format = BookFormat.EPUB,
            fileName = source.name, addedAtMillis = 1L)
        StoreFixture.seed(temporary.root, listOf(book))
        val context = mock(Context::class.java)
        given(context.filesDir).willReturn(temporary.root)
        given(context.cacheDir).willReturn(temporary.root)
        val repository = BookRepository(context)
        val saved = requireNotNull(repository.bookById(book.id))
        val cache = ChunkedContentCache(File(temporary.root, "reader-content"))
        val canonical = EpubParser.parseContent(source, images)
        assertTrue(cache.save(book.id, source, canonical))
        assertTrue(cache.entryFile(book.id).delete())
        assertTrue(images.deleteRecursively())
        assertNull(repository.openIndexedContent(saved))

        requireNotNull(repository.openEpubSourceContent(saved)).use { session ->
            val exact = session.index
            val start = exact.chapters[2].startElement
            val opening = requireNotNull(session.loadWindow(start + 1, neighbors = 0))
            assertArrayEquals(digest(canonical.chapters[2]), digest(opening.chapters.single()))
            val earlier = requireNotNull(session.loadWindow(0, neighbors = 0))
            assertArrayEquals(digest(canonical.chapters[0]), digest(earlier.chapters.single()))
            assertTrue("The addressable source path must work before any cache rebuild",
                !cache.entryFile(book.id).exists())
            assertTrue(repository.prepareEpubSourceContent(saved, session))
            assertTrue(cache.entryFile(book.id).isFile)
            assertTrue(exact === session.index)
            assertArrayEquals(digest(opening.chapters.single()), digest(requireNotNull(session.readChapter(2))))
            assertTrue(requireNotNull(session.readGlobals()).linkedDocuments.isNotEmpty())
        }
    }

    private fun index(content: BookContent): BookIndex {
        var start = 0
        return BookIndex(
            chapters = content.chapters.map { chapter ->
                BookChapterIndex(chapter.title, chapter.depth, chapter.elements.size, start).also {
                    start += chapter.elements.size
                }
            },
            navigation = content.navigation,
            linkTargets = content.linkTargets,
            linkedDocumentTargets = content.linkedDocumentTargets,
            fonts = content.fonts,
            language = content.language,
            pageProgression = content.pageProgression,
            totalElements = content.totalElements,
            resourcePaths = emptyList(),
            epubSourceRanges = content.epubSourceRanges,
        )
    }

    private fun digest(chapter: Chapter): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(Json.encodeToString(Chapter.serializer(), chapter).encodeToByteArray())

    private fun publication(): File = temporary.newFile("targeted.epub").also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.encodeToByteArray())
                zip.closeEntry()
            }
            fun xhtml(path: String, body: String) = entry(
                "OPS/$path",
                """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>$body</body></html>""",
            )

            entry(
                "META-INF/container.xml",
                """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            entry(
                "OPS/content.opf",
                """
                <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0">
                  <metadata><dc:title>Targeted spine fixture</dc:title><dc:language>en</dc:language></metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>
                    <item id="lead" href="lead.xhtml" media-type="application/xhtml+xml"/>
                    <item id="linked" href="linked.xhtml" media-type="application/xhtml+xml"/>
                    <item id="split" href="split.xhtml" media-type="application/xhtml+xml"/>
                    <item id="merge" href="merge.xhtml" media-type="application/xhtml+xml"/>
                    <item id="repeat" href="repeated.xhtml" media-type="application/xhtml+xml"/>
                    <item id="plate" href="plate.svg" media-type="image/svg+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="lead"/><itemref idref="linked" linear="no"/>
                    <itemref idref="split"/><itemref idref="merge"/>
                    <itemref idref="repeat"/><itemref idref="repeat"/><itemref idref="plate"/>
                  </spine>
                </package>
                """.trimIndent(),
            )
            xhtml(
                "nav.xhtml",
                """
                <nav epub:type="toc"><ol>
                  <li><a href="lead.xhtml">Lead</a></li>
                  <li><a href="split.xhtml#first">First</a>
                    <ol><li><a href="split.xhtml#second">Second</a></li></ol>
                  </li>
                  <li><a href="plate.svg">Plate</a></li>
                </ol></nav>
                """.trimIndent(),
            )
            xhtml("lead.xhtml", "<p>${"Leading text. ".repeat(750)}</p>")
            xhtml("linked.xhtml", "<p>Outside the linear spine.</p>")
            xhtml(
                "split.xhtml",
                """
                <div style="border:1px solid #123456;padding:0.2em">
                  <p>Unlisted preamble.</p><p id="first">First <em>styled</em> entry.</p>
                  <p>First continuation.</p><p id="second">Second entry.</p><p>Second continuation.</p>
                </div>
                """.trimIndent(),
            )
            xhtml("merge.xhtml", "<p>Unlisted fragment.</p>")
            xhtml("repeated.xhtml", """<div style="border:1px solid #654321"><p>Repeat this.</p><p>Repeat that.</p></div>""")
            entry("OPS/plate.svg", """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16"><title>Plate</title><rect width="16" height="16"/></svg>""")
        }
    }
}
