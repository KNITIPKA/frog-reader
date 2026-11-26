package com.example.frogreader.data

import com.example.frogreader.data.pdf.compat.PdfNativeWordMap
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfNativeWordMapTest {
    @Test
    fun wordSelectionKeepsNativeIndicesAfterSupplementaryCharacter() {
        val map = PdfNativeWordMap(listOf("😀", " ", "C", "a", "f", "é", " ", "t", "e", "x", "t"))
        assertEquals(2..5, map.wordRange(4))
        assertEquals(7..10, map.wordRange(8))
    }

    @Test
    fun expandedLigatureKeepsOneNativeCharacterAnchor() {
        val map = PdfNativeWordMap(listOf("A", " ", "o", "ffi", "c", "e", " ", "B"))
        assertEquals(2..5, map.wordRange(3))
        assertEquals(2..5, map.wordRange(5))
        assertEquals(7..7, map.wordRange(7))
    }

    @Test
    fun combiningMarkAndGeneratedLineBreakKeepNativeOffsets() {
        val map = PdfNativeWordMap(listOf("C", "a", "f", "e", "\u0301", "\r\n", "n", "e", "x", "t"))
        assertEquals(0..4, map.wordRange(3))
        assertEquals(6..9, map.wordRange(8))
    }

    @Test
    fun beginningAndEndOfWordHaveTheSameNativeRange() {
        val map = PdfNativeWordMap("Frog Reader".map(Char::toString))
        assertEquals(0..3, map.wordRange(0))
        assertEquals(0..3, map.wordRange(3))
        assertEquals(5..10, map.wordRange(10))
    }
}
