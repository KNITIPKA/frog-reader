package com.example.frogreader.parser

import com.example.frogreader.data.parser.ListMarkers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListMarkersTest {

    @Test
    fun `roman numerals`() {
        assertEquals("i", ListMarkers.toRoman(1))
        assertEquals("iv", ListMarkers.toRoman(4))
        assertEquals("ix", ListMarkers.toRoman(9))
        assertEquals("xiv", ListMarkers.toRoman(14))
        assertEquals("xl", ListMarkers.toRoman(40))
        assertEquals("mcmxcix", ListMarkers.toRoman(1999))
        assertEquals("mmxxvi", ListMarkers.toRoman(2026))
        assertEquals("4000", ListMarkers.toRoman(4000))
    }

    @Test
    fun `alphabetic counters wrap past z`() {
        assertEquals("a", ListMarkers.toAlpha(1))
        assertEquals("z", ListMarkers.toAlpha(26))
        assertEquals("aa", ListMarkers.toAlpha(27))
        assertEquals("ab", ListMarkers.toAlpha(28))
        assertEquals("az", ListMarkers.toAlpha(52))
        assertEquals("ba", ListMarkers.toAlpha(53))
    }

    @Test
    fun `markers by type`() {
        assertEquals("3. ", ListMarkers.marker("decimal", 3))
        assertEquals("c. ", ListMarkers.marker("lower-alpha", 3))
        assertEquals("C. ", ListMarkers.marker("upper-alpha", 3))
        assertEquals("iii. ", ListMarkers.marker("lower-roman", 3))
        assertEquals("III. ", ListMarkers.marker("upper-roman", 3))
        assertEquals("• ", ListMarkers.marker("disc", 3))
        assertEquals("◦ ", ListMarkers.marker("circle", 3))
        assertEquals("▪ ", ListMarkers.marker("square", 3))
        assertEquals("", ListMarkers.marker("none", 3))
    }

    @Test
    fun `html type attribute mapping`() {
        assertEquals("decimal", ListMarkers.cssTypeFor("1"))
        assertEquals("lower-alpha", ListMarkers.cssTypeFor("a"))
        assertEquals("upper-alpha", ListMarkers.cssTypeFor("A"))
        assertEquals("lower-roman", ListMarkers.cssTypeFor("i"))
        assertEquals("upper-roman", ListMarkers.cssTypeFor("I"))
        assertNull(ListMarkers.cssTypeFor("x"))
        assertNull(ListMarkers.cssTypeFor(null))
    }

    @Test
    fun `inherited css type only applies to the matching list kind`() {
        // A ul must not number its bullets with an inherited roman type…
        assertNull(ListMarkers.applicableCssType("upper-roman", ordered = false))
        // …and an ol must not bullet its numbers.
        assertNull(ListMarkers.applicableCssType("disc", ordered = true))
        assertEquals("upper-roman", ListMarkers.applicableCssType("upper-roman", ordered = true))
        assertEquals("circle", ListMarkers.applicableCssType("circle", ordered = false))
        assertEquals("none", ListMarkers.applicableCssType("none", ordered = true))
        assertNull(ListMarkers.applicableCssType(null, ordered = true))
    }

    @Test
    fun `default bullets cycle with depth`() {
        assertEquals("disc", ListMarkers.defaultType(ordered = false, depth = 1))
        assertEquals("circle", ListMarkers.defaultType(ordered = false, depth = 2))
        assertEquals("square", ListMarkers.defaultType(ordered = false, depth = 3))
        assertEquals("disc", ListMarkers.defaultType(ordered = false, depth = 4))
        assertEquals("decimal", ListMarkers.defaultType(ordered = true, depth = 2))
    }
}
