package com.example.frogreader.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextNormalizerTest {

    @Test
    fun `latin accents and ligatures fold cleanly to searchable ASCII`() {
        assertEquals("cafe naive resume", SearchTextNormalizer.normalize("  Café   Naïve\nRésumé "))
        assertEquals("strasse soren lodz", SearchTextNormalizer.normalize("Straße Søren Łódź"))
        assertEquals("encyclopaedie oeuvre", SearchTextNormalizer.normalize("Encyclopædie Œuvre"))
    }

    @Test
    fun `ukrainian letters remain distinct while apostrophes and stress marks normalize`() {
        // Distinct Ukrainian letters must NEVER collapse into each other:
        assertNotEquals(SearchTextNormalizer.normalize("їжак"), SearchTextNormalizer.normalize("іжак"))
        assertNotEquals(SearchTextNormalizer.normalize("ґанок"), SearchTextNormalizer.normalize("ганок"))
        assertNotEquals(SearchTextNormalizer.normalize("ємність"), SearchTextNormalizer.normalize("емність"))
        assertNotEquals(SearchTextNormalizer.normalize("йорж"), SearchTextNormalizer.normalize("иорж"))

        // Acute dictionary stress mark (`U+0301`) is stripped without breaking `ї` or `й`:
        assertEquals("україна й київ", SearchTextNormalizer.normalize("Украї\u0301на й Ки\u0301їв"))

        // All common Ukrainian apostrophe variants unify into a single FTS5 token:
        val ascii = SearchTextNormalizer.tokenize("солов'їний пам'ять")
        val typographic = SearchTextNormalizer.tokenize("солов’їний пам’ять")
        val modifier = SearchTextNormalizer.tokenize("соловʼїний памʼять")
        val prime = SearchTextNormalizer.tokenize("солов′їний пам′ять")
        assertEquals(2, ascii.size)
        assertEquals(ascii, typographic)
        assertEquals(ascii, modifier)
        assertEquals(ascii, prime)
    }

    @Test
    fun `canonical decomposition preserves Ukrainian letters and mapped UTF16 bounds`() {
        assertEquals(SearchTextNormalizer.normalize("Їжак йорж Київ"), SearchTextNormalizer.normalize("І\u0308жак и\u0306орж Киі\u0308в"))
        val mapped = SearchTextNormalizer.normalizeWithMapping("І\u0308жак")
        assertEquals("їжак", mapped.normalized)
        assertEquals(0, mapped.origStarts[0])
        assertEquals(2, mapped.origEnds[0])
        assertEquals(listOf("time"), SearchTextNormalizer.tokenize("\uE000Time"))
    }

    @Test
    fun `arabic harakat tatweel and alef yeh teh-marbuta kaf variants unify`() {
        // Vocalized `كِتَابٌ` and kashida-stretched `كـتـاب` both normalize to `كتاب`:
        val plain = SearchTextNormalizer.normalize("كتاب")
        val vocalized = SearchTextNormalizer.normalize("كِتَابٌ")
        val stretched = SearchTextNormalizer.normalize("كـتـاب")
        val persianKaf = SearchTextNormalizer.normalize("کتاب")
        assertEquals(plain, vocalized)
        assertEquals(plain, stretched)
        assertEquals(plain, persianKaf)

        // Alef variants (`أ`, `إ`, `آ`, `ٱ` -> `ا`), Teh-Marbuta (`ة` -> `ه`), Alef Maksura (`ى` -> `ي`):
        assertEquals(
            SearchTextNormalizer.normalize("احمد ابراهيم افاق مكتبه موسي"),
            SearchTextNormalizer.normalize("أَحْمَدُ إِبْرَاهِيم آفاق مَكْتَبَة مُوسَى"),
        )
    }

    @Test
    fun `hebrew niqqud strips and gershayim abbreviations stay single tokens`() {
        assertEquals(
            SearchTextNormalizer.normalize("שלום בראשית הזמן"),
            SearchTextNormalizer.normalize("שָׁלוֹם בְּרֵאשִׁית הַזְּמַן"),
        )

        val asciiQuote = SearchTextNormalizer.tokenize("""תנ"ך רמב"ם""")
        val gershayim = SearchTextNormalizer.tokenize("תנ״ך רמב״ם")
        assertEquals(2, gershayim.size)
        assertEquals(asciiQuote, gershayim)
    }

    @Test
    fun `fts5 prefix query escapes hostile syntax and punctuation safely`() {
        assertNull(SearchTextNormalizer.toFts5PrefixQuery("   ... — ?!  "))
        val expr = SearchTextNormalizer.toFts5PrefixQuery("""time "AND" OR* (test)""")
        assertEquals(""""time"* "and"* "or"* "test"*""", expr)
    }

    @Test
    fun `word prefix matching classifies whole words prefixes and rejects mid-word hits`() {
        val norm = SearchTextNormalizer.normalize("Sometimes the timeless Time")
        val matches = SearchTextNormalizer.findWordPrefixMatches(norm, "time")
        assertEquals(
            listOf(WordMatchKind.PREFIX, WordMatchKind.WHOLE_WORD),
            matches.map { it.kind },
        )
        assertEquals(WordMatchKind.WHOLE_WORD, SearchTextNormalizer.bestMatchKind(norm, "time"))
        assertNull(SearchTextNormalizer.bestMatchKind(norm, "imes"))
        assertNull(SearchTextNormalizer.bestMatchKind(SearchTextNormalizer.normalize("вмостилася"), "с"))

        // Apostrophes stay inside the word, so `ʼ` never starts a new word (`пам'ять` ≠ `ять`):
        val uk = SearchTextNormalizer.normalize("Пам’ять")
        assertNull(SearchTextNormalizer.bestMatchKind(uk, "ять"))
        assertEquals(WordMatchKind.WHOLE_WORD, SearchTextNormalizer.bestMatchKind(uk, SearchTextNormalizer.tokenize("пам'ять").single()))
    }

    @Test
    fun `match score requires every token and prefers whole words`() {
        val fields = listOf(SearchTextNormalizer.normalize("The Time Machine"), SearchTextNormalizer.normalize("H. G. Wells"))
        assertEquals(6, SearchTextNormalizer.matchScore(fields, listOf("time", "wells")))
        assertEquals(5, SearchTextNormalizer.matchScore(fields, listOf("time", "wel")))
        assertEquals(0, SearchTextNormalizer.matchScore(fields, listOf("time", "verne")))
        assertTrue(SearchTextNormalizer.matchesAllTokens(fields, listOf("mach", "h")))
    }

    @Test
    fun `arabic and hebrew clitic prefixes are recognised only for their scripts`() {
        val ar = SearchTextNormalizer.normalize("وَالكِتَابُ لِلطُّلَّابِ بالقلم")
        assertEquals(WordMatchKind.CLITIC, SearchTextNormalizer.bestMatchKind(ar, "كتاب"))
        assertEquals(WordMatchKind.CLITIC, SearchTextNormalizer.bestMatchKind(ar, "طلاب"))
        assertEquals(WordMatchKind.CLITIC, SearchTextNormalizer.bestMatchKind(ar, "قلم"))
        // `تاب` sits after `الك`, which is not a clitic sequence:
        assertNull(SearchTextNormalizer.bestMatchKind(ar, "تاب"))

        val he = SearchTextNormalizer.normalize("וּבַסֵּפֶר שֶׁלִּי")
        assertEquals(WordMatchKind.CLITIC, SearchTextNormalizer.bestMatchKind(he, "ספר"))
        assertNull(SearchTextNormalizer.bestMatchKind(he, "פר"))

        assertTrue(SearchTextNormalizer.needsCliticCandidates("كتاب"))
        assertTrue(SearchTextNormalizer.needsCliticCandidates("ספר"))
        assertTrue(!SearchTextNormalizer.needsCliticCandidates("time"))
        assertTrue(!SearchTextNormalizer.needsCliticCandidates("стіл"))
        assertTrue(!SearchTextNormalizer.needsCliticCandidates("ك"))
    }
}
