package com.example.frogreader.data

import com.example.frogreader.data.model.PdfOutline
import com.example.frogreader.data.model.PdfOutlineEntry
import com.example.frogreader.data.pdf.compat.PdfOutlineSource
import com.example.frogreader.data.pdf.compat.readPdfOutline
import java.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PdfOutlineTest {
    private data class Node(val title: String = "", val page: Long = 0, val child: Long = 0, val next: Long = 0)

    private class Source(private val nodes: Map<Long, Node>, private val first: Long = 1) : PdfOutlineSource {
        var calls = 0
        private fun node(bookmark: Long): Node = nodes.getValue(bookmark).also { calls++ }
        override fun firstChild(bookmark: Long): Long = if (bookmark == 0L) first else node(bookmark).child
        override fun nextSibling(bookmark: Long): Long = node(bookmark).next
        override fun title(bookmark: Long): String = node(bookmark).title
        override fun pageIndex(bookmark: Long): Long = node(bookmark).page
    }

    @Test fun `walk retains document order nesting unicode and nonclickable grouping entries`() {
        val outline = readPdfOutline(Source(mapOf(
            1L to Node(" Перша \u0000", -1, 2, 4),
            2L to Node("Підрозділ", 2, next = 3),
            3L to Node("Child", Long.MAX_VALUE),
            4L to Node("第二章", 4),
        )), 5)
        assertEquals(listOf(
            PdfOutlineEntry("Перша", null, 0), PdfOutlineEntry("Підрозділ", 2, 1),
            PdfOutlineEntry("Child", null, 1), PdfOutlineEntry("第二章", 4, 0),
        ), outline.entries)
        assertFalse(outline.truncated)
        assertTrue(outline.isValidFor(5))
    }

    @Test fun `known empty has a serializable successful value`() {
        val empty = readPdfOutline(Source(emptyMap(), first = 0), 10)
        assertEquals(PdfOutline(), empty)
        assertEquals(empty, Json.decodeFromString<PdfOutline>(Json.encodeToString(empty)))
        assertTrue(empty.isValidFor(10))
    }

    @Test fun `cycle cannot loop and pending valid sibling remains reachable`() {
        val source = Source(mapOf(1L to Node("Parent", child = 2, next = 3),
            2L to Node("Child", next = 2), 3L to Node("Next")))
        val outline = readPdfOutline(source, 1)
        assertEquals(listOf("Parent", "Child", "Next"), outline.entries.map { it.title })
        assertTrue(outline.truncated)
        assertEquals(12, source.calls)
    }

    @Test fun `very deep outline exceeds former sixteen levels without recursion and stops at bound`() {
        val source = Source((1L..200L).associateWith { Node("Level $it", child = it + 1) })
        val outline = readPdfOutline(source, 1)
        assertEquals(PdfOutline.MAX_DEPTH + 1, outline.entries.size)
        assertEquals(PdfOutline.MAX_DEPTH, outline.entries.last().depth)
        assertTrue(outline.truncated)
        assertTrue(outline.isValidFor(1))
    }

    @Test fun `wide outline stops before reading one more native title than item bound`() {
        val source = Source((1L..5_000L).associateWith { Node(next = it + 1) })
        val outline = readPdfOutline(source, 1)
        assertEquals(PdfOutline.MAX_ENTRIES, outline.entries.size)
        assertTrue(outline.truncated)
        assertEquals(PdfOutline.MAX_ENTRIES * 4, source.calls)
    }

    @Test fun `title budgets return a bounded partial outline`() {
        val source = Source((1L..100L).associateWith {
            Node("x".repeat(PdfOutline.MAX_TITLE_CHARS + 10), next = it + 1)
        })
        val outline = readPdfOutline(source, 1)
        assertEquals(PdfOutline.MAX_TOTAL_TITLE_CHARS, outline.entries.sumOf { it.title.length })
        assertTrue(outline.entries.all { it.title.length <= PdfOutline.MAX_TITLE_CHARS })
        assertTrue(outline.truncated)
        assertTrue(outline.isValidFor(1))
    }

    @Test fun `native failure and cancellation remain failures instead of empty cached results`() {
        val source = Source(mapOf(1L to Node()))
        val cancelled = IOException("Cancelled")
        assertSame(cancelled, runCatching { readPdfOutline(source, 1) { throw cancelled } }.exceptionOrNull())
        assertEquals(0, source.calls)
        val failing = object : PdfOutlineSource by source {
            override fun pageIndex(bookmark: Long): Long = throw IOException("Native failure")
        }
        assertTrue(runCatching { readPdfOutline(failing, 1) }.exceptionOrNull() is IOException)
    }

    @Test fun `validation rejects broken preorder pages and excessive sizes`() {
        fun valid(vararg entries: PdfOutlineEntry) = PdfOutline(entries.toList()).isValidFor(3)
        assertFalse(valid(PdfOutlineEntry(depth = 1)))
        assertFalse(valid(PdfOutlineEntry(), PdfOutlineEntry(depth = 2)))
        assertFalse(valid(PdfOutlineEntry(depth = -1)))
        assertFalse(valid(PdfOutlineEntry(pageIndex = -1)))
        assertFalse(valid(PdfOutlineEntry(pageIndex = 3)))
        assertFalse(valid(PdfOutlineEntry(title = "x".repeat(PdfOutline.MAX_TITLE_CHARS + 1))))
        assertFalse(PdfOutline(List(PdfOutline.MAX_ENTRIES + 1) { PdfOutlineEntry() }).isValidFor(3))
        assertFalse(PdfOutline(List(65) { PdfOutlineEntry("x".repeat(PdfOutline.MAX_TITLE_CHARS)) }).isValidFor(3))
        assertTrue(valid(PdfOutlineEntry(), PdfOutlineEntry(depth = 1), PdfOutlineEntry(depth = 0)))
    }
}
