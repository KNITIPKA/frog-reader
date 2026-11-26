package com.example.frogreader.data.backup

import com.example.frogreader.data.AppLockDelay
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.LightThemeDefault
import com.example.frogreader.data.PageMargins
import com.example.frogreader.data.PageTurnAnimation
import com.example.frogreader.data.ReaderFont
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.ReadingMode
import com.example.frogreader.data.ReadingStats
import com.example.frogreader.data.StartupDestination
import com.example.frogreader.data.StatsRepository
import com.example.frogreader.data.StoreFixture
import com.example.frogreader.data.db.DatabaseBackupManager
import com.example.frogreader.data.db.DatabaseManager
import com.example.frogreader.data.model.BackupManifest
import com.example.frogreader.data.model.BackupMode
import com.example.frogreader.data.model.BackupSettings
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.QuoteColor
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.data.model.ReadingStatus
import com.example.frogreader.data.model.Shelf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * End-to-end verification of FrogReader's native SQLite backup archive (format v4),
 * including `VACUUM INTO` snapshots, custom font path rebasing, staging integrity
 * checks, hostile/truncated archives, and `.pre_restore_bak` rollback protection.
 */
class BackupArchiveTest {

    private val workDir = File("build/tmp/backup_test")
    private val sourceFilesRoot = File(workDir, "source_files")
    private val sourceBooksDir = File(sourceFilesRoot, "books")
    private val sourceCoversDir = File(sourceFilesRoot, "covers")
    private val sourceFontsDir = File(sourceFilesRoot, "fonts")

    private val targetFilesRoot = File(workDir, "target_files")
    private val targetBooksDir = File(targetFilesRoot, "books")
    private val targetCoversDir = File(targetFilesRoot, "covers")
    private val targetFontsDir = File(targetFilesRoot, "fonts")

    @Before
    fun setUp() {
        DatabaseManager.closeAndDeleteForFilesRoot(sourceFilesRoot)
        DatabaseManager.closeAndDeleteForFilesRoot(targetFilesRoot)
        workDir.deleteRecursively()
        listOf(
            sourceBooksDir, sourceCoversDir, sourceFontsDir,
            targetBooksDir, targetCoversDir, targetFontsDir,
        ).forEach { it.mkdirs() }
    }

    @After
    fun tearDown() {
        DatabaseManager.closeAndDeleteForFilesRoot(sourceFilesRoot)
        DatabaseManager.closeAndDeleteForFilesRoot(targetFilesRoot)
        workDir.deleteRecursively()
    }

    private fun contextFor(filesRoot: File): Context = mock(Context::class.java).also {
        `when`(it.filesDir).thenReturn(filesRoot)
        `when`(it.cacheDir).thenReturn(File(filesRoot.parentFile, "${filesRoot.name}_cache").apply { mkdirs() })
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
            Quote(
                "q-$id", "a line worth keeping", 1, 2_000L, note = "why it matters", bookId = id,
                color = QuoteColor.ROSE,
            ),
        ),
        bookmarks = listOf(Bookmark("m-$id", 10, 1, "preview", 3_000L, bookId = id)),
        status = ReadingStatus.FINISHED,
        rating = 5,
        review = "Worth every page.",
        readingSeconds = 4_242L,
    )

    private val defaultStats = ReadingStats(
        dailySeconds = mapOf("2026-08-09" to 3_600L),
        lastReadAtMillis = 1_785_000_000_000L,
    )

    private val defaultSettings = BackupSettings(
        reader = ReaderSettings(fontSizeSp = 21f),
        app = AppSettings(
            theme = AppTheme.OLED,
            dailyGoalMinutes = 45,
            autoHideReturnButton = false,
            defaultTranslator = "translator.app/translator.app.ProcessText",
            defaultDictionary = "colordict:dictionary.app/dictionary.app.Lookup",
            defaultSearchApp = "search:search.app/search.app.Search",
            profileAvatarBase64 = "base64-avatar-jpeg-payload",
        ),
    )

    private suspend fun seedSourceAndExport(
        books: List<Book> = emptyList(),
        shelves: List<Shelf> = listOf(Shelf("s1", "Favourites", books.map { it.id }, 1_000L)),
        stats: ReadingStats = defaultStats,
        settings: BackupSettings = defaultSettings,
        mode: BackupMode = BackupMode.DATA,
    ): ByteArray {
        StoreFixture.seed(sourceFilesRoot, books = books, shelves = shelves)
        StatsRepository(filesRootOverride = sourceFilesRoot).replaceAll(stats)
        val out = ByteArrayOutputStream()
        BackupArchive.write(
            out = out,
            filesRoot = sourceFilesRoot,
            settings = settings,
            mode = mode,
            appVersion = "2.0.0",
            createdAtMillis = 1_700_000_000_000L,
            booksDir = sourceBooksDir,
            coversDir = sourceCoversDir,
            fontsDir = sourceFontsDir,
        )
        return out.toByteArray()
    }

    private fun emptyValidSnapshotBytes(): ByteArray {
        val dbRoot = File(workDir, "empty_db_template").apply { deleteRecursively(); mkdirs() }
        val snap = File(workDir, "empty_snap.db")
        try {
            StoreFixture.seed(dbRoot, emptyList())
            kotlinx.coroutines.runBlocking {
                DatabaseBackupManager.exportSnapshot(dbRoot, snap)
            }
            return snap.readBytes()
        } finally {
            DatabaseManager.closeAndDeleteForFilesRoot(dbRoot)
            dbRoot.deleteRecursively()
            snap.delete()
        }
    }

    @Test
    fun `text formats and user data survive full backup with exact source bytes`() = runTest {
        val textBooks = listOf(BookFormat.TXT, BookFormat.MD).map { format ->
            val id = format.name.lowercase()
            val fileName = "$id.$id"
            File(sourceBooksDir, fileName).writeText("# Українська книжка\r\n\r\nLiteral **text**")
            book(id, fileName).copy(format = format, coverFileName = null)
        }
        val bytes = seedSourceAndExport(books = textBooks, mode = BackupMode.FULL)
        val restored = BackupArchive.restore(
            input = ByteArrayInputStream(bytes),
            targetFilesRoot = targetFilesRoot,
            booksDir = targetBooksDir,
            coversDir = targetCoversDir,
            fontsDir = targetFontsDir,
        )
        assertEquals(BackupManifest.FORMAT_VERSION, restored.manifest.formatVersion)
        val repo = BookRepository(contextFor(targetFilesRoot))
        for (original in textBooks) {
            val loaded = repo.bookById(original.id)!!
            assertEquals(original.title, loaded.title)
            assertEquals(original.format, loaded.format)
            assertEquals(original.quotes, loaded.quotes)
            assertEquals(original.bookmarks, loaded.bookmarks)
            val restoredName = restored.restoredBookFiles.getValue(original.id)
            assertTrue(
                File(sourceBooksDir, original.fileName!!).readBytes()
                    .contentEquals(File(targetBooksDir, restoredName).readBytes())
            )
        }
    }

    @Test
    fun `fresh installation defaults survive a fully encoded settings backup`() = runTest {
        val freshSettings = BackupSettings()
        val bytes = seedSourceAndExport(settings = freshSettings, mode = BackupMode.DATA)
        val restored = BackupArchive.restore(ByteArrayInputStream(bytes), targetFilesRoot)
        assertEquals(BackupManifest.FORMAT_VERSION, restored.manifest.formatVersion)
        assertEquals(freshSettings, restored.settings)
    }

    @Test
    fun `external choices can be reset separately in settings backups`() = runTest {
        val choices = defaultSettings.app
        for (appSettings in listOf(
            choices.copy(defaultTranslator = null),
            choices.copy(defaultDictionary = null),
            choices.copy(defaultSearchApp = null),
        )) {
            val expected = defaultSettings.copy(app = appSettings)
            val bytes = seedSourceAndExport(settings = expected, mode = BackupMode.DATA)
            val restored = BackupArchive.restore(ByteArrayInputStream(bytes), targetFilesRoot)
            assertEquals(BackupManifest.FORMAT_VERSION, restored.manifest.formatVersion)
            assertEquals(expected, restored.settings)
        }
    }

    @Test
    fun `numeric reading margins survive global and per book settings backups`() = runTest {
        val global = defaultSettings.copy(reader = ReaderSettings(pageMarginDp = 24))
        val perBook = ReaderSettings(pageMargins = PageMargins.NORMAL, pageMarginDp = 38)
        val bytes = seedSourceAndExport(
            books = listOf(book("a").copy(readerSettings = perBook)),
            settings = global,
            mode = BackupMode.DATA,
        )
        val restored = BackupArchive.restore(ByteArrayInputStream(bytes), targetFilesRoot)
        assertEquals(BackupManifest.FORMAT_VERSION, restored.manifest.formatVersion)
        assertEquals(global, restored.settings)
        val loadedBook = BookRepository(contextFor(targetFilesRoot)).bookById("a")!!
        assertEquals(perBook, loadedBook.readerSettings)
    }

    @Test
    fun `everything the user made survives a data-only round trip`() = runTest {
        val bytes = seedSourceAndExport(books = listOf(book("a"), book("b")), mode = BackupMode.DATA)
        val restored = BackupArchive.restore(ByteArrayInputStream(bytes), targetFilesRoot)

        val repo = BookRepository(contextFor(targetFilesRoot))
        val statsRepo = StatsRepository(filesRootOverride = targetFilesRoot)

        assertEquals(2, repo.books.value.size)
        val a = repo.bookById("a")!!
        assertEquals("Book a", a.title)
        assertEquals(5, a.rating)
        assertEquals("Worth every page.", a.review)
        assertEquals(ReadingStatus.FINISHED, a.status)
        assertEquals(4_242L, a.readingSeconds)
        assertEquals(3, a.progress.chapterIndex)
        assertEquals(0.42f, a.progress.fraction, 0.0001f)
        assertEquals("a line worth keeping", a.quotes.single().text)
        assertEquals("why it matters", a.quotes.single().note)
        assertEquals(QuoteColor.ROSE, a.quotes.single().color)
        assertEquals("preview", a.bookmarks.single().preview)
        assertNull("DATA restore on fresh device clears unarchived book fileName", a.fileName)
        assertEquals(listOf("a", "b"), repo.shelves.value.single().bookIds)
        assertEquals(3_600L, statsRepo.stats.value.dailySeconds["2026-08-09"])
        assertEquals(1_785_000_000_000L, statsRepo.stats.value.lastReadAtMillis)
        assertEquals(21f, restored.settings?.reader?.fontSizeSp)
        assertEquals(AppTheme.OLED, restored.settings?.app?.theme)
        assertEquals(45, restored.settings?.app?.dailyGoalMinutes)
        assertEquals(false, restored.settings?.app?.autoHideReturnButton)
        assertEquals("base64-avatar-jpeg-payload", restored.settings?.app?.profileAvatarBase64)
        assertEquals(defaultSettings.app.defaultTranslator, restored.settings?.app?.defaultTranslator)
        assertEquals(defaultSettings.app.defaultDictionary, restored.settings?.app?.defaultDictionary)
        assertEquals(defaultSettings.app.defaultSearchApp, restored.settings?.app?.defaultSearchApp)
    }

    @Test
    fun `custom fonts are packed in data backups and rebased across device directories`() = runTest {
        val sourceFont = File(sourceFontsDir, "LiterataCustom-Var.ttf").apply {
            writeBytes(byteArrayOf(0, 1, 0, 0, 7, 8, 9))
        }
        val sourceBold = File(sourceFontsDir, "LiterataCustom-Bold.ttf").apply {
            writeBytes(byteArrayOf(0, 1, 0, 0, 1, 2, 3))
        }

        val globalReader = ReaderSettings(
            font = ReaderFont.CUSTOM,
            customFontPath = sourceFont.absolutePath,
            customFontAuto = true,
            customFontFamilyPaths = listOf(sourceFont.absolutePath, sourceBold.absolutePath),
            customFontAxes = mapOf("wght" to 700f, "wdth" to 75f),
        )
        val perBookReader = globalReader.copy(customFontAxes = mapOf("wght" to 900f, "wdth" to 100f))

        val bytes = seedSourceAndExport(
            books = listOf(book("a").copy(readerSettings = perBookReader)),
            settings = defaultSettings.copy(reader = globalReader),
            mode = BackupMode.DATA,
        )

        // Verify settings.json inside the archive carries portable `fonts/<name>` paths, not host paths!
        var rawSettingsJson = ""
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == BackupArchive.SETTINGS) {
                    rawSettingsJson = zip.readBytes().decodeToString()
                }
            }
        }
        assertTrue(rawSettingsJson.contains("fonts/LiterataCustom-Var.ttf"))
        assertFalse(rawSettingsJson.contains(sourceFilesRoot.absolutePath))

        // Restore onto a completely different targetFilesRoot and verify rebasing!
        val restored = BackupArchive.restore(
            input = ByteArrayInputStream(bytes),
            targetFilesRoot = targetFilesRoot,
            booksDir = targetBooksDir,
            coversDir = targetCoversDir,
            fontsDir = targetFontsDir,
        )

        val expectedTargetVar = File(targetFontsDir, "LiterataCustom-Var.ttf")
        val expectedTargetBold = File(targetFontsDir, "LiterataCustom-Bold.ttf")
        assertTrue(expectedTargetVar.exists())
        assertTrue(expectedTargetBold.exists())
        assertEquals(expectedTargetVar.absolutePath, restored.settings?.reader?.customFontPath)
        assertEquals(
            listOf(expectedTargetVar.absolutePath, expectedTargetBold.absolutePath),
            restored.settings?.reader?.customFontFamilyPaths,
        )
        assertEquals(mapOf("wght" to 700f, "wdth" to 75f), restored.settings?.reader?.customFontAxes)

        val restoredBookSettings = BookRepository(contextFor(targetFilesRoot)).bookById("a")!!.readerSettings!!
        assertEquals(expectedTargetVar.absolutePath, restoredBookSettings.customFontPath)
        assertEquals(
            listOf(expectedTargetVar.absolutePath, expectedTargetBold.absolutePath),
            restoredBookSettings.customFontFamilyPaths,
        )
        assertEquals(mapOf("wght" to 900f, "wdth" to 100f), restoredBookSettings.customFontAxes)
    }

    @Test
    fun `unknown JSON keys in settings do not prevent restoring settings and SQLite data`() = runTest {
        val original = seedSourceAndExport(books = listOf(book("a")))
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            ZipInputStream(ByteArrayInputStream(original)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val bytes = input.readBytes()
                    zip.putNextEntry(ZipEntry(entry.name))
                    zip.write(
                        if (entry.name == "settings.json") {
                            """{"reader":{"fontSizeSp":24},"app":{"centerHeadings":true,"dailyGoalMinutes":45}}""".toByteArray()
                        } else bytes
                    )
                    zip.closeEntry()
                }
            }
        }
        val restored = BackupArchive.restore(ByteArrayInputStream(out.toByteArray()), targetFilesRoot)
        assertEquals(24f, restored.settings?.reader?.fontSizeSp)
        assertEquals(45, restored.settings?.app?.dailyGoalMinutes)
        val loaded = BookRepository(contextFor(targetFilesRoot)).bookById("a")!!
        assertEquals("a line worth keeping", loaded.quotes.single().text)
        assertEquals("Worth every page.", loaded.review)
    }

    @Test
    fun `a data-only backup carries no book files`() = runTest {
        File(sourceBooksDir, "a.epub").writeText("pretend epub")
        File(sourceCoversDir, "a.img").writeText("pretend cover")

        val bytes = seedSourceAndExport(books = listOf(book("a")), mode = BackupMode.DATA)
        val restored = BackupArchive.restore(ByteArrayInputStream(bytes), targetFilesRoot)

        assertTrue(restored.restoredBookFiles.isEmpty())
        assertTrue(restored.restoredCovers.isEmpty())
        assertFalse(File(targetBooksDir, "a.epub").exists())
        val loaded = BookRepository(contextFor(targetFilesRoot)).bookById("a")!!
        assertEquals(1, loaded.quotes.size)
        assertEquals(1, restored.counts.booksWithoutFile)
    }

    @Test
    fun `a full backup carries the book files and puts them back`() = runTest {
        File(sourceBooksDir, "a.epub").writeText("pretend epub")
        File(sourceCoversDir, "a.img").writeText("pretend cover")

        val bytes = seedSourceAndExport(books = listOf(book("a")), mode = BackupMode.FULL)
        val restored = BackupArchive.restore(ByteArrayInputStream(bytes), targetFilesRoot)

        assertEquals("a.epub", restored.restoredBookFiles["a"])
        assertEquals("pretend epub", File(targetBooksDir, "a.epub").readText())
        assertTrue(restored.restoredCovers.contains("a.img"))
        assertEquals("pretend cover", File(targetCoversDir, "a.img").readText())
        assertEquals(0, restored.counts.booksWithoutFile)
    }

    @Test
    fun `a book whose file was already missing still backs up`() = runTest {
        val bytes = seedSourceAndExport(books = listOf(book("a", fileName = null)), mode = BackupMode.FULL)
        BackupArchive.restore(ByteArrayInputStream(bytes), targetFilesRoot)
        val loaded = BookRepository(contextFor(targetFilesRoot)).bookById("a")!!
        assertNull(loaded.fileName)
        assertEquals(1, loaded.quotes.size)
    }

    @Test
    fun `both backup modes preserve multilingual thoughts exact anchors and orphaned bookmarks`() = runTest {
        val original = book("saved", fileName = null).copy(
            quotes = listOf(
                Quote(
                    "q", "Текст цитати\nSecond paragraph", 3, 1000,
                    startItem = 17, startChar = 45, endItem = 19, endChar = 12,
                    note = "Мої думки 🐸\nAnother line", bookId = "saved", updatedAtMillis = 2000,
                )
            ),
            bookmarks = listOf(
                Bookmark(
                    "b", 24, 4, "Remember this", 1000,
                    orphaned = true, bookId = "saved", updatedAtMillis = 3000,
                )
            ),
        )
        for (mode in BackupMode.entries) {
            val modeTarget = File(workDir, "target_${mode.name}")
            try {
                val bytes = seedSourceAndExport(books = listOf(original), mode = mode)
                BackupArchive.restore(ByteArrayInputStream(bytes), modeTarget)
                val loaded = BookRepository(contextFor(modeTarget)).bookById("saved")!!
                assertEquals(original.quotes, loaded.quotes)
                assertEquals(original.bookmarks, loaded.bookmarks)
                assertNull(loaded.fileName)
            } finally {
                DatabaseManager.closeAndDeleteForFilesRoot(modeTarget)
            }
        }
    }

    @Test
    fun `the manifest can be read without unpacking the archive`() = runTest {
        val bytes = seedSourceAndExport(books = listOf(book("a"), book("b")), mode = BackupMode.FULL)
        val manifest = BackupArchive.readManifest(ByteArrayInputStream(bytes))

        assertEquals(BackupManifest.FORMAT_VERSION, manifest.formatVersion)
        assertEquals("2.0.0", manifest.appVersion)
        assertEquals(1_700_000_000_000L, manifest.createdAtMillis)
        assertEquals(BackupMode.FULL, manifest.mode)
        assertEquals(2, manifest.bookCount)
        assertEquals(2, manifest.quoteCount)
    }

    @Test
    fun `a zip that is not a backup is reported clearly`() = runTest {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use {
            it.putNextEntry(ZipEntry("holiday.jpg"))
            it.write(ByteArray(16))
            it.closeEntry()
        }

        try {
            BackupArchive.restore(ByteArrayInputStream(out.toByteArray()), targetFilesRoot)
            fail("Expected a foreign zip to be rejected")
        } catch (e: BackupFormatException) {
            assertTrue(e.message.orEmpty().contains("manifest"))
        }
    }

    @Test
    fun `legacy pre-SQLite format versions are rejected clearly`() {
        for (legacyVersion in 1..3) {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write("""{"formatVersion":$legacyVersion,"bookCount":1}""".toByteArray())
                zip.closeEntry()
            }
            try {
                BackupArchive.readManifest(ByteArrayInputStream(out.toByteArray()))
                fail("Expected legacy formatVersion=$legacyVersion to be rejected")
            } catch (e: BackupFormatException) {
                assertTrue(e.message.orEmpty().contains("pre-SQLite"))
            }
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
    fun `a truncated backup is reported rather than half-applied`() = runTest {
        StoreFixture.seed(targetFilesRoot, books = listOf(book("existing")))
        val bytes = seedSourceAndExport(books = listOf(book("a")))

        try {
            BackupArchive.restore(ByteArrayInputStream(bytes.copyOf(bytes.size / 2)), targetFilesRoot)
            fail("Expected a truncated archive to be rejected")
        } catch (e: Exception) {
            assertTrue(e is BackupFormatException || e is IOException)
        }
        // Existing live database must remain completely intact!
        assertEquals("Book existing", BookRepository(contextFor(targetFilesRoot)).bookById("existing")?.title)
    }

    @Test
    fun `corrupt SQLite payload inside valid zip is rejected in staging without touching live data`() = runTest {
        StoreFixture.seed(targetFilesRoot, books = listOf(book("precious")))
        File(targetBooksDir, "precious.epub").writeText("live-book-bytes")

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BackupArchive.MANIFEST))
            zip.write("""{"formatVersion":4,"bookCount":1}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupArchive.DATABASE))
            zip.write("SQLite format 3\u0000CORRUPTED_BTREE_PAGES_GARBAGE".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("books/poison.epub"))
            zip.write("should-never-reach-live-dir".toByteArray())
            zip.closeEntry()
        }

        assertThrows(BackupFormatException::class.java) {
            kotlinx.coroutines.runBlocking {
                BackupArchive.restore(ByteArrayInputStream(out.toByteArray()), targetFilesRoot)
            }
        }

        // Neither live database nor live booksDir was touched!
        assertFalse(File(targetBooksDir, "poison.epub").exists())
        assertEquals("live-book-bytes", File(targetBooksDir, "precious.epub").readText())
        assertEquals("Book precious", BookRepository(contextFor(targetFilesRoot)).bookById("precious")?.title)
    }

    @Test
    fun `database swap rolls back pre_restore_bak automatically if post-open hook fails`() = runTest {
        StoreFixture.seed(targetFilesRoot, books = listOf(book("live-before")))
        StoreFixture.seed(sourceFilesRoot, books = listOf(book("staged-new")))
        val stagedSnap = File(workDir, "staged-good.db")
        DatabaseBackupManager.exportSnapshot(sourceFilesRoot, stagedSnap)

        try {
            DatabaseManager.replaceDatabaseFromStaged(
                filesRoot = targetFilesRoot,
                stagedDbFile = stagedSnap,
                afterOpenBeforeUnlock = {
                    throw IOException("Simulated failure right after swapping DB")
                },
            )
            fail("Expected failure to propagate")
        } catch (_: IOException) {
        }

        // Rollback must have restored `live-before` and cleaned `.pre_restore_bak`!
        val preRestoreBak = File(DatabaseManager.databaseFileFor(targetFilesRoot).parentFile, "frogreader.db.pre_restore_bak")
        assertFalse(preRestoreBak.exists())
        val recovered = BookRepository(contextFor(targetFilesRoot))
        assertEquals(listOf("live-before"), recovered.books.value.map { it.id })
    }

    @Test
    fun `physical truncation in optional settings is not swallowed`() {
        val validDb = emptyValidSnapshotBytes()
        val out = ByteArrayOutputStream()
        var payloadStart = 0
        val payload = (" ".repeat(2048) + "{}").toByteArray()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BackupArchive.MANIFEST))
            zip.write("""{"formatVersion":4,"bookCount":0}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupArchive.DATABASE))
            zip.write(validDb)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupArchive.SETTINGS).apply {
                method = ZipEntry.STORED
                size = payload.size.toLong()
                compressedSize = size
                crc = CRC32().apply { update(payload) }.value
            })
            payloadStart = out.size()
            zip.write(payload)
            zip.closeEntry()
        }
        val truncated = out.toByteArray().copyOf(payloadStart + payload.size / 2)
        val stage = File(workDir, "trunc_stage")
        assertThrows(IOException::class.java) {
            BackupArchive.read(ByteArrayInputStream(truncated), stage)
        }
    }

    @Test
    fun `sparse settings retain current defaults`() = runTest {
        val validDb = emptyValidSnapshotBytes()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BackupArchive.MANIFEST))
            zip.write("""{"formatVersion":4,"bookCount":0}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupArchive.DATABASE))
            zip.write(validDb)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupArchive.SETTINGS))
            zip.write("""{"reader":{"fontSizeSp":20.0},"app":{"theme":"OLED","haptics":false}}""".toByteArray())
            zip.closeEntry()
        }

        val restored = BackupArchive.restore(ByteArrayInputStream(out.toByteArray()), targetFilesRoot).settings
        val saved = requireNotNull(restored) { "Expected sparse settings to decode" }
        val app = saved.app

        assertEquals(20f, saved.reader.fontSizeSp)
        assertFalse(saved.reader.justify)
        assertFalse(saved.reader.hyphenation)
        assertTrue(saved.reader.bookStyles)
        assertEquals(PageMargins.WIDE, saved.reader.pageMargins)
        assertNull(saved.reader.pageMarginDp)
        assertEquals(34, saved.reader.sideMarginDp)
        assertEquals(ReadingMode.PAGES, saved.reader.readingMode)
        assertTrue(saved.reader.dropCaps)
        assertEquals(PageTurnAnimation.CASCADE, saved.reader.pageTurnAnimation)

        assertEquals(AppTheme.OLED, app.theme)
        assertFalse(app.haptics)
        assertTrue(app.followSystemTheme)
        assertEquals(LightThemeDefault.BEIGE, app.lightThemeDefault)
        assertFalse(app.autoInvertImages)
        assertFalse(app.dynamicColor)
        assertEquals(StartupDestination.LIBRARY, app.startupDestination)
        assertEquals(AppLockDelay.ONE_MINUTE, app.appLockDelay)
        assertEquals(BackupMode.DATA, app.backupMode)
        assertNull(app.defaultTranslator)
        assertNull(app.defaultDictionary)
        assertNull(app.defaultSearchApp)
    }

    @Test
    fun `an entry that tries to escape the app directory cannot`() = runTest {
        val validDb = emptyValidSnapshotBytes()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BackupArchive.MANIFEST))
            zip.write("""{"formatVersion":4,"bookCount":1}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupArchive.DATABASE))
            zip.write(validDb)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("books/../../../escaped.epub"))
            zip.write("hostile".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("fonts/../../../escaped.ttf"))
            zip.write("hostile-font".toByteArray())
            zip.closeEntry()
        }

        BackupArchive.restore(ByteArrayInputStream(out.toByteArray()), targetFilesRoot)

        assertFalse(File(workDir, "escaped.epub").exists())
        assertFalse(File(workDir.parentFile, "escaped.epub").exists())
        assertFalse(File(workDir, "escaped.ttf").exists())
        assertTrue("it lands inside books/ or not at all", File(targetBooksDir, "escaped.epub").exists())
        assertTrue("it lands inside fonts/ or not at all", File(targetFontsDir, "escaped.ttf").exists())
    }

    @Test
    fun `malformed metadata and ambiguous asset entries never replace live state`() = runTest {
        StoreFixture.seed(targetFilesRoot, books = listOf(book("precious")))
        File(targetBooksDir, "precious.epub").writeText("existing bytes")
        val validDatabase = emptyValidSnapshotBytes()
        for (scenario in listOf("missing version", "broken settings", "duplicate book id", "duplicate asset leaf")) {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                fun entry(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                entry(BackupArchive.MANIFEST, (if (scenario == "missing version") "{}" else """{"formatVersion":4}""").toByteArray())
                entry(BackupArchive.DATABASE, validDatabase)
                when (scenario) {
                    "broken settings" -> entry(BackupArchive.SETTINGS, "{invalid".toByteArray())
                    "duplicate book id" -> {
                        entry("books/same.epub", byteArrayOf(1))
                        entry("books/same.pdf", byteArrayOf(2))
                    }
                    "duplicate asset leaf" -> {
                        entry("fonts/same.ttf", byteArrayOf(1))
                        entry("fonts/nested/same.ttf", byteArrayOf(2))
                    }
                }
            }
            try {
                BackupArchive.restore(ByteArrayInputStream(out.toByteArray()), targetFilesRoot)
                fail("Expected rejection for $scenario")
            } catch (_: BackupFormatException) {
                assertEquals("Book precious", BookRepository(contextFor(targetFilesRoot)).bookById("precious")?.title)
                assertEquals("existing bytes", File(targetBooksDir, "precious.epub").readText())
            }
        }
    }

}
