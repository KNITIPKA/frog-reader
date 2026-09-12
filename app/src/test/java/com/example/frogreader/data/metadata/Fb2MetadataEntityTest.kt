package com.example.frogreader.data.metadata

import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.EditableBookMetadata
import com.example.frogreader.data.parser.BookParsers
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Document
import java.io.File
import java.nio.charset.Charset
import javax.xml.parsers.DocumentBuilderFactory

class Fb2MetadataEntityTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `decimal and hexadecimal character references survive metadata and body rewrites`() {
        val source = fixture("&#375; &#226; &#x177; &#xE2; &#128512; &#x1F600; &amp; &lt; &gt; &apos; &quot; &#38; &#60; &#x3E; &#9; &#10; &#13;", "Original &#x177;")
        val before = document(source)
        val target = temp.newFile("renamed.fb2")
        val metadata = BookParsers.parseMetadata(source, BookFormat.FB2)
        val actual = BookMetadataWriter.write(source, target, BookFormat.FB2,
            EditableBookMetadata.from(metadata).copy(title = "New title"), CoverEdit.Keep)
        assertEquals("New title", actual.title)
        assertEquals(metadata.authors, actual.authors)
        assertEquals(metadata.description, actual.description)
        assertEquals(before.getElementsByTagName("body").item(0).textContent,
            document(target).getElementsByTagName("body").item(0).textContent)
        assertTrue(target.readText().contains("ŷ â ŷ â 😀 😀"))
    }

    @Test fun `escaped references and CDATA are not decoded twice`() {
        val source = fixture("&amp;#375; &amp;#x177; <![CDATA[&#375; &unknown;]]>")
        val target = temp.newFile("literal.fb2")
        BookMetadataWriter.write(source, target, BookFormat.FB2,
            EditableBookMetadata.from(BookParsers.parseMetadata(source, BookFormat.FB2)).copy(title = "Renamed"), CoverEdit.Keep)
        assertEquals(document(source).getElementsByTagName("body").item(0).textContent,
            document(target).getElementsByTagName("body").item(0).textContent)
    }

    @Test fun `invalid or unresolved references fail without replacing the original`() {
        for (entity in listOf("&unknown;", "&#0;", "&#xD800;", "&#xFFFF;", "&#x110000;", "&#+375;")) {
            val source = fixture(entity)
            val original = source.readBytes()
            val target = File(temp.root, "invalid-${source.name}")
            val result = runCatching {
                BookMetadataWriter.write(source, target, BookFormat.FB2,
                    EditableBookMetadata.from(BookParsers.parseMetadata(source, BookFormat.FB2)).copy(title = "Renamed"), CoverEdit.Keep)
            }
            assertTrue("Must reject $entity", result.isFailure)
            assertArrayEquals(original, source.readBytes())
            assertFalse(target.exists())
        }
    }

    /** Opt-in local regression; never copies a user's full book into the repository. */
    @Test fun `supplied FB2 renames without changing text illustrations or remaining metadata`() {
        val path = System.getenv("FROGREADER_METADATA_TEST_BOOK")
        assumeTrue("Set FROGREADER_METADATA_TEST_BOOK to validate a local book", !path.isNullOrBlank())
        val source = File(path!!)
        assertTrue("Supplied file must exist", source.isFile)
        val original = source.readBytes()
        val before = BookParsers.parseMetadata(source, BookFormat.FB2)
        val target = temp.newFile("user-book-renamed.fb2")
        val desired = EditableBookMetadata.from(before).copy(title = before.title + " · перевірка")
        val actual = BookMetadataWriter.write(source, target, BookFormat.FB2, desired, CoverEdit.Keep)
        assertEquals(desired.normalized(), EditableBookMetadata.from(actual).normalized())
        assertArrayEquals(before.coverBytes, actual.coverBytes)
        val beforeXml = document(source)
        val afterXml = document(target)
        for (name in listOf("body", "binary", "stylesheet")) {
            val oldNodes = beforeXml.getElementsByTagNameNS("*", name)
            val newNodes = afterXml.getElementsByTagNameNS("*", name)
            assertEquals("$name count", oldNodes.length, newNodes.length)
            for (i in 0 until oldNodes.length) {
                assertEquals("$name $i content", oldNodes.item(i).textContent, newNodes.item(i).textContent)
            }
        }
        assertArrayEquals("The downloaded original must remain untouched", original, source.readBytes())
        println("Local FB2 rename verified: ${source.length()} bytes; ${beforeXml.getElementsByTagNameNS("*", "body").length} bodies; ${beforeXml.getElementsByTagNameNS("*", "binary").length} binaries unchanged")
    }

    private fun fixture(body: String, title: String = "Original") = temp.newFile().apply {
        writeBytes("""<?xml version="1.0" encoding="Windows-1251"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info><author><nickname>Author &#xE2;</nickname></author><book-title>$title</book-title><annotation><p>Description &#226;</p></annotation><lang>uk</lang></title-info></description><body><section><p>$body</p></section></body></FictionBook>""".toByteArray(Charset.forName("windows-1251")))
    }

    private fun document(file: File): Document = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(file)
}
