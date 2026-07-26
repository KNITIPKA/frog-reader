package com.example.frogreader.reader

import com.example.frogreader.ui.reader.BreakRules
import com.example.frogreader.ui.reader.BreakRules.SplitDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakRulesTest {

    private fun decide(
        startLine: Int,
        endLine: Int,
        lineCount: Int,
        isFirstFragment: Boolean = true,
        pageHasOtherContent: Boolean = true,
    ) = BreakRules.splitDecision(startLine, endLine, lineCount, isFirstFragment, pageHasOtherContent)

    @Test
    fun `plenty of lines on both sides - place as computed`() {
        assertEquals(SplitDecision.Place, decide(startLine = 0, endLine = 4, lineCount = 10))
    }

    @Test
    fun `single-line widow - cut one line earlier`() {
        // 5 lines, 4 fit: cutting after line 3 leaves one widow line.
        assertEquals(SplitDecision.PlaceFewer(2), decide(startLine = 0, endLine = 3, lineCount = 5))
    }

    @Test
    fun `orphan after widow adjustment - move paragraph to next page`() {
        // 3 lines, 2 fit: widow rule retreats to 1 line here → orphan → move.
        assertEquals(SplitDecision.MoveToNextPage, decide(startLine = 0, endLine = 1, lineCount = 3))
    }

    @Test
    fun `single fitting line of a new paragraph - move to next page`() {
        assertEquals(SplitDecision.MoveToNextPage, decide(startLine = 0, endLine = 0, lineCount = 4))
    }

    @Test
    fun `empty page never moves - degenerate placement allowed`() {
        assertEquals(
            SplitDecision.Place,
            decide(startLine = 0, endLine = 1, lineCount = 3, pageHasOtherContent = false),
        )
        assertEquals(
            SplitDecision.Place,
            decide(startLine = 0, endLine = 0, lineCount = 4, pageHasOtherContent = false),
        )
    }

    @Test
    fun `continuation fragment ignores the orphan rule but honors widows`() {
        // Continuation filling a page, one line would remain → cut earlier.
        assertEquals(
            SplitDecision.PlaceFewer(7),
            decide(startLine = 0, endLine = 8, lineCount = 10, isFirstFragment = false, pageHasOtherContent = false),
        )
        // A short continuation start is fine — no orphan rule.
        assertEquals(
            SplitDecision.PlaceFewer(5),
            decide(startLine = 3, endLine = 6, lineCount = 8, isFirstFragment = false, pageHasOtherContent = false),
        )
    }

    @Test
    fun `widow adjustment eating the whole fragment on a used page - move`() {
        // 2 lines total, only the first fits, page has other content.
        assertEquals(
            SplitDecision.MoveToNextPage,
            decide(startLine = 0, endLine = 0, lineCount = 2, isFirstFragment = true),
        )
    }

    @Test
    fun `continuation with nothing left after widow rule on empty page - place`() {
        assertEquals(
            SplitDecision.Place,
            decide(startLine = 5, endLine = 5, lineCount = 7, isFirstFragment = false, pageHasOtherContent = false),
        )
    }

    @Test
    fun `heading keep-with-next matrix`() {
        // Empty page: the heading always stays.
        assertTrue(BreakRules.headingFits(0, 500, pageHasOtherContent = false))
        // Enough room after the heading for the required next lines.
        assertTrue(BreakRules.headingFits(300, 200, pageHasOtherContent = true))
        // Not enough room — heading moves.
        assertFalse(BreakRules.headingFits(100, 200, pageHasOtherContent = true))
    }
}
