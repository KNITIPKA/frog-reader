package com.example.frogreader.reader

import com.example.frogreader.ui.reader.ReaderContentsTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderContentsTreeTest {
    private val tree = ReaderContentsTree(listOf(0, 0, 1, 1, 0, 1, 2, 1, 0))

    @Test
    fun `collapsed parts hide all descendants but preserve following sections`() {
        assertEquals(listOf(0, 1, 4, 8), tree.visibleIndices { it == 1 || it == 4 })
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 7, 8), tree.visibleIndices { it == 5 })
        assertEquals((0..8).toList(), tree.visibleIndices { false })
    }

    @Test
    fun `opening the current path reveals its nested chapter`() {
        val expanded = tree.ancestorsOf(6).toSet()
        assertEquals(setOf(5, 4), expanded)
        assertEquals(listOf(0, 1, 4, 5, 6, 7, 8), tree.visibleIndices { it !in expanded })
    }

    @Test
    fun `irregular depth jumps still find the nearest enclosing entry`() {
        val irregular = ReaderContentsTree(listOf(2, 4, 7, 3, 0))
        assertEquals(listOf(-1, 0, 1, 0, -1), irregular.parent.toList())
        assertEquals(listOf(0, 4), irregular.visibleIndices { it == 0 })
        assertTrue(ReaderContentsTree(emptyList()).visibleIndices { true }.isEmpty())
    }

    @Test
    fun `deep collapsed hierarchy requires no recursive row traversal`() {
        val deep = ReaderContentsTree((0 until 10_000).toList())
        var evaluatedGroups = 0
        assertEquals(listOf(0), deep.visibleIndices { evaluatedGroups++; true })
        assertEquals(1, evaluatedGroups)
    }
}
