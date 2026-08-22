package com.example.frogreader.data.backup

import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.AppLockDelay
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.LightThemeDefault
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.ReadingStats
import com.example.frogreader.data.StartupDestination
import com.example.frogreader.data.model.BackupDocument
import com.example.frogreader.data.model.BackupManifest
import com.example.frogreader.data.model.BackupMode
import com.example.frogreader.data.model.BackupSettings
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.data.model.ReadingStatus
import com.example.frogreader.data.model.Shelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The backup format, end to end, without Android in the way.
 *
 * A backup that cannot be restored is worse than no backup, because the user
 * believes they have one. So these tests care less about the happy path than
 * about what happens to a file that is truncated, foreign, or hostile.
 */
class BackupArchiveTest {

    private val workDir = File("build/tmp/backup_test")
    private val booksDir = File(workDir, "books")
    private val coversDir = File(workDir, "covers")
    private val sourceDir = File(workDir, "source")

    @Before
    fun setUp() {
        workDir.deleteRecursively()
        listOf(booksDir, coversDir, sourceDir).forEach { it.mkdirs() }
    }

    private fun book(id: String, fileName: String? = "$id.epub") = Book(
        id = id,
        title = "Book $id",
        author = "An Author",
        format = BookFormat.EPUB,
        fileName = fileName,
        coverFileName = "$id.img",
        addedAtMillis = 1_000L,
        progress = ReadingProgress(chapterIndex = 3, fraction = 0.42f),
        quotes = listOf(
            Quote("q-$id", "a line worth keeping", 1, 2_000L, note = "why it matters", bookId = id),
        ),
        bookmarks = listOf(Bookmark("m-$id", 10, 1, "preview", 3_000L, bookId = id)),
        status = ReadingStatus.FINISHED,
        rating = 5,
        review = "Worth every page.",
        readingSeconds = 4_242L,
    )

    private fun document(vararg books: Book) = BackupDocument(
        books = books.toList(),
        shelves = listOf(Shelf("s1", "Favourites", books.map { it.id }, 1_000L)),
    )

    private val stats = ReadingStats(mapOf("2026-08-09" to 3_600L))
    private val settings = BackupSettings(
        reader = ReaderSettings(fontSizeSp = 21f),
        app = AppSettings(theme = AppTheme.OLED, dailyGoalMinutes = 45),
    )

    private fun writeArchive(
        doc: BackupDocument,
        mode: BackupMode = BackupMode.DATA,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        BackupArchive.write(
            out = out,
            document = doc,
            stats = stats,
            settings = settings,
            mode = mode,
            appVersion = "1.56",
            createdAtMillis = 1_700_000_000_000L,
            bookFile = { b -> b.fileName?.let { File(sourceDir, it) }?.takeIf { it.exists() } },
            coverFile = { b -> b.coverFileName?.let { File(sourceDir, it) }?.takeIf { it.exists() } },
        )
        return out.toByteArray()
    }

    // ------------------------------------------------------------ round trip

    @Test
    fun `everything the user made survives a data-only round trip`() {
        val bytes = writeArchive(document(book("a"), book("b")))

        val contents = BackupArchive.read(ByteArrayInputStream(bytes), booksDir, coversDir)

        assertEquals(2, contents.document.books.size)
        val a = contents.document.books.first { it.id == "a" }
        assertEquals("Book a", a.title)
        assertEquals(5, a.rating)
        assertEquals("Worth every page.", a.review)
        assertEquals(ReadingStatus.FINISHED, a.status)
        assertEquals(4_242L, a.readingSeconds)
        assertEquals(3, a.progress.chapterIndex)
        assertEquals(0.42f, a.progress.fraction, 0.0001f)
        assertEquals("a line worth keeping", a.quotes.single().text)
        assertEquals("why it matters", a.quotes.single().note)
        assertEquals("preview", a.bookmarks.single().preview)
        assertEquals(listOf("a", "b"), contents.document.shelves.single().bookIds)
        assertEquals(3_600L, contents.stats?.dailySeconds?.get("2026-08-09"))
        assertEquals(21f, contents.settings?.reader?.fontSizeSp)
        assertEquals(AppTheme.OLED, contents.settings?.app?.theme)
        assertEquals(45, contents.settings?.app?.dailyGoalMinutes)
    }

    @Test
    fun `a data-only backup carries no book files`() {
        File(sourceDir, "a.epub").writeText("pretend epub")
        File(sourceDir, "a.img").writeText("pretend cover")

        val bytes = writeArchive(document(book("a")), BackupMode.DATA)
        val contents = BackupArchive.read(ByteArrayInputStream(bytes), booksDir, coversDir)

        assertTrue(contents.restoredBookFiles.isEmpty())
        assertTrue(contents.restoredCovers.isEmpty())
        assertFalse(File(booksDir, "a.epub").exists())
        // The record and everything written about it is still there.
        assertEquals(1, contents.document.books.single().quotes.size)
    }

    @Test
    fun `a full backup carries the book files and puts them back`() {
        File(sourceDir, "a.epub").writeText("pretend epub")
        File(sourceDir, "a.img").writeText("pretend cover")

        val bytes = writeArchive(document(book("a")), BackupMode.FULL)
        val contents = BackupArchive.read(ByteArrayInputStream(bytes), booksDir, coversDir)

        assertEquals("a.epub", contents.restoredBookFiles["a"])
        assertEquals("pretend epub", File(booksDir, "a.epub").readText())
        assertTrue(contents.restoredCovers.contains("a.img"))
        assertEquals("pretend cover", File(coversDir, "a.img").readText())
    }

    @Test
    fun `a book whose file was already missing still backs up`() {
        val bytes = writeArchive(document(book("a", fileName = null)), BackupMode.FULL)
        val contents = BackupArchive.read(ByteArrayInputStream(bytes), booksDir, coversDir)

        assertNull(contents.document.books.single().fileName)
        assertEquals(1, contents.document.books.single().quotes.size)
    }

    // --------------------------------------------------------- the manifest

    @Test
    fun `the manifest can be read without unpacking the archive`() {
        val bytes = writeArchive(document(book("a"), book("b")), BackupMode.FULL)

        val manifest = BackupArchive.readManifest(ByteArrayInputStream(bytes))

        assertEquals(BackupManifest.FORMAT_VERSION, manifest.formatVersion)
        assertEquals("1.56", manifest.appVersion)
        assertEquals(1_700_000_000_000L, manifest.createdAtMillis)
        assertEquals(BackupMode.FULL, manifest.mode)
        assertEquals(2, manifest.bookCount)
        assertEquals(2, manifest.quoteCount)
    }

    // ------------------------------------------------------------- bad input

    @Test
    fun `a zip that is not a backup is reported clearly`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use {
            it.putNextEntry(ZipEntry("holiday.jpg"))
            it.write(ByteArray(16))
            it.closeEntry()
        }

        try {
            BackupArchive.read(ByteArrayInputStream(out.toByteArray()), booksDir, coversDir)
            fail("Expected a foreign zip to be rejected")
        } catch (e: BackupFormatException) {
            assertTrue(e.message.orEmpty().contains("manifest"))
        }
    }

    @Test
    fun `a truncated backup is reported rather than half-applied`() {
        val bytes = writeArchive(document(book("a")))

        try {
            BackupArchive.read(ByteArrayInputStream(bytes.copyOf(bytes.size / 2)), booksDir, coversDir)
            fail("Expected a truncated archive to be rejected")
        } catch (e: Exception) {
            assertTrue(
                "should be a format error, was ${e::class.simpleName}",
                e is BackupFormatException || e is java.io.IOException,
            )
        }
    }

    @Test
    fun `a backup from a newer app says so instead of losing data`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"formatVersion":99,"bookCount":3}""".toByteArray())
            zip.closeEntry()
        }

        try {
            BackupArchive.readManifest(ByteArrayInputStream(out.toByteArray()))
            fail("Expected a newer format version to be refused")
        } catch (e: BackupFormatException) {
            assertTrue(e.message.orEmpty().contains("newer version"))
        }
    }

    @Test
    fun `a damaged stats section does not cost the user their quotes`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"formatVersion":1,"bookCount":1}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("library.json"))
            zip.write(
                """{"books":[{"id":"a","title":"Book a","format":"EPUB","addedAtMillis":1,
                   "quotes":[{"id":"q","text":"kept","chapterIndex":0,"createdAtMillis":1}]}],
                   "shelves":[]}""".trimIndent().toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("stats.json"))
            zip.write("not json at all".toByteArray())
            zip.closeEntry()
        }

        val contents = BackupArchive.read(ByteArrayInputStream(out.toByteArray()), booksDir, coversDir)

        assertNull("the damaged section is simply absent", contents.stats)
        assertEquals("kept", contents.document.books.single().quotes.single().text)
    }

    @Test
    fun `settings from before the redesign decode with new defaults`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"formatVersion":1,"bookCount":0}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("library.json"))
            zip.write("""{"books":[],"shelves":[]}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write(
                """{"reader":{"fontSizeSp":20.0},"app":{"theme":"OLED","haptics":false}}"""
                    .toByteArray(),
            )
            zip.closeEntry()
        }

        val restored = BackupArchive.read(
            ByteArrayInputStream(out.toByteArray()),
            booksDir,
            coversDir,
        ).settings
        val app = requireNotNull(restored) { "Expected old settings to decode" }.app

        assertEquals(AppTheme.OLED, app.theme)
        assertFalse(app.haptics)
        assertTrue(app.followSystemTheme)
        assertEquals(LightThemeDefault.LIGHT, app.lightThemeDefault)
        assertFalse(app.dynamicColor)
        assertEquals(StartupDestination.LIBRARY, app.startupDestination)
        assertEquals(AppLockDelay.ONE_MINUTE, app.appLockDelay)
        assertEquals(BackupMode.DATA, app.backupMode)
    }

    @Test
    fun `an entry that tries to escape the app directory cannot`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"formatVersion":1,"bookCount":1}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("library.json"))
            zip.write("""{"books":[],"shelves":[]}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("books/../../../escaped.epub"))
            zip.write("hostile".toByteArray())
            zip.closeEntry()
        }

        BackupArchive.read(ByteArrayInputStream(out.toByteArray()), booksDir, coversDir)

        assertFalse(File(workDir, "escaped.epub").exists())
        assertFalse(File(workDir.parentFile, "escaped.epub").exists())
        assertTrue("it lands inside books/ or not at all", File(booksDir, "escaped.epub").exists())
    }
}
