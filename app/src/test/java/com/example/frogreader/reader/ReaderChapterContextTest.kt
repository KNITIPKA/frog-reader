package com.example.frogreader.reader

import com.example.frogreader.data.model.LinkedDocumentTarget
import com.example.frogreader.ui.reader.ReaderChapterContexts
import com.example.frogreader.ui.reader.ReaderNavigationEntry
import com.example.frogreader.ui.reader.ReaderNavigationTarget
import com.example.frogreader.ui.reader.readerVisibleChapterParents
import com.example.frogreader.ui.reader.ReaderChapterHeading
import com.example.frogreader.ui.reader.readerChapterHeading
import com.example.frogreader.ui.reader.readerChaptersRemaining
import com.example.frogreader.ui.reader.readerBookChapterStarts
import com.example.frogreader.ui.reader.BookPage
import com.example.frogreader.ui.reader.ReaderChapterPreview
import com.example.frogreader.ui.reader.resolveReaderChapterPreview
import com.example.frogreader.ui.reader.readerChapterPagesLeft
import com.example.frogreader.ui.reader.readerChapterPercentRemaining
import com.example.frogreader.ui.reader.ReaderBottomBarHeading
import com.example.frogreader.ui.reader.resolveReaderBottomBarHeading
import com.example.frogreader.ui.reader.cleanChapterTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderChapterContextTest {
    @Test
    fun `chapter number is separate from its descriptive title`() {
        assertEquals(
            ReaderChapterHeading("Розділ 4", "Навпростець по гриби"),
            readerChapterHeading("Розділ 4 Навпростець по гриби"),
        )
        assertEquals(ReaderChapterHeading("Chapter IV", "A long tale"), readerChapterHeading("Chapter IV: A long tale"))
        assertEquals(ReaderChapterHeading("Глава 12", "Название"), readerChapterHeading("Глава 12. Название"))
        assertEquals(ReaderChapterHeading("Підрозділ 2.3", "Назва"), readerChapterHeading("Підрозділ 2.3 — Назва"))
        assertEquals(ReaderChapterHeading("Розділ 4", "Назва"), readerChapterHeading("  Розділ 4\nНазва  "))
    }

    @Test
    fun `punctuated roman and arabic chapter numbers are recognized`() {
        assertEquals(ReaderChapterHeading("IV", "The Rabbit Sends in a Little Bill"), readerChapterHeading("IV: The Rabbit Sends in a Little Bill"))
        assertEquals(ReaderChapterHeading("12", "A title"), readerChapterHeading("12. A title"))
    }

    @Test
    fun `unnumbered titles and headings without a subtitle remain intact`() {
        listOf("Пролог", "Книга перша", "Chapter IV", "Розділ 4", "I Am a Cat", "1984", "3.14 and other numbers", "Book of Wonder").forEach {
            assertEquals(ReaderChapterHeading(null, it), readerChapterHeading(it))
        }
    }

    @Test
    fun `publication root is hidden but real books inside the book remain`() {
        assertEquals(
            emptyList<String>(),
            readerVisibleChapterParents("Alice’s Adventures in Wonderland", listOf("ALICE'S ADVENTURES IN WONDERLAND")),
        )
        assertEquals(
            listOf("Братство персня", "Книга перша"),
            readerVisibleChapterParents("Володар Перснів", listOf("Володар Перснів", "Братство персня", "Книга перша")),
        )
        assertEquals(listOf("Book One"), readerVisibleChapterParents("Collected Works", listOf("Book One")))
    }

    private fun entry(title: String, depth: Int, item: Int, chapter: Int) =
        ReaderNavigationEntry(title, depth, ReaderNavigationTarget.ReadingOrder(item, chapter))

    @Test
    fun `books parts and subchapters retain the full ordered parent path`() {
        val contexts = ReaderChapterContexts(
            navigation = listOf(
                entry("Братство персня", 0, 0, 0),
                entry("Книга перша", 1, 10, 1),
                entry("Розділ 1 Довгоочікувана гостина", 2, 20, 2),
                entry("Підрозділ", 3, 30, 3),
                entry("Розділ 2 Тінь минулого", 2, 40, 4),
            ),
            chapterStarts = listOf(0, 10, 20, 30, 40),
            chapterTitles = List(5) { "Fallback $it" },
            chapterDepths = listOf(0, 1, 2, 3, 2),
            itemCount = 60,
        )
        val nested = contexts.at(33, 3)
        assertEquals(listOf("Братство персня", "Книга перша", "Розділ 1 Довгоочікувана гостина"), nested.parents)
        assertEquals("Підрозділ", nested.title)
        assertEquals(30, nested.firstItem)
        assertEquals(40, nested.endItemExclusive)
        assertEquals(listOf("Братство персня", "Книга перша"), contexts.at(45, 4).parents)
    }

    @Test
    fun `subchapters sharing a spine chapter switch at their own anchors`() {
        val contexts = ReaderChapterContexts(
            listOf(entry("Chapter", 0, 0, 0), entry("One", 1, 10, 0), entry("Two", 1, 25, 0)),
            listOf(0, 40), listOf("File", "Next file"), listOf(0, 0), 70,
        )
        assertEquals("Chapter", contexts.at(9, 0).title)
        assertEquals(10, contexts.at(9, 0).endItemExclusive)
        assertEquals("One", contexts.at(10, 0).title)
        assertEquals(25, contexts.at(24, 0).endItemExclusive)
        val second = contexts.at(25, 0)
        assertEquals("Two", second.title)
        assertEquals(listOf("Chapter"), second.parents)
        assertEquals(25, second.firstItem)
        assertEquals(40, second.endItemExclusive)
        assertEquals(2, second.navigationIndex)
        assertEquals("Next file", contexts.at(40, 1).title)
        assertTrue(contexts.at(40, 1).parents.isEmpty())
    }

    @Test
    fun `shared anchors prefer the deepest title without zero length ranges`() {
        val contexts = ReaderChapterContexts(
            listOf(entry("Book", 0, 0, 0), entry("Part", 1, 0, 0), entry("Chapter", 2, 0, 0)),
            listOf(0), listOf("Fallback"), listOf(0), 30,
        )
        val current = contexts.at(0, 0)
        assertEquals("Chapter", current.title)
        assertEquals(listOf("Book", "Part"), current.parents)
        assertEquals(30, current.endItemExclusive)
    }

    @Test
    fun `linked notes never become the active reading section or its end`() {
        val contexts = ReaderChapterContexts(
            listOf(
                entry("Part", 0, 0, 0),
                ReaderNavigationEntry("Notes", 1, ReaderNavigationTarget.Linked(LinkedDocumentTarget("notes", 0))),
                entry("Chapter", 1, 15, 0),
            ),
            listOf(0), listOf("Fallback"), listOf(0), 40,
        )
        assertEquals("Part", contexts.at(14, 0).title)
        assertEquals(15, contexts.at(14, 0).endItemExclusive)
        assertEquals(listOf("Part"), contexts.at(15, 0).parents)
    }

    @Test
    fun `fallback hierarchy supports irregular depths and untitled sections`() {
        val contexts = ReaderChapterContexts(
            emptyList(), listOf(0, 10, 20, 30), listOf("Book", " ", "Chapter", null),
            listOf(0, 2, 5, 0), 45,
        )
        assertEquals(listOf("Book"), contexts.at(22, 2).parents)
        assertEquals("Chapter", contexts.at(22, 2).title)
        assertNull(contexts.at(30, 3).title)
        assertTrue(contexts.at(30, 3).parents.isEmpty())
    }

    @Test
    fun `preamble before the first anchor keeps its physical chapter title`() {
        val contexts = ReaderChapterContexts(
            listOf(entry("First subchapter", 0, 12, 0)),
            listOf(0), listOf("Preamble"), listOf(0), 40,
        )
        val preamble = contexts.at(0, 0)
        assertEquals("Preamble", preamble.title)
        assertEquals(0, preamble.firstItem)
        assertEquals(12, preamble.endItemExclusive)
        assertNull(preamble.navigationIndex)
    }

    @Test
    fun `remaining chapters count accurately tracks progress along chapter stops`() {
        val stops = listOf(0.0f, 0.1f, 0.25f, 0.5f, 0.75f)
        assertEquals(4, readerChaptersRemaining(stops, 0.0f))
        assertEquals(3, readerChaptersRemaining(stops, 0.15f))
        assertEquals(2, readerChaptersRemaining(stops, 0.25f))
        assertEquals(1, readerChaptersRemaining(stops, 0.6f))
        assertEquals(0, readerChaptersRemaining(stops, 0.75f))
        assertEquals(0, readerChaptersRemaining(stops, 1.0f))
    }

    @Test
    fun `book chapter starts excludes group headers and preserves distinct sorted order`() {
        val starts = readerBookChapterStarts(
            navigation = emptyList(),
            starts = listOf(0, 5, 10, 20),
            depths = listOf(0, 1, 1, 0),
            itemCount = 25,
        )
        // Group root 0 has children 1 and 2, so candidate starts exclude 0
        assertEquals(listOf(5, 10, 20), starts)
    }

    @Test
    fun `chapter pages left computes remaining pages within current chapter accurately`() {
        val starts = listOf(0, 20, 50, 100)
        val pageCount = 200
        // At page 0 (fraction 0f), chapter ends at page 19 -> 19 pages left
        assertEquals(19, readerChapterPagesLeft(starts, pageCount, 0f))
        // At page 19 (fraction 19/199f), last page of chapter 0 -> 0 pages left
        assertEquals(0, readerChapterPagesLeft(starts, pageCount, 19f / 199f))
        // At page 20 (fraction 20/199f), chapter ends at page 49 -> 29 pages left
        assertEquals(29, readerChapterPagesLeft(starts, pageCount, 20f / 199f))
        // At page 199 (fraction 1f), last page of book -> 0 pages left
        assertEquals(0, readerChapterPagesLeft(starts, pageCount, 1f))
    }

    @Test
    fun `chapter percent remaining computes progress in scroll mode`() {
        val starts = listOf(0, 50, 100)
        val itemCount = 101
        // At element 0 (fraction 0f), 50 items in chapter -> 100% left
        assertEquals(100, readerChapterPercentRemaining(starts, itemCount, 0f))
        // At element 25 (fraction 25/100f), halfway through chapter 0 -> 50% left
        assertEquals(50, readerChapterPercentRemaining(starts, itemCount, 0.25f))
    }

    @Test
    fun `resolve reader chapter preview accurately resolves title, parents, and distinct chapterIds`() {
        val contexts = ReaderChapterContexts(
            navigation = listOf(
                entry("Part One", 0, 0, 0),
                entry("Chapter 1", 1, 0, 0),
                entry("Chapter 2", 1, 25, 0),
                entry("Part Two", 0, 50, 1),
                entry("Chapter 3", 1, 50, 1),
            ),
            chapterStarts = listOf(0, 50),
            chapterTitles = listOf("Spine 0", "Spine 1"),
            chapterDepths = listOf(0, 0),
            itemCount = 100,
        )

        val pages = listOf(
            BookPage(emptyList(), firstItemIndex = 0),   // Page 0 -> Item 0 (Chapter 1)
            BookPage(emptyList(), firstItemIndex = 10),  // Page 1 -> Item 10 (Chapter 1)
            BookPage(emptyList(), firstItemIndex = 25),  // Page 2 -> Item 25 (Chapter 2)
            BookPage(emptyList(), firstItemIndex = 50),  // Page 3 -> Item 50 (Chapter 3)
        )

        fun chapterAtItem(item: Int): Int = if (item >= 50) 1 else 0

        // Page 0 (fraction 0.0f) -> Chapter 1
        val p0 = resolveReaderChapterPreview(
            fraction = 0.0f,
            usePages = true,
            pageCount = 4,
            fullPages = pages,
            chapterStartPages = listOf(0, 3),
            chapterStarts = listOf(0, 50),
            itemCount = 100,
            chapterAtItem = ::chapterAtItem,
            chapterContexts = contexts,
            bookTitle = "Book Title",
            defaultChapterName = { "Chapter ${it + 1}" },
        )
        assertEquals("Chapter 1", p0.label)
        assertEquals(listOf("Part One"), p0.parents)

        // Page 2 (fraction 2/3f) -> Chapter 2 (subchapter in same spine chapter)
        val p2 = resolveReaderChapterPreview(
            fraction = 2f / 3f,
            usePages = true,
            pageCount = 4,
            fullPages = pages,
            chapterStartPages = listOf(0, 3),
            chapterStarts = listOf(0, 50),
            itemCount = 100,
            chapterAtItem = ::chapterAtItem,
            chapterContexts = contexts,
            bookTitle = "Book Title",
            defaultChapterName = { "Chapter ${it + 1}" },
        )
        assertEquals("Chapter 2", p2.label)
        assertEquals(listOf("Part One"), p2.parents)
        assertNotEquals(p0.chapterId, p2.chapterId)

        // Page 3 (fraction 1.0f) -> Chapter 3 (in Part Two)
        val p3 = resolveReaderChapterPreview(
            fraction = 1.0f,
            usePages = true,
            pageCount = 4,
            fullPages = pages,
            chapterStartPages = listOf(0, 3),
            chapterStarts = listOf(0, 50),
            itemCount = 100,
            chapterAtItem = ::chapterAtItem,
            chapterContexts = contexts,
            bookTitle = "Book Title",
            defaultChapterName = { "Chapter ${it + 1}" },
        )
        assertEquals("Chapter 3", p3.label)
        assertEquals(listOf("Part Two"), p3.parents)
        assertNotEquals(p2.chapterId, p3.chapterId)

        // Continuous scroll mode fallback (no pages)
        val scrollPreview = resolveReaderChapterPreview(
            fraction = 0.6f, // item 60 -> Chapter 3
            usePages = false,
            pageCount = null,
            fullPages = null,
            chapterStartPages = null,
            chapterStarts = listOf(0, 50),
            itemCount = 100,
            chapterAtItem = ::chapterAtItem,
            chapterContexts = contexts,
            bookTitle = "Book Title",
            defaultChapterName = { "Chapter ${it + 1}" },
        )
        assertEquals("Chapter 3", scrollPreview.label)
        assertEquals(listOf("Part Two"), scrollPreview.parents)
    }

    @Test
    fun `resolveReaderBottomBarHeading properly formats Dark Tower subchapter 15`() {
        val heading = resolveReaderBottomBarHeading(
            activeLabel = "15",
            activeParents = listOf(
                "Часть 3\nВ этом зелено-золотом мареве Вес'-ка Ган",
                "Глава 1\nМиссис Тассенбаум едет на юг",
            ),
        )
        // Subchapter number and parent chapter label appear on the context line,
        // while the descriptive title is promoted to the main title line.
        assertEquals("Часть 3 · Глава 1 · 15", heading.context)
        assertEquals("Миссис Тассенбаум едет на юг", heading.title)
    }

    @Test
    fun `resolveReaderBottomBarHeading formats chapter start with parent part`() {
        val heading = resolveReaderBottomBarHeading(
            activeLabel = "Глава 1\nМиссис Тассенбаум едет на юг",
            activeParents = listOf(
                "Часть 3\nВ этом зелено-золотом мареве Вес'-ка Ган",
            ),
        )
        assertEquals("Часть 3 · Глава 1", heading.context)
        assertEquals("Миссис Тассенбаум едет на юг", heading.title)
    }

    @Test
    fun `resolveReaderBottomBarHeading normalizes whitespace and removes newlines`() {
        val heading = resolveReaderBottomBarHeading(
            activeLabel = "Розділ  4\n  Навпростець   по гриби  ",
            activeParents = listOf("Книга   перша\n  "),
        )
        assertEquals("Книга перша · Розділ 4", heading.context)
        assertEquals("Навпростець по гриби", heading.title)
    }

    @Test
    fun `resolveReaderBottomBarHeading handles simple chapters with and without numbering`() {
        val numbered = resolveReaderBottomBarHeading("Chapter IV: A long tale", emptyList())
        assertEquals("Chapter IV", numbered.context)
        assertEquals("A long tale", numbered.title)

        val unnumbered = resolveReaderBottomBarHeading("Пролог", emptyList())
        assertEquals("", unnumbered.context)
        assertEquals("Пролог", unnumbered.title)

        val numberedNoSubtitle = resolveReaderBottomBarHeading("Глава 12", emptyList())
        assertEquals("", numberedNoSubtitle.context)
        assertEquals("Глава 12", numberedNoSubtitle.title)
    }
}

