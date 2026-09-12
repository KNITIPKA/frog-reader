package com.example.frogreader.data.metadata

import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.EditableBookMetadata
import com.example.frogreader.data.model.previewText
import com.example.frogreader.data.parser.FontObfuscation
import com.example.frogreader.data.parser.BookParsers
import com.example.frogreader.data.parser.mobi.Exth
import com.example.frogreader.data.parser.mobi.MobiDoc
import com.example.frogreader.parser.mobi.MobiBuilder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BookMetadataWriterTest {
    @get:Rule val temp = TemporaryFolder()
    private val cover = MobiBuilder.fakePng(23)
    private val edit = EditableBookMetadata(
        title = "Нова книга & <історія>", authors = listOf("Автор, Іван", "Другий Автор"),
        description = "Нове слово для пошуку & літера <B>.\nДругий абзац.",
        genres = listOf("science", "adventure"), series = "Цикл", seriesNumber = "2.5",
        publisher = "Видавець", year = "2026", isbn = "9781234567897",
        translators = listOf("Олена Перекладач"), language = "uk",
    )

    @Test fun `epub2 rewrites native metadata preserves text css and identifier references`() = roundTrip(BookFormat.EPUB, epub("2.0"))
    @Test fun `epub3 rewrites refinements and cover manifest`() = roundTrip(BookFormat.EPUB, epub("3.0"))
    @Test fun `fb2 rewrites native metadata without changing body and notes`() = roundTrip(BookFormat.FB2, fb2())
    @Test fun `mobi6 rewrites metadata while preserving compressed text and unknown records`() = roundTrip(BookFormat.MOBI, mobi())
    @Test fun `azw3 rewrites metadata while preserving KF8 structures`() = roundTrip(BookFormat.MOBI, kf8(false))
    @Test fun `combo mobi updates both headers without moving the KF8 boundary`() = roundTrip(BookFormat.MOBI, kf8(true))

    private fun roundTrip(format: BookFormat, source: File) {
        val original = source.readBytes()
        val beforeContent = text(source, format)
        val output = File(temp.root, "edited-${source.name}")
        val actual = BookMetadataWriter.write(source, output, format, edit, CoverEdit.Replace(cover, "image/png"))
        assertEquals(edit.title, actual.title)
        assertEquals(edit.authors, actual.authors)
        assertEquals(edit.translators, actual.translators)
        assertEquals(edit.series, actual.series)
        assertEquals(edit.genres, actual.genres)
        assertEquals(edit.isbn, actual.isbn)
        assertTrue(actual.description!!.contains("<B>"))
        assertArrayEquals(cover, actual.coverBytes)
        assertArrayEquals("Original file stays untouched", original, source.readBytes())
        assertEquals("Reading text remains identical", beforeContent, text(output, format))
        if (format == BookFormat.MOBI) MobiDoc.open(source).use { before -> MobiDoc.open(output).use { after ->
            assertEquals(before.kf8?.base, after.kf8?.base)
            val headers = listOfNotNull(before.mobi6.base, before.kf8?.base).toSet()
            for (i in 0 until before.pdb.recordCount) {
                if (i !in headers) assertArrayEquals("MOBI record $i", before.pdb.record(i), after.pdb.record(i))
            }
            listOfNotNull(after.mobi6, after.kf8).distinct().forEach {
                assertEquals(edit.title, it.exth.string(Exth.UPDATED_TITLE, Charsets.UTF_8))
            }
        } }
        if (format == BookFormat.EPUB) ZipFile(source).use { before -> ZipFile(output).use { after ->
            for (entry in before.entries().asSequence().filter { it.name !in listOf("OPS/book.opf", "mimetype") }) {
                assertArrayEquals(entry.name, before.getInputStream(entry).readBytes(), after.getInputStream(after.getEntry(entry.name)).readBytes())
            }
            assertEquals(ZipEntry.STORED, after.getEntry("mimetype").method)
            assertEquals("mimetype", after.entries().nextElement().name)
        } }
        val cleared = File(temp.root, "cleared-${source.name}")
        val minimal = EditableBookMetadata(title = "Only title", language = if (format == BookFormat.EPUB) "und" else "")
        val removed = BookMetadataWriter.write(output, cleared, format, minimal, CoverEdit.Remove)
        assertEquals(minimal, EditableBookMetadata.from(removed))
        assertNull(removed.coverBytes)
        assertEquals(beforeContent, text(cleared, format))
        val titleOnly = File(temp.root, "title-only-${source.name}")
        val onlyTitle = EditableBookMetadata.from(actual).copy(title = "One more title")
        val kept = BookMetadataWriter.write(output, titleOnly, format, onlyTitle, CoverEdit.Keep)
        assertArrayEquals(cover, kept.coverBytes)
    }

    @Test fun `invalid input never touches source or creates output`() {
        val source = fb2()
        val original = source.readBytes()
        val target = File(temp.root, "bad.fb2")
        assertThrows(IllegalArgumentException::class.java) {
            BookMetadataWriter.write(source, target, BookFormat.FB2, edit.copy(title = " "), CoverEdit.Keep)
        }
        assertArrayEquals(original, source.readBytes())
        assertFalse(target.exists())
    }

    @Test fun `legacy mobi refuses unrepresentable Unicode rather than replacing it with question marks`() {
        val source = MobiBuilder.buildMobi6(temp.newFile("legacy.mobi"), "<html><body><p>Original body</p></body></html>", encoding = 1252)
        val target = File(temp.root, "bad.mobi")
        val original = source.readBytes()
        assertThrows(java.io.IOException::class.java) { BookMetadataWriter.write(source, target, BookFormat.MOBI, edit, CoverEdit.Keep) }
        assertArrayEquals(original, source.readBytes())
        assertFalse(target.exists())
    }

    @Test fun `replacing an existing cover also updates its resource and keeps illustration records`() {
        val source = MobiBuilder.buildMobi6(temp.newFile("cover.mobi"), "<html><body><p>Body</p></body></html>",
            exth = listOf(Exth.COVER_OFFSET to byteArrayOf(0, 0, 0, 0)), images = listOf(MobiBuilder.fakePng(1), MobiBuilder.fakePng(2)))
        val output = File(temp.root, "new-cover.mobi")
        BookMetadataWriter.write(source, output, BookFormat.MOBI, edit, CoverEdit.Replace(cover, "image/png"))
        MobiDoc.open(source).use { old -> MobiDoc.open(output).use { new ->
            assertEquals(old.pdb.recordCount, new.pdb.recordCount)
            val image = old.mobi6.resourceRecord(1)!!
            assertArrayEquals(cover, new.pdb.record(image))
            assertArrayEquals(old.pdb.record(image + 1), new.pdb.record(image + 1))
        } }
    }

    @Test fun `ISBN change rekeys obfuscated EPUB fonts without changing the decoded font`() {
        val source = epub("3.0")
        val font = ByteArray(1600) { (it * 17).toByte() }
        val obfuscated = FontObfuscation.deobfuscate(font, FontObfuscation.idpfKey("urn:isbn:9780000000002"), FontObfuscation.IDPF_PREFIX)
        addEntries(source, mapOf(
            "OPS/fonts/Fancy Font.otf" to obfuscated,
            "META-INF/encryption.xml" to """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#"><EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/><CipherData><CipherReference URI="OPS/fonts/Fancy%20Font.otf"/></CipherData></EncryptedData></encryption>""".toByteArray(),
        ))
        val target = File(temp.root, "rekeyed.epub")
        BookMetadataWriter.write(source, target, BookFormat.EPUB, edit, CoverEdit.Keep)
        ZipFile(target).use { zip ->
            val opf = org.jsoup.Jsoup.parse(zip.getInputStream(zip.getEntry("OPS/book.opf")).bufferedReader().readText(), "", org.jsoup.parser.Parser.xmlParser())
            val identity = opf.selectFirst("dc|identifier[id=uid]")!!.text()
            val bytes = zip.getInputStream(zip.getEntry("OPS/fonts/Fancy Font.otf")).readBytes()
            assertArrayEquals(font, FontObfuscation.deobfuscate(bytes, FontObfuscation.idpfKey(identity), FontObfuscation.IDPF_PREFIX))
        }
    }

    @Test fun `SVG cover page references new art and keeps its viewport`() {
        val source = epub("3.0")
        val opf = ZipFile(source).use { it.getInputStream(it.getEntry("OPS/book.opf")).bufferedReader().readText() }
        addEntries(source, mapOf(
            "OPS/book.opf" to opf.replace("<manifest>", """<manifest><item id="cover" href="cover.svg" media-type="image/svg+xml" properties="cover-image"/>""").toByteArray(),
            "OPS/cover.svg" to """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 600 900"><rect width="600" height="900" fill="navy"/></svg>""".toByteArray(),
        ))
        val target = File(temp.root, "svg-cover.epub")
        BookMetadataWriter.write(source, target, BookFormat.EPUB, edit, CoverEdit.Replace(cover, "image/png"))
        ZipFile(target).use { zip ->
            val svg = org.jsoup.Jsoup.parse(zip.getInputStream(zip.getEntry("OPS/cover.svg")).bufferedReader().readText(), "", org.jsoup.parser.Parser.xmlParser())
            assertEquals("0 0 600 900", svg.selectFirst("svg")!!.attr("viewBox"))
            assertNull(svg.selectFirst("rect"))
            val href = svg.selectFirst("image")!!.attr("xlink:href")
            assertArrayEquals(cover, zip.getInputStream(zip.getEntry("OPS/$href")).readBytes())
        }
    }

    @Test fun `plain PalmDOC edits the native short title without changing its text records`() {
        val text = "Original plain text".toByteArray()
        val header = ByteArray(16).apply { this[1] = 1; this[7] = text.size.toByte(); this[9] = 1; this[10] = 0x10 }
        val source = MobiBuilder.writePdb(temp.newFile("plain.prc"), "TEXtREAd", "Old title", listOf(header, text))
        val target = File(temp.root, "new-title.prc")
        val draft = EditableBookMetadata.from(BookParsers.parseMetadata(source, BookFormat.MOBI)).copy(title = "New title")
        val actual = BookMetadataWriter.write(source, target, BookFormat.MOBI, draft, CoverEdit.Keep)
        assertEquals("New title", actual.title)
        assertEquals(text(source, BookFormat.MOBI), text(target, BookFormat.MOBI))
        assertArrayEquals(source.readBytes().drop(32).toByteArray(), target.readBytes().drop(32).toByteArray())
    }

    @Test fun `FB2 stream preserves UTF16 body namespaces comments and CDATA while replacing an existing binary`() {
        val source = temp.newFile("utf16.fb2")
        val encoded = java.util.Base64.getEncoder().encodeToString(MobiBuilder.fakePng(1))
        source.writeBytes("""<?xml version="1.0" encoding="UTF-16"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink"><description><title-info><book-title>Old title</book-title><coverpage><image l:href="#cover"/></coverpage><lang>en</lang></title-info></description><body><!--Keep this comment--><section><p><![CDATA[Unicode Україна & literal <tag>]]></p><p><image l:href="#cover"/></p></section></body><binary id="cover" content-type="image/png">$encoded</binary></FictionBook>""".toByteArray(Charsets.UTF_16))
        val target = File(temp.root, "utf8.fb2")
        BookMetadataWriter.write(source, target, BookFormat.FB2, edit, CoverEdit.Replace(cover, "image/png"))
        val content = target.readText()
        assertTrue(content.contains("Keep this comment"))
        assertTrue(content.contains("<![CDATA[Unicode Україна & literal <tag>]]>"))
        assertEquals(1, Regex("<binary").findAll(content).count())
        assertTrue(content.contains("#cover"))
        assertEquals(text(source, BookFormat.FB2), text(target, BookFormat.FB2))
    }

    @Test fun `adding a cover keeps the MOBI EOF sentinel last`() {
        val eof = byteArrayOf(0xE9.toByte(), 0x8E.toByte(), 0x0D, 0x0A)
        val source = MobiBuilder.buildMobi6(temp.newFile("eof.mobi"), "<html><body><p>Text</p></body></html>", extraRecords = listOf(eof))
        val target = File(temp.root, "cover-before-eof.mobi")
        val actual = BookMetadataWriter.write(source, target, BookFormat.MOBI, edit, CoverEdit.Replace(cover, "image/png"))
        assertArrayEquals(cover, actual.coverBytes)
        MobiDoc.open(target).use { doc -> assertArrayEquals(eof, doc.pdb.record(doc.pdb.recordCount - 1)) }
    }

    private fun addEntries(file: File, added: Map<String, ByteArray>) {
        val original = ZipFile(file).use { zip -> zip.entries().asSequence().associate { it.name to zip.getInputStream(it).readBytes() } }
        ZipOutputStream(file.outputStream()).use { zip ->
            (original + added).forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
    }

    private fun text(file: File, format: BookFormat) = BookParsers.parseContent(file, format, temp.newFolder()).chapters
        .flatMap { it.elements }.mapNotNull { it.previewText() }.filter { it.isNotBlank() }

    private fun fb2() = temp.newFile("original.fb2").apply { writeText("""
        <?xml version="1.0" encoding="UTF-8"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
          <description><title-info><genre>old</genre><author><nickname>Old Author</nickname></author><book-title>Original</book-title><annotation><p>Old blurb</p></annotation><lang>en</lang></title-info>
          <document-info><author><nickname>Producer</nickname></author><id>keep-this-id</id></document-info></description>
          <body><section><title><p>Chapter One</p></title><p>Original body &amp; text.</p></section></body>
          <body name="notes"><section id="note"><p>Footnote text</p></section></body>
        </FictionBook>
    """.trimIndent()) }

    private fun epub(version: String): File = temp.newFile("original.epub").apply {
        ZipOutputStream(outputStream()).use { zip ->
            val entries = mapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                "OPS/book.opf" to """<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf" version="$version" unique-identifier="uid"><metadata><dc:identifier id="uid">urn:isbn:9780000000002</dc:identifier><dc:title>Original</dc:title><dc:creator id="aut">Old Author</dc:creator><dc:description>Old blurb</dc:description><dc:language>en</dc:language><dc:rights>Preserve copyright</dc:rights><meta name="unknown" content="keep"/></metadata><manifest><item id="ch" href="chapter.xhtml" media-type="application/xhtml+xml"/><item id="style" href="style.css" media-type="text/css"/></manifest><spine><itemref idref="ch"/></spine></package>""",
                "OPS/chapter.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter</title><link rel="stylesheet" href="style.css"/></head><body><h1>Chapter One</h1><p>Original body &amp; text.</p></body></html>""",
                "OPS/style.css" to "p { color: navy; margin: 1em; }",
            )
            entries.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
        }
    }

    private fun mobi() = MobiBuilder.buildMobi6(temp.newFile("original.mobi"), "<html><body><h1>Chapter One</h1><p>Original body &amp; text.</p></body></html>",
        exth = listOf(777 to "untouched".toByteArray()), extraRecords = listOf("UNRELATED RECORD".toByteArray()))

    private fun kf8(combo: Boolean) = MobiBuilder.buildKf8(temp.newFile(if (combo) "combo.mobi" else "standalone.azw3"),
        MobiBuilder.Kf8Spec(listOf("<html><head><title>Chapter One</title></head><body></body></html>"), listOf(listOf("<p>Original body &amp; text.</p>")), "p {color:navy}"),
        combo = combo, images = emptyList())
}
