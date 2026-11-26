package com.example.frogreader.engine

import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.data.parser.HtmlMapper
import com.example.frogreader.data.parser.RustEpubPackage
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RustEpubPackageTest {

    private fun jsoupReferenceParseEncryption(bytes: ByteArray): List<Pair<String, String>> {
        val doc = Jsoup.parse(bytes.inputStream(), "UTF-8", "", Parser.xmlParser())
        fun Element.localName() = tagName().substringAfter(':')
        val out = mutableListOf<Pair<String, String>>()
        for (data in doc.getAllElements()) {
            if (!data.localName().equals("EncryptedData", ignoreCase = true)) continue
            val algorithm = data.getAllElements()
                .firstOrNull { it.localName().equals("EncryptionMethod", ignoreCase = true) }
                ?.attr("Algorithm")
            val uri = data.getAllElements()
                .firstOrNull { it.localName().equals("CipherReference", ignoreCase = true) }
                ?.attr("URI")
            if (algorithm.isNullOrEmpty() || uri.isNullOrEmpty()) continue
            out += uri to algorithm
        }
        return out
    }

    @Test
    fun `parseEncryption matches Jsoup reference across namespaces multiple blocks and missing attributes`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
                        xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
              <enc:EncryptedData>
                <enc:EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/>
                <enc:KeyInfo><enc:KeyName>idpf-key</enc:KeyName></enc:KeyInfo>
                <enc:CipherData>
                  <enc:CipherReference URI="OEBPS/Fonts/Serif&amp;Regular.otf"/>
                  <enc:CipherReference URI="OEBPS/Fonts/IgnoredSecond.otf"/>
                </enc:CipherData>
                <enc:EncryptionMethod Algorithm="http://ignored.example/second"/>
              </enc:EncryptedData>
              <EncryptedData>
                <EncryptionMethod/>
                <CipherData><CipherReference URI="OEBPS/Fonts/MissingAlg.otf"/></CipherData>
              </EncryptedData>
              <EncryptedData>
                <EncryptionMethod Algorithm=""/>
                <CipherData><CipherReference URI="OEBPS/Fonts/EmptyAlg.otf"/></CipherData>
              </EncryptedData>
              <EncryptedData>
                <EncryptionMethod Algorithm="http://ns.adobe.com/pdf/enc#RC"/>
                <CipherData><CipherReference/></CipherData>
              </EncryptedData>
              <EncryptedData>
                <EncryptionMethod Algorithm="http://ns.adobe.com/pdf/enc#RC"/>
                <CipherData><CipherReference URI=""/></CipherData>
              </EncryptedData>
              <xenc:EncryptedData xmlns:xenc="http://www.w3.org/2001/04/xmlenc#">
                <xenc:CipherData>
                  <xenc:CipherReference URI="Fonts/Display%20Bold.woff2"/>
                </xenc:CipherData>
                <xenc:EncryptionMethod Algorithm="http://ns.adobe.com/pdf/enc#RC"/>
              </xenc:EncryptedData>
            </encryption>
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val expected = jsoupReferenceParseEncryption(xml)
        val actual = RustEpubPackage.parseEncryption(xml)
        assertNotNull(actual)
        assertEquals(expected, actual)
        assertEquals(
            listOf(
                "OEBPS/Fonts/Serif&Regular.otf" to "http://www.idpf.org/2008/embedding",
                "Fonts/Display%20Bold.woff2" to "http://ns.adobe.com/pdf/enc#RC",
            ),
            actual,
        )
    }

    @Test
    fun `parseEncryption decodes UTF-16 BOM recoveries and bounds hostile nesting`() {
        val source = """
            <encryption>
              <EncryptedData>
                <EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/>
                <CipherData><CipherReference URI="Fonts/Hebrew&#x20;1.ttf"/></CipherData>
              </EncryptedData>
            </encryption>
        """.trimIndent()
        val utf16Le = ByteArray(2 + source.length * 2).also { buf ->
            buf[0] = 0xFF.toByte()
            buf[1] = 0xFE.toByte()
            source.forEachIndexed { i, ch ->
                buf[2 + i * 2] = (ch.code and 0xFF).toByte()
                buf[3 + i * 2] = ((ch.code ushr 8) and 0xFF).toByte()
            }
        }
        assertEquals(
            listOf("Fonts/Hebrew 1.ttf" to "http://www.idpf.org/2008/embedding"),
            RustEpubPackage.parseEncryption(utf16Le),
        )

        val malformed = """
            <encryption>
              <enc:EncryptedData>
                <enc:EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding">
                <enc:CipherData>
                  <enc:CipherReference URI="Fonts/Unclosed.ttf">
                </enc:CipherData>
              </EncryptedData>
              <EncryptedData>
                <EncryptionMethod Algorithm="http://ns.adobe.com/pdf/enc#RC"/>
                <CipherData><CipherReference URI="Fonts/Truncated.otf"/><unclosed
        """.trimIndent().toByteArray(Charsets.UTF_8)
        assertEquals(
            listOf(
                "Fonts/Unclosed.ttf" to "http://www.idpf.org/2008/embedding",
                "Fonts/Truncated.otf" to "http://ns.adobe.com/pdf/enc#RC",
            ),
            RustEpubPackage.parseEncryption(malformed),
        )

        val deepHostile = ("<encryption>" + "<div>".repeat(600) + "</encryption>").toByteArray(Charsets.UTF_8)
        assertNull(RustEpubPackage.parseEncryption(deepHostile))
    }

    @Test
    fun `CssResolver retains sharedComputed and computedSession across chapters and releases on close`() {
        val css = """
            body { font-family: "Literata"; color: #112233; line-height: 1.5; }
            p.prose { text-indent: 1.5em; text-align: justify; margin-bottom: 0.5em; }
            p.prose span.accent { font-style: italic; font-weight: 700; color: #445566; }
            h2.chapter-title { font-size: 1.6em; text-align: center; page-break-before: always; }
            @media (prefers-color-scheme: dark) {
                img.invertible { filter: invert(1); }
            }
            @media screen and (max-width: 480px) {
                table.responsive td { display: block; }
            }
        """.trimIndent()

        val resolver = CssResolver(listOf(CssResolver.Sheet(css)))
        val ch1Doc = Jsoup.parse(
            """
            <html><body>
              <h2 class="chapter-title">Chapter 1</h2>
              <p class="prose">First paragraph with <span class="accent">accent</span>.</p>
              <img class="invertible" src="a.png"/>
            </body></html>
            """.trimIndent(),
        )
        val ch1Heading = resolver.computed(ch1Doc.selectFirst("h2.chapter-title")!!)
        val ch1Paragraph = resolver.computed(ch1Doc.selectFirst("p.prose")!!)
        val ch1Span = resolver.computed(ch1Doc.selectFirst("span.accent")!!)

        assertTrue(resolver.cachedElementCount > 0)
        assertTrue(resolver.hasActiveSelectorSession)
        assertTrue(resolver.hasActiveComputedSession)
        val countAfterCh1 = resolver.sharedComputedStyleCount
        assertTrue(countAfterCh1 >= 4)

        // Trigger dark image and responsive sub-resolvers to verify clearCache() and close() propagate.
        assertTrue(resolver.darkImageStyle(ch1Doc.selectFirst("img.invertible")!!).imageInvert)
        val responsive = resolver.resolverAtMaxWidth(480f)
        assertNotNull(responsive)
        assertEquals(
            "block",
            responsive!!.computed(Jsoup.parse("<table class='responsive'><td>cell</td></table>").selectFirst("td")!!).display,
        )

        // Between chapters, HtmlMapper calls clearCache().
        resolver.clearCache()
        assertEquals(0, resolver.cachedElementCount)
        assertFalse(resolver.hasActiveSelectorSession)
        // sharedComputed and computedSession must stay alive across chapters!
        assertEquals(countAfterCh1, resolver.sharedComputedStyleCount)
        assertTrue(resolver.hasActiveComputedSession)

        // Chapter 2 has identical structure plus a new child element under the cached p.prose parent.
        val ch2Doc = Jsoup.parse(
            """
            <html><body>
              <h2 class="chapter-title">Chapter 2</h2>
              <p class="prose">Second paragraph with <span class="accent">accent</span> and <code>inline code</code>.</p>
            </body></html>
            """.trimIndent(),
        )
        val ch2Heading = resolver.computed(ch2Doc.selectFirst("h2.chapter-title")!!)
        val ch2Paragraph = resolver.computed(ch2Doc.selectFirst("p.prose")!!)
        val ch2Span = resolver.computed(ch2Doc.selectFirst("span.accent")!!)
        val ch2Code = resolver.computed(ch2Doc.selectFirst("code")!!)

        // Identical (parent, tag, attributes, declarations) tuples hit sharedComputed and reuse the exact Computed object!
        assertSame(ch1Heading, ch2Heading)
        assertSame(ch1Paragraph, ch2Paragraph)
        assertSame(ch1Span, ch2Span)
        assertTrue(ch2Code.monospace)
        assertEquals(0xff112233.toInt(), ch2Code.foregroundColorArgb)

        // HtmlMapper end-to-end across two chapters sharing one CssResolver produces identical styled elements.
        val mapped1 = HtmlMapper({ it }, { it }, resolver).map(ch1Doc.body())
        val mapped2 = HtmlMapper({ it }, { it }, resolver).map(ch1Doc.body())
        assertEquals(mapped1, mapped2)

        // Closing the resolver deterministically releases all shared styles and native sessions.
        resolver.close()
        assertEquals(0, resolver.cachedElementCount)
        assertEquals(0, resolver.sharedComputedStyleCount)
        assertFalse(resolver.hasActiveSelectorSession)
        assertFalse(resolver.hasActiveComputedSession)
        assertEquals(0, responsive.sharedComputedStyleCount)
        assertFalse(responsive.hasActiveComputedSession)
    }

    @Test
    fun `CssResolver evicts sharedComputed and resets computedSession when cap is reached`() {
        val resolver = CssResolver(listOf(CssResolver.Sheet("p { color: #223344; }")))
        val html = buildString {
            append("<html><body>")
            for (i in 0 until 1030) {
                append("<p style=\"margin-top: ${i}px\">Paragraph $i</p>")
            }
            append("</body></html>")
        }
        val doc = Jsoup.parse(html)
        for (p in doc.select("p")) {
            resolver.computed(p)
        }
        assertEquals(1024, resolver.sharedComputedStyleCount)
        assertTrue(resolver.hasActiveComputedSession)

        // Because the 1,024-style cap was reached, clearCache() resets both sharedComputed and computedSession.
        resolver.clearCache()
        assertEquals(0, resolver.sharedComputedStyleCount)
        assertFalse(resolver.hasActiveComputedSession)

        // Subsequent chapter resolution opens a fresh session and resolves styles accurately.
        val nextDoc = Jsoup.parse("<html><body><p style=\"margin-top: 12px\">Next</p></body></html>")
        val nextP = resolver.computed(nextDoc.selectFirst("p")!!)
        assertEquals(0xff223344.toInt(), nextP.foregroundColorArgb)
        assertEquals(12f / 16f, nextP.marginTopEm, 0.001f)
        resolver.close()
    }
}
