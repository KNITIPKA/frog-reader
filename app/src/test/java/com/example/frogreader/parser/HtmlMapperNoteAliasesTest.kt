package com.example.frogreader.parser

import com.example.frogreader.data.parser.HtmlMapper
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class HtmlMapperNoteAliasesTest {

    @Test
    fun `many rich notes retain same-position aliases and first duplicate anchor`() {
        val count = 256
        val source = buildString {
            append("""<body xmlns:epub="http://www.idpf.org/2007/ops">""")
            append(
                """<p id="duplicate">Preface.<a epub:type="other&#x9;NoTeReF" href="#alias0">first</a><a role="other&#x9;DOC-NOTEREF" href="#alias1">second</a></p>""",
            )
            for (index in 0 until count) {
                // The repeated id already belongs to the preface. It must
                // never enter a later note's same-position alias bucket.
                append("""<a id="alias$index"/><a id="duplicate"/>""")
                val semantic = when (index % 4) {
                    0 -> "epub:type=\"FOOTNOTE\""
                    1 -> "role=\"DOC-FOOTNOTE\""
                    2 -> "class=\"legacy Footnote\""
                    else -> "type=\"endnote\""
                }
                append("""<aside id="note$index" $semantic>""")
                // An anchored p inside a semantic note is itself recognized
                // as a legacy note item. Use an inline descendant here so
                // this fixture has one unambiguous two-paragraph note body.
                append("""<p><span id="inside$index">Note $index.</span></p><p>Tail $index.</p></aside>""")
            }
            append("</body>")
        }
        val mapper = HtmlMapper(resolveImage = { null }, resolveLink = { it })
        val body = Jsoup.parse(source, "", Parser.xmlParser()).selectFirst("body")!!
        val elements = mapper.map(body)

        assertEquals(1 + count * 2, elements.size)
        assertEquals(0, mapper.anchors.getValue("duplicate"))
        assertFalse(mapper.noteDocuments.containsKey("duplicate"))
        assertEquals(setOf("#alias0", "#alias1"), mapper.noteTargets)
        for (index in 0 until count) {
            val expectedStart = 1 + index * 2
            assertEquals(expectedStart, mapper.anchors.getValue("alias$index"))
            assertEquals(expectedStart, mapper.anchors.getValue("note$index"))
            assertEquals(expectedStart, mapper.anchors.getValue("inside$index"))
            val note = mapper.noteDocuments.getValue("note$index")
            assertEquals("Note $index.\n\nTail $index.", note.text)
            assertSame(note, mapper.noteDocuments.getValue("alias$index"))
            assertSame(note, mapper.noteDocuments.getValue("inside$index"))
        }
    }
}
