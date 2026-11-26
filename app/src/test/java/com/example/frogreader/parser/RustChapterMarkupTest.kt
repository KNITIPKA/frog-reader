package com.example.frogreader.parser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import com.example.frogreader.data.model.BIDI_TAG
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.InlineBidiMode
import com.example.frogreader.data.model.NoteDocument
import com.example.frogreader.data.model.PublisherBoxAlign
import com.example.frogreader.data.model.TableCell
import com.example.frogreader.data.model.TableRow
import com.example.frogreader.data.model.encode
import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.data.parser.HtmlMapper
import com.example.frogreader.data.parser.ReaderResourceLimits
import com.example.frogreader.data.parser.ResourceLimitException
import com.example.frogreader.data.parser.RustChapterMarkup
import com.example.frogreader.data.parser.RustEpubArchive
import com.example.frogreader.data.parser.parseChapterDocument
import org.jsoup.Jsoup
import org.jsoup.nodes.CDataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RustChapterMarkupTest {
    @Test
    fun `XHTML decoding retains namespaces CDATA and authored text`() {
        val css = "p::before { content: '<'; }"
        val chapter = """
            <?xml version="1.0" encoding="UTF-16"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <head><title/><style><![CDATA[$css]]></style></head>
              <body><p id="target" epub:type="note">𝄞&nbsp;שלום</p>
              <svg xmlns="http://www.w3.org/2000/svg"><path d="M0 0"/></svg></body>
            </html>
        """.trimIndent().toByteArray(Charsets.UTF_16BE)
        val document = checkNotNull(parseChapterDocument(byteArrayOf(0xfe.toByte(), 0xff.toByte()) + chapter))
        assertEquals(Document.OutputSettings.Syntax.xml, document.outputSettings().syntax())
        assertEquals("http://www.idpf.org/2007/ops", document.selectFirst("html")!!.attr("xmlns:epub"))
        val style = document.selectFirst("style")!!
        assertTrue(style.childNode(0) is CDataNode)
        assertEquals(css, style.data())
        val paragraph = document.getElementById("target")!!
        assertEquals("note", paragraph.attr("epub:type"))
        assertEquals("𝄞\u00a0שלום", (paragraph.childNode(0) as TextNode).wholeText)
        assertTrue(document.selectFirst("svg path")!!.outerHtml().endsWith("/>"))
    }

    @Test
    fun `HTML recovery supplies the body and keeps raw CSS and base links`() {
        val document = checkNotNull(parseChapterDocument(
            """
            <title>A chapter</title><base href="https://reader.example/books/">
            <style>p::before { content: '<'; }</style>
            <p>Start <a href="chapter.xhtml#note">note</a><p>Next
            """.trimIndent().encodeToByteArray(),
        ))
        assertEquals(Document.OutputSettings.Syntax.html, document.outputSettings().syntax())
        assertEquals(listOf("Start note", "Next"), document.body().select("p").map { it.text() })
        assertEquals("p::before { content: '<'; }", document.selectFirst("style")!!.data())
        assertEquals("https://reader.example/books/chapter.xhtml#note", document.selectFirst("a")!!.absUrl("href"))
    }

    @Test
    fun `MRK2 preindex captures metadata flags attributes whitespace hints and mutation state`() {
        val xhtml = """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xmlns:xlink="http://www.w3.org/1999/xlink" lang="ru">
              <head>
                <title>Preindex Title</title>
                <meta name="viewport" content="width=600, height=800"/>
                <link rel="stylesheet" href="styles/main.css"/>
                <style>body { writing-mode: vertical-rl; }</style>
                <script>/* noop */</script>
              </head>
              <body>
                <div class="title2" id="heading-box"><h2>Heading</h2></div>
                <div class="empty-line">   &#x000C;  </div>
                <p><a id="ref1" epub:type="noteref" href="notes.xhtml#fn1"><sup>1</sup></a></p>
                <p><img id="pic" src="images/hero.png" alt="Hero"/></p>
                <svg><image xlink:href="images/inline.png"/></svg>
                <math display="block"><mi>x</mi></math>
                <aside id="fn1" epub:type="footnote"><p>Footnote body</p></aside>
                <p>   &#13;&#10;  </p>
              </body>
            </html>
        """.trimIndent().encodeToByteArray()

        checkNotNull(RustChapterMarkup.parseRetained(xhtml)).use { doc ->
            assertTrue(doc.hasDocFlag(RustChapterMarkup.DOC_HAS_MATH))
            assertTrue(doc.hasDocFlag(RustChapterMarkup.DOC_HAS_SVG))
            assertTrue(doc.hasDocFlag(RustChapterMarkup.DOC_HAS_SCRIPT))
            assertTrue(doc.hasDocFlag(RustChapterMarkup.DOC_MAYBE_VERTICAL_WRITING))
            assertTrue(doc.hasDocFlag(RustChapterMarkup.DOC_HAS_ANY_ID))
            assertTrue(doc.hasDocFlag(RustChapterMarkup.DOC_HAS_ANY_A_HREF))
            assertTrue(doc.hasDocFlag(RustChapterMarkup.DOC_HAS_ANY_LANG))

            assertNotNull(doc.bodyElement)
            assertNull(doc.bookElement)
            assertEquals("Preindex Title", doc.headTitleElement?.text())
            assertEquals("width=600, height=800", doc.viewportMetaElement?.attr("content"))
            assertEquals(listOf("link", "style"), doc.stylesheetElements.map { it.normalName() })

            val headingBox = doc.getElementById("heading-box") as RustChapterMarkup.PreindexedElement
            assertEquals(3, headingBox.titleClassLevel)
            assertEquals("heading-box", headingBox.anchorId)
            assertTrue(headingBox.hasSubtree(RustChapterMarkup.SUBTREE_HAS_BLOCK_PROBE))

            val emptyLine = doc.selectFirst("div.empty-line") as RustChapterMarkup.PreindexedElement
            assertTrue(emptyLine.hasAttrFlag(RustChapterMarkup.ATTR_HAS_EMPTY_LINE_CLASS))
            assertFalse(emptyLine.hasSubtree(RustChapterMarkup.SUBTREE_HAS_JAVA_NON_WS_TEXT))

            val link = doc.getElementById("ref1") as RustChapterMarkup.PreindexedElement
            assertTrue(link.hasTag(RustChapterMarkup.TAG_A))
            assertTrue(link.hasAttrFlag(RustChapterMarkup.ATTR_HAS_HREF))
            assertTrue(link.hasAttrFlag(RustChapterMarkup.ATTR_EPUB_NOTEREF))
            assertTrue(link.hasAttrFlag(RustChapterMarkup.ATTR_ANC_HAS_LANG))
            assertTrue(link.hasSubtree(RustChapterMarkup.SUBTREE_HAS_SUP))
            assertEquals("notes.xhtml#fn1", link.hrefAttr)

            val sup = link.firstElementChild() as RustChapterMarkup.PreindexedElement
            assertTrue(sup.hasTag(RustChapterMarkup.TAG_SUP))

            val img = doc.getElementById("pic") as RustChapterMarkup.PreindexedElement
            assertEquals("images/hero.png", img.srcAttr)

            val svgImage = doc.selectFirst("svg image") as RustChapterMarkup.PreindexedElement
            assertEquals("images/inline.png", svgImage.xlinkHrefAttr)

            val math = doc.selectFirst("math") as RustChapterMarkup.PreindexedElement
            assertTrue(math.hasTag(RustChapterMarkup.TAG_MATH))
            assertTrue(math.hasAttrFlag(RustChapterMarkup.ATTR_IS_DISPLAY_MATH))

            val aside = doc.getElementById("fn1") as RustChapterMarkup.PreindexedElement
            assertTrue(aside.hasAttrFlag(RustChapterMarkup.ATTR_IS_SEMANTIC_NOTE_CONTAINER))

            val blankPara = doc.body().children().last()!!
            val blankText = blankPara.childNode(0) as RustChapterMarkup.PreindexedTextNode
            assertTrue((blankText.textFlags and RustChapterMarkup.TEXT_HTML_BLANK) != 0)
            assertTrue((blankText.textFlags and RustChapterMarkup.TEXT_HAS_CR) != 0)

            // Cloning an element isolates owner so clone edits do not dirty the original document.
            val svgClone = doc.selectFirst("svg")!!.clone()
            svgClone.attr("style", "width:10px")
            assertFalse(doc.treeMutated)
            assertEquals(0, doc.mutatedAttrs)

            // Mutating the text node invalidates its precomputed whitespace flags.
            blankText.text("Visible")
            assertEquals(RustChapterMarkup.TEXT_UNKNOWN, blankText.textFlags)
        }
    }

    @Test
    fun `MRK2 preindex handles prefixed XML capabilities and FB2 note promotion without invalidating flags`() {
        val xhtml = """
            <xhtml:html xmlns:xhtml="http://www.w3.org/1999/xhtml" xmlns:svg="http://www.w3.org/2000/svg" xmlns:m="http://www.w3.org/1998/Math/MathML">
              <xhtml:head>
                <xhtml:title>Prefixed</xhtml:title>
                <xhtml:style>body { writing-mode: vertical-rl; }</xhtml:style>
                <xhtml:script>// ok</xhtml:script>
              </xhtml:head>
              <body>
                <svg:svg><svg:rect width="10" height="10"/></svg:svg>
                <m:math style="display: block"><m:mi>y</m:mi></m:math>
                <p><a id="fb2note" href="ch2-01.xhtml#n1">[1]</a></p>
                <p id="n1">FB2 footnote</p>
              </body>
            </xhtml:html>
        """.trimIndent().encodeToByteArray()

        checkNotNull(RustChapterMarkup.parseRetained(xhtml)).use { doc ->
            assertTrue(doc.hasDocFlag(RustChapterMarkup.DOC_HAS_MATH))
            assertTrue(doc.hasDocFlag(RustChapterMarkup.DOC_HAS_SVG))
            assertTrue(doc.hasDocFlag(RustChapterMarkup.DOC_HAS_SCRIPT))
            assertTrue(doc.hasDocFlag(RustChapterMarkup.DOC_MAYBE_VERTICAL_WRITING))
            assertEquals("Prefixed", doc.headTitleElement?.text())
            assertNull(doc.viewportMetaElement)

            val math = doc.bodyElement!!.getElementsByTag("m:math").first() as RustChapterMarkup.PreindexedElement
            assertTrue(math.hasTag(RustChapterMarkup.TAG_MATH))
            assertTrue(math.hasAttrFlag(RustChapterMarkup.ATTR_IS_DISPLAY_MATH))

            val link = doc.getElementById("fb2note") as RustChapterMarkup.PreindexedElement
            link.setPromotedNoteType()
            assertFalse(doc.treeMutated)
            assertEquals(0, doc.mutatedAttrs)
            assertTrue(link.promotedNote)

            val mapper = HtmlMapper(
                resolveImage = { null },
                resolveLink = { href -> href },
            )
            mapper.map(doc.bodyElement!!)
            assertTrue("ch2-01.xhtml#n1" in mapper.noteTargets)
        }
    }

    @Test
    fun `EPUB XHTML HtmlMapper produces identical content digests and anchors against Jsoup`() {
        val xhtml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xmlns:xlink="http://www.w3.org/1999/xlink" lang="en">
              <head>
                <title>Chapter One</title>
                <style><![CDATA[
                  .drop::first-letter { float: left; font-size: 2em; }
                  .boxed { display: inline-block; width: 2em; text-align: center; }
                  .note-box { border: 1px solid #444; }
                ]]></style>
              </head>
              <body>
                <div class="title2" id="chap"><span>Chapter One</span></div>
                <div class="empty-line">   </div>
                <div class="empty-line">&#160;</div>
                <p id="p1" class="drop">Here is <q lang="ru">цитата <q>внутри</q></q>, <code>inline()</code>,
                  and <bdi>abc</bdi> / <bdo dir="rtl">123</bdo>, plus
                  <ruby>漢<rp>(</rp><rt>かん</rt><rp>)</rp></ruby>
                  and <span class="boxed">★</span>.
                  See <a epub:type="noteref" href="chap.xhtml#fn1">note</a>,
                  <a role="doc-noteref" href="chap.xhtml#fn2">2</a>,
                  and <a href="chap.xhtml#fn3"><sup>[3]</sup></a>.
                </p>
                <p><img src="img/fig1.png" alt="Figure 1"/></p>
                <svg width="24" height="24"><title>Icon</title><path d="M0 0"/></svg>
                <math display="block" id="eq1"><mi>E</mi><mo>=</mo><mi>m</mi><msup><mi>c</mi><mn>2</mn></msup></math>
                <table id="tbl1">
                  <caption>Results</caption>
                  <tr><th>Item</th><td><p>Line 1</p><p>Line 2</p></td></tr>
                </table>
                <aside id="fn1" epub:type="footnote" class="note-box"><p>First note</p></aside>
                <section epub:type="endnotes">
                  <ol><li id="fn2"><p>Second note</p></li></ol>
                </section>
                <p id="fn3"><a type="note" href="chap.xhtml#p1">Back</a> Footnote 3</p>
              </body>
            </html>
        """.trimIndent()

        val cssText = """
            .drop::first-letter { float: left; font-size: 2em; }
            .boxed { display: inline-block; width: 2em; text-align: center; }
            .note-box { border: 1px solid #444; }
        """.trimIndent()

        val rustResult = checkNotNull(RustChapterMarkup.parseRetained(xhtml.encodeToByteArray())).use { doc ->
            mapWithParityMapper(doc.bodyElement!!, cssText)
        }
        val jsoupDoc = Jsoup.parse(xhtml, "", Parser.xmlParser())
        val jsoupResult = mapWithParityMapper(jsoupDoc.selectFirst("body")!!, cssText)

        assertParity(jsoupResult, rustResult)
    }

    @Test
    fun `DTBook XML HtmlMapper produces identical content digests and anchors against Jsoup`() {
        val dtbook = """
            <?xml version="1.0" encoding="UTF-8"?>
            <dtbook xmlns="http://www.daisy.org/z3986/2005/dtbook/">
              <book>
                <frontmatter><doctitle id="title">Book Title</doctitle></frontmatter>
                <bodymatter>
                  <level1 id="lvl1">
                    <levelhd>Part I</levelhd>
                    <level>
                      <hd>Nested Section</hd>
                      <p>Lead paragraph with <a href="book.xml#pn1"><sup>1</sup></a>.</p>
                      <prodnote id="pn1"><p>Producer note content</p></prodnote>
                      <list type="ol">
                        <hd>Steps</hd>
                        <li>First step
                          <list type="ul"><li>Sub step</li></list>
                        </li>
                      </list>
                    </level>
                  </level1>
                </bodymatter>
              </book>
            </dtbook>
        """.trimIndent()

        val rustResult = checkNotNull(RustChapterMarkup.parseRetained(dtbook.encodeToByteArray())).use { doc ->
            mapWithParityMapper(doc.bookElement!!)
        }
        val jsoupDoc = Jsoup.parse(dtbook, "", Parser.xmlParser())
        val jsoupResult = mapWithParityMapper(jsoupDoc.selectFirst("book")!!)

        assertParity(jsoupResult, rustResult)
    }

    @Test
    fun `MOBI6 HTML and KF8 XHTML maintain parity even after post-parse DOM rewrites`() {
        val mobiHtml = """
            <html>
              <body>
                <p id="top">Before page break</p>
                <mbp:pagebreak/>
                <p><font color="#336699">Legacy styled</font> <a id="ref" href="raw#n1"><sup>[1]</sup></a></p>
                <p><img recindex="0001" alt="Cover"/></p>
                <p id="n1">Target footnote</p>
                <guide><reference type="toc" title="TOC"/></guide>
              </body>
            </html>
        """.trimIndent()

        fun rewriteMobi(doc: Document) {
            doc.select("mbp\\:pagebreak").forEach { it.unwrap() }
            doc.select("guide").forEach { it.remove() }
            doc.selectFirst("img")?.attr("src", "images/mobi_0001.jpg")
            doc.getElementById("ref")?.apply {
                attr("href", "chapter1.html#n1")
                attr("type", "note")
            }
            doc.getElementById("top")?.attr("style", "text-align: center")
        }

        val rustMobiDoc = checkNotNull(RustChapterMarkup.parse(mobiHtml.encodeToByteArray(), htmlOnly = true))
        rewriteMobi(rustMobiDoc)
        assertTrue((rustMobiDoc as RustChapterMarkup.MarkupDocument).treeMutated)
        val rustMobiResult = mapWithParityMapper(rustMobiDoc.body())

        val jsoupMobiDoc = Jsoup.parse(mobiHtml)
        rewriteMobi(jsoupMobiDoc)
        val jsoupMobiResult = mapWithParityMapper(jsoupMobiDoc.body())

        assertParity(jsoupMobiResult, rustMobiResult)

        val kf8Xhtml = """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:xlink="http://www.w3.org/1999/xlink">
              <head><style>p.centered { text-align: center; }</style></head>
              <body>
                <p class="centered" id="k1">KF8 lead <a id="klink" href="kindle:pos:fid:0001"><sup>2</sup></a></p>
                <svg><image id="kimg" xlink:href="kindle:embed:0002"/></svg>
                <table border="0"><tr><td><p>Cell A</p></td><td>Cell B</td></tr></table>
                <div id="fnk" class="footnote"><p>KF8 footnote</p></div>
              </body>
            </html>
        """.trimIndent()

        fun rewriteKf8(doc: Document) {
            doc.getElementById("klink")?.attr("href", "part0001.xhtml#fnk")
            doc.getElementById("kimg")?.attr("xlink:href", "images/kf8_0002.png")
            doc.getElementById("k1")?.attr("style", "font-style: italic")
        }

        val cssText = "p.centered { text-align: center; }"
        val rustKf8Result = checkNotNull(RustChapterMarkup.parseRetained(kf8Xhtml.encodeToByteArray())).use { doc ->
            rewriteKf8(doc)
            assertFalse(doc.treeMutated)
            assertEquals(
                RustChapterMarkup.MUTATED_HREF or RustChapterMarkup.MUTATED_SRC or RustChapterMarkup.MUTATED_STYLE,
                doc.mutatedAttrs,
            )
            mapWithParityMapper(doc.bodyElement!!, cssText)
        }
        val jsoupKf8Doc = Jsoup.parse(kf8Xhtml, "", Parser.xmlParser())
        rewriteKf8(jsoupKf8Doc)
        val jsoupKf8Result = mapWithParityMapper(jsoupKf8Doc.selectFirst("body")!!, cssText)

        assertParity(jsoupKf8Result, rustKf8Result)
    }

    @Test
    fun `MRK2 textFlags accurately classify TEXT_ALREADY_COLLAPSED_ASCII and whitespace boundaries`() {
        val xhtml = """
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body>
                <p id="plain">Plain collapsed</p>
                <p id="edges"> leading and trailing </p>
                <p id="single-space"> </p>
                <p id="nbsp">Привет&#160;мир&#8201;!</p>
                <p id="nbsp-only">&#160;&#8239;</p>
                <p id="nel">${'\u0085'}</p>
                <p id="vtab"> &#x000B;</p>
                <p id="double">double  space</p>
                <p id="newline">line1
line2</p>
                <p id="tab">col1&#9;col2</p>
                <p id="cr">cr&#13;lf&#10;</p>
                <p id="ff">page&#12;break</p>
                <p id="blank">   </p>
                <pre id="pre-collapsed"> single leading </pre>
              </body>
            </html>
        """.trimIndent().encodeToByteArray()

        checkNotNull(RustChapterMarkup.parseRetained(xhtml)).use { doc ->
            fun textNode(id: String): RustChapterMarkup.PreindexedTextNode =
                doc.getElementById(id)!!.childNode(0) as RustChapterMarkup.PreindexedTextNode

            val plain = textNode("plain")
            assertTrue(plain.hasTextFlag(RustChapterMarkup.TEXT_ALREADY_COLLAPSED_ASCII))
            assertFalse(plain.hasTextFlag(RustChapterMarkup.TEXT_HTML_BLANK))
            assertFalse(plain.hasTextFlag(RustChapterMarkup.TEXT_STARTS_HTML_WS))
            assertFalse(plain.hasTextFlag(RustChapterMarkup.TEXT_ENDS_HTML_WS))
            assertTrue(plain.hasTextFlag(RustChapterMarkup.TEXT_HAS_JAVA_NON_WS))
            assertFalse(plain.hasTextFlag(RustChapterMarkup.TEXT_HAS_CR))

            val edges = textNode("edges")
            assertTrue(edges.hasTextFlag(RustChapterMarkup.TEXT_ALREADY_COLLAPSED_ASCII))
            assertTrue(edges.hasTextFlag(RustChapterMarkup.TEXT_STARTS_HTML_WS))
            assertTrue(edges.hasTextFlag(RustChapterMarkup.TEXT_ENDS_HTML_WS))
            assertTrue(edges.hasTextFlag(RustChapterMarkup.TEXT_HAS_JAVA_NON_WS))

            val singleSpace = textNode("single-space")
            assertTrue(singleSpace.hasTextFlag(RustChapterMarkup.TEXT_ALREADY_COLLAPSED_ASCII))
            assertTrue(singleSpace.hasTextFlag(RustChapterMarkup.TEXT_HTML_BLANK))
            assertTrue(singleSpace.hasTextFlag(RustChapterMarkup.TEXT_STARTS_HTML_WS))
            assertTrue(singleSpace.hasTextFlag(RustChapterMarkup.TEXT_ENDS_HTML_WS))
            assertFalse(singleSpace.hasTextFlag(RustChapterMarkup.TEXT_HAS_JAVA_NON_WS))

            val nbsp = textNode("nbsp")
            assertTrue(nbsp.hasTextFlag(RustChapterMarkup.TEXT_ALREADY_COLLAPSED_ASCII))
            assertFalse(nbsp.hasTextFlag(RustChapterMarkup.TEXT_HTML_BLANK))
            assertTrue(nbsp.hasTextFlag(RustChapterMarkup.TEXT_HAS_JAVA_NON_WS))
            assertTrue(nbsp.hasTextFlag(RustChapterMarkup.TEXT_HAS_KOTLIN_NON_WS))

            val nbspOnly = textNode("nbsp-only")
            assertTrue(nbspOnly.hasTextFlag(RustChapterMarkup.TEXT_ALREADY_COLLAPSED_ASCII))
            assertFalse(nbspOnly.hasTextFlag(RustChapterMarkup.TEXT_HTML_BLANK))
            assertTrue(nbspOnly.hasTextFlag(RustChapterMarkup.TEXT_HAS_JAVA_NON_WS))
            assertFalse(nbspOnly.hasTextFlag(RustChapterMarkup.TEXT_HAS_KOTLIN_NON_WS))

            val nel = textNode("nel")
            assertTrue(nel.hasTextFlag(RustChapterMarkup.TEXT_HAS_JAVA_NON_WS))
            assertTrue(nel.hasTextFlag(RustChapterMarkup.TEXT_HAS_KOTLIN_NON_WS))

            val vtab = textNode("vtab")
            assertTrue(vtab.hasTextFlag(RustChapterMarkup.TEXT_ALREADY_COLLAPSED_ASCII))
            assertFalse(vtab.hasTextFlag(RustChapterMarkup.TEXT_HTML_BLANK))
            assertTrue(vtab.hasTextFlag(RustChapterMarkup.TEXT_STARTS_HTML_WS))
            assertFalse(vtab.hasTextFlag(RustChapterMarkup.TEXT_ENDS_HTML_WS))
            assertFalse(vtab.hasTextFlag(RustChapterMarkup.TEXT_HAS_JAVA_NON_WS))
            assertFalse(vtab.hasTextFlag(RustChapterMarkup.TEXT_HAS_KOTLIN_NON_WS))

            assertFalse(textNode("double").hasTextFlag(RustChapterMarkup.TEXT_ALREADY_COLLAPSED_ASCII))
            assertFalse(textNode("newline").hasTextFlag(RustChapterMarkup.TEXT_ALREADY_COLLAPSED_ASCII))
            assertFalse(textNode("tab").hasTextFlag(RustChapterMarkup.TEXT_ALREADY_COLLAPSED_ASCII))
            val cr = textNode("cr")
            assertFalse(cr.hasTextFlag(RustChapterMarkup.TEXT_ALREADY_COLLAPSED_ASCII))
            assertTrue(cr.hasTextFlag(RustChapterMarkup.TEXT_HAS_CR))
            assertFalse(textNode("ff").hasTextFlag(RustChapterMarkup.TEXT_ALREADY_COLLAPSED_ASCII))

            val blank = textNode("blank")
            assertFalse(blank.hasTextFlag(RustChapterMarkup.TEXT_ALREADY_COLLAPSED_ASCII))
            assertTrue(blank.hasTextFlag(RustChapterMarkup.TEXT_HTML_BLANK))

            val pre = textNode("pre-collapsed")
            assertTrue(pre.hasTextFlag(RustChapterMarkup.TEXT_ALREADY_COLLAPSED_ASCII))
            assertTrue(pre.hasTextFlag(RustChapterMarkup.TEXT_STARTS_HTML_WS))
            assertTrue(pre.hasTextFlag(RustChapterMarkup.TEXT_ENDS_HTML_WS))

            plain.text("Mutated  text")
            assertEquals(RustChapterMarkup.TEXT_UNKNOWN, plain.textFlags)
            assertFalse(plain.hasTextFlag(RustChapterMarkup.TEXT_ALREADY_COLLAPSED_ASCII))
        }

        val htmlDoc = checkNotNull(
            RustChapterMarkup.parse(
                "<p id='a'>Collapsed HTML</p><p id='b'>Uncollapsed  HTML</p>".encodeToByteArray(),
                htmlOnly = true,
            ),
        )
        val collapsedHtml = htmlDoc.getElementById("a")!!.childNode(0) as RustChapterMarkup.PreindexedTextNode
        val uncollapsedHtml = htmlDoc.getElementById("b")!!.childNode(0) as RustChapterMarkup.PreindexedTextNode
        assertTrue(collapsedHtml.hasTextFlag(RustChapterMarkup.TEXT_ALREADY_COLLAPSED_ASCII))
        assertFalse(uncollapsedHtml.hasTextFlag(RustChapterMarkup.TEXT_ALREADY_COLLAPSED_ASCII))
    }

    @Test
    fun `HtmlMapper maintains exact AnnotatedString parity for pre-collapsed inline spans pre blocks and boundaries`() {
        val xhtml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="en">
              <head>
                <title>Whitespace Parity</title>
                <style><![CDATA[
                  .boxed { display: inline-block; width: 2em; text-align: center; }
                  .upper { text-transform: uppercase; }
                  .caps { text-transform: capitalize; }
                  .row { display: flex; }
                ]]></style>
              </head>
              <body>
                <h1 id="h1">  <span> Leading</span> and <span>trailing </span>  </h1>
                <div class="title2">  <span> Loose</span> <span>heading </span>  </div>
                <div>  Loose <span>inline</span> text  </div>
                <p id="spans1">  <span> first </span><span> second</span><span>  </span><span>third </span>  </p>
                <p id="spans2"><span>Hello</span> <span>world</span>   <span> </span><span>&#160;after</span>&#10;</p>
                <p id="vtab-p"><span>&#x000B;</span> <span>after</span></p>
                <p id="nbsp-only">&#160;&#8239;</p>
                <p id="nel-p">${'\u0085'}</p>
                <p id="nbsp-p"><span>Before </span><span> &#160;after</span> and <em>&#8239;narrow</em>&#x200B;break.</p>
                <p id="inline-mix">Ruby <ruby> 漢 <rp>(</rp><rt> かん </rt><rp>)</rp></ruby> and <span class="boxed"> A </span> after<wbr/>word<br/> next   line</p>
                <p id="transforms" class="upper"> hello <span>WORLD</span> <span class="caps">two words</span></p>
                <p id="math-inline">Lead <math><mi>x</mi><mo>+</mo><mn>1</mn></math> tail and <math><unknown><mtext> a </mtext></unknown></math> end</p>
                <pre id="pre1"> single leading space</pre>
                <pre id="pre2"><span> </span><span>next</span></pre>
                <pre id="pre3">  two spaces&#10;&#9;indented&#13;&#10;<span>  inside span  </span></pre>
                <pre id="pre-blank">   &#10;&#9;  </pre>
                <pre id="pre-nbsp">&#160;</pre>
                <div class="row"><span> Left </span><span> Right </span></div>
                <figure><figcaption>  Caption text  </figcaption></figure>
                <details><summary>  Summary text  </summary></details>
                <table>
                  <caption>  Table cap  </caption>
                  <tr><th>  Col A </th><td><p>  Cell 1  </p><p>  Cell 2  </p></td></tr>
                </table>
              </body>
            </html>
        """.trimIndent()

        val cssText = """
            .boxed { display: inline-block; width: 2em; text-align: center; }
            .upper { text-transform: uppercase; }
            .caps { text-transform: capitalize; }
            .row { display: flex; }
        """.trimIndent()

        // 1. With CSS (exercises font typography annotations, text-transform, inline-block, flex row)
        val rustCssResult = checkNotNull(RustChapterMarkup.parseRetained(xhtml.encodeToByteArray())).use { doc ->
            mapWithParityMapper(doc.bodyElement!!, cssText)
        }
        val jsoupCssDoc = Jsoup.parse(xhtml, "", Parser.xmlParser())
        val jsoupCssResult = mapWithParityMapper(jsoupCssDoc.selectFirst("body")!!, cssText)
        assertParity(jsoupCssResult, rustCssResult)

        // 2. Without CSS (exercises semantic tags, default paragraph/heading/pre paths)
        val rustPlainResult = checkNotNull(RustChapterMarkup.parseRetained(xhtml.encodeToByteArray())).use { doc ->
            mapWithParityMapper(doc.bodyElement!!, cssText = null)
        }
        val jsoupPlainDoc = Jsoup.parse(xhtml, "", Parser.xmlParser())
        val jsoupPlainResult = mapWithParityMapper(jsoupPlainDoc.selectFirst("body")!!, cssText = null)
        assertParity(jsoupPlainResult, rustPlainResult)

        // 3. HTML5 recovery mode + post-parse TextNode mutation fallback parity
        val html5 = """
            <h2 id="hh">  <span>Title</span>  </h2>
            <p id="hp"><span>First</span> <span> second</span><span>  </span><span>third </span>
            <pre id="hpre"> single leading  and&#9;tab</pre>
            <p id="hmut">Original collapsed</p>
        """.trimIndent()
        val rustHtmlDoc = checkNotNull(RustChapterMarkup.parse(html5.encodeToByteArray(), htmlOnly = true))
        val rustMutatedNode = rustHtmlDoc.getElementById("hmut")!!.childNode(0) as TextNode
        rustMutatedNode.text("  Updated   text  ")
        assertEquals(
            RustChapterMarkup.TEXT_UNKNOWN,
            (rustMutatedNode as RustChapterMarkup.PreindexedTextNode).textFlags,
        )
        val rustHtmlResult = mapWithParityMapper(rustHtmlDoc.body(), cssText = null)

        val jsoupHtmlDoc = Jsoup.parse(html5)
        (jsoupHtmlDoc.getElementById("hmut")!!.childNode(0) as TextNode).text("  Updated   text  ")
        val jsoupHtmlResult = mapWithParityMapper(jsoupHtmlDoc.body(), cssText = null)
        assertParity(jsoupHtmlResult, rustHtmlResult)
    }

    @Test
    fun `SUBTREE_SIMPLE_LEAF_TEXT classification mutation invalidation and fast leaf-block parity`() {
        val xhtml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" lang="en">
              <head>
                <title>Leaf Block Fast Path</title>
                <style><![CDATA[
                  .upper { text-transform: uppercase; }
                  .lower { text-transform: lowercase; }
                  .caps { text-transform: capitalize; }
                  .drop::first-letter { float: left; font-size: 2.2em; font-weight: bold; }
                  .before-deco::before { content: "§ "; font-weight: bold; }
                  .after-deco::after { content: " ❖"; color: #556677; }
                  .boxed { border: 1px solid #223344; padding: 0.4em; background-color: #f4f4f4; }
                  .CodeFont { font-family: monospace; font-feature-settings: "zero" 1; font-variant-caps: small-caps; }
                  .hidden-vis { visibility: hidden; }
                  .page-break-wrap { page-break-before: always; }
                ]]></style>
              </head>
              <body>
                <div id="wrap" class="boxed">
                  <h1 id="h1">  Chapter One  </h1>
                  <h2 id="h2" class="upper" style="color: #112233; text-align: center;">  second   heading </h2>
                  <h3 id="h3" class="before-deco">Decorated Heading</h3>
                  <h4 id="h4" class="after-deco">After Heading</h4>
                  <h5 id="h5" lang="tr" class="upper">istanbul</h5>
                  <h6 id="h6" style="font-style: italic; font-weight: 700; font-size: 1.2em;">Subheading Six</h6>
                  <p id="p-plain">Simple paragraph with single ASCII spaces.</p>
                  <p id="p-ws">  Leading,   internal
                    and trailing whitespace  </p>
                  <p id="p-style" style="color: #334455; background-color: #eeeeee; text-indent: 1.5em; line-height: 1.6; font-weight: 600; font-style: italic;">Inline styled paragraph</p>
                  <p id="p-lower" class="lower">MIXED Case Paragraph</p>
                  <p id="p-caps" class="caps">  once upon a time in frogreader  </p>
                  <p id="p-font" class="CodeFont" lang="fr" dir="ltr">Monospace typography leaf</p>
                  <p id="p-hidden-vis" class="hidden-vis">Visibly hidden text</p>
                  <p id="p-drop" class="drop">Drop cap paragraph on simple text.</p>
                  <p id="p-before" class="before-deco">Generated before paragraph.</p>
                  <p id="p-after" class="after-deco">Generated after paragraph.</p>
                  <p id="p-title-class" class="title2">  Promoted heading via title class  </p>
                  <div class="title3"><p id="p-in-title-div">  Promoted heading via parent div  </p></div>
                  <div class="page-break-wrap"><p id="p-break">First leaf under page-break wrapper</p></div>
                  <blockquote><p id="p-quote">Quoted simple leaf paragraph.</p></blockquote>
                  <p id="p-with-span"><span id="inner-span">Child element</span></p>
                  <p id="p-comment">Text with comment<!-- c --></p>
                  <p id="p-cdata"><![CDATA[CDATA leaf]]></p>
                  <p id="p-blank">   &#10;  </p>
                  <p id="p-nbsp">&#160;&#8239;</p>
                  <p id="p-empty"></p>
                  <p id="p-empty-line" class="empty-line">   </p>
                </div>
              </body>
            </html>
        """.trimIndent()

        val cssText = """
            .upper { text-transform: uppercase; }
            .lower { text-transform: lowercase; }
            .caps { text-transform: capitalize; }
            .drop::first-letter { float: left; font-size: 2.2em; font-weight: bold; }
            .before-deco::before { content: "§ "; font-weight: bold; }
            .after-deco::after { content: " ❖"; color: #556677; }
            .boxed { border: 1px solid #223344; padding: 0.4em; background-color: #f4f4f4; }
            .CodeFont { font-family: monospace; font-feature-settings: "zero" 1; font-variant-caps: small-caps; }
            .hidden-vis { visibility: hidden; }
            .page-break-wrap { page-break-before: always; }
        """.trimIndent()

        // 1. Verify SUBTREE_SIMPLE_LEAF_TEXT classification & non-propagation
        checkNotNull(RustChapterMarkup.parseRetained(xhtml.encodeToByteArray())).use { doc ->
            fun elem(id: String): RustChapterMarkup.PreindexedElement =
                doc.getElementById(id) as RustChapterMarkup.PreindexedElement

            for (id in listOf(
                "h1", "h2", "h3", "h4", "h5", "h6",
                "p-plain", "p-ws", "p-style", "p-lower", "p-caps", "p-font",
                "p-hidden-vis", "p-drop", "p-before", "p-after",
                "p-title-class", "p-in-title-div", "p-break", "p-quote", "inner-span",
            )) {
                assertTrue("expected SUBTREE_SIMPLE_LEAF_TEXT on #$id", elem(id).hasSubtreeFlag(RustChapterMarkup.SUBTREE_SIMPLE_LEAF_TEXT))
            }

            for (id in listOf("wrap", "p-with-span", "p-comment", "p-cdata", "p-blank", "p-nbsp", "p-empty", "p-empty-line")) {
                assertFalse("expected no SUBTREE_SIMPLE_LEAF_TEXT on #$id", elem(id).hasSubtreeFlag(RustChapterMarkup.SUBTREE_SIMPLE_LEAF_TEXT))
            }

            // Cloning invalidates the clone's subtree flag without dirtying the original
            val clone = elem("p-plain").clone() as RustChapterMarkup.PreindexedElement
            assertFalse(clone.hasSubtreeFlag(RustChapterMarkup.SUBTREE_SIMPLE_LEAF_TEXT))
            assertTrue(elem("p-plain").hasSubtreeFlag(RustChapterMarkup.SUBTREE_SIMPLE_LEAF_TEXT))

            // Mutating child TextNode invalidates SUBTREE_SIMPLE_LEAF_TEXT on that element
            val wsText = elem("p-ws").childNode(0) as TextNode
            wsText.text("   ")
            assertFalse(elem("p-ws").hasSubtreeFlag(RustChapterMarkup.SUBTREE_SIMPLE_LEAF_TEXT))
            assertTrue(elem("p-plain").hasSubtreeFlag(RustChapterMarkup.SUBTREE_SIMPLE_LEAF_TEXT))

            // Structural mutation on an element invalidates SUBTREE_SIMPLE_LEAF_TEXT
            elem("p-plain").appendElement("em").text("extra")
            assertFalse(elem("p-plain").hasSubtreeFlag(RustChapterMarkup.SUBTREE_SIMPLE_LEAF_TEXT))
        }

        // 2. Full parity against Jsoup with CSS
        val rustCssResult = checkNotNull(RustChapterMarkup.parseRetained(xhtml.encodeToByteArray())).use { doc ->
            mapWithParityMapper(doc.bodyElement!!, cssText)
        }
        val jsoupCssDoc = Jsoup.parse(xhtml, "", Parser.xmlParser())
        val jsoupCssResult = mapWithParityMapper(jsoupCssDoc.selectFirst("body")!!, cssText)
        assertParity(jsoupCssResult, rustCssResult)

        // 3. Full parity against Jsoup without CSS
        val rustPlainResult = checkNotNull(RustChapterMarkup.parseRetained(xhtml.encodeToByteArray())).use { doc ->
            mapWithParityMapper(doc.bodyElement!!, cssText = null)
        }
        val jsoupPlainDoc = Jsoup.parse(xhtml, "", Parser.xmlParser())
        val jsoupPlainResult = mapWithParityMapper(jsoupPlainDoc.selectFirst("body")!!, cssText = null)
        assertParity(jsoupPlainResult, rustPlainResult)

        // 4. Parity after post-parse child text mutation on RetainedDocument and structural tree mutations on MarkupDocument
        val rustRetainedTextMutated = checkNotNull(RustChapterMarkup.parseRetained(xhtml.encodeToByteArray())).use { doc ->
            val pPlain = doc.getElementById("p-plain") as RustChapterMarkup.PreindexedElement
            (pPlain.childNode(0) as TextNode).text("  Mutated   leaf text  ")
            assertFalse(pPlain.hasSubtreeFlag(RustChapterMarkup.SUBTREE_SIMPLE_LEAF_TEXT))
            mapWithParityMapper(doc.bodyElement!!, cssText)
        }
        val jsoupTextMutatedDoc = Jsoup.parse(xhtml, "", Parser.xmlParser())
        (jsoupTextMutatedDoc.getElementById("p-plain")!!.childNode(0) as TextNode).text("  Mutated   leaf text  ")
        assertParity(mapWithParityMapper(jsoupTextMutatedDoc.selectFirst("body")!!, cssText), rustRetainedTextMutated)

        fun mutateDoc(doc: Document) {
            (doc.getElementById("p-plain")!!.childNode(0) as TextNode).text("  Mutated   leaf text  ")
            doc.getElementById("h1")!!.appendElement("span").text(" Part 2")
            doc.getElementById("p-style")!!.empty()
        }
        val rustMutatedDoc = checkNotNull(RustChapterMarkup.parse(xhtml.encodeToByteArray(), htmlOnly = false)) as RustChapterMarkup.MarkupDocument
        mutateDoc(rustMutatedDoc)
        assertTrue(rustMutatedDoc.treeMutated)
        val rustMutatedResult = mapWithParityMapper(rustMutatedDoc.bodyElement!!, cssText)
        val jsoupMutatedDoc = Jsoup.parse(xhtml, "", Parser.xmlParser())
        mutateDoc(jsoupMutatedDoc)
        val jsoupMutatedResult = mapWithParityMapper(jsoupMutatedDoc.selectFirst("body")!!, cssText)
        assertParity(jsoupMutatedResult, rustMutatedResult)
    }

    @Test
    fun `ATTR_ANC_HAS_FLOW_SCOPE and anonymous CSS text-line SUBTREE_SIMPLE_LEAF_TEXT fast path maintain exact parity`() {
        val xhtml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="en">
              <head>
                <title>Flow Scope &amp; Verse Lines</title>
                <style><![CDATA[
                  .verse-line { display: block; text-indent: -1.5em; padding-left: 1.5em; }
                  .verse-upper { display: block; text-transform: uppercase; font-family: monospace; }
                  .verse-before { display: block; }
                  .verse-before::before { content: "¶ "; }
                  .verse-after { display: block; }
                  .verse-after::after { content: " ♪"; }
                  .verse-drop { display: block; }
                  .verse-drop::first-letter { float: left; font-size: 2em; }
                  .bidi-embed { unicode-bidi: embed; direction: rtl; }
                  .bidi-override { unicode-bidi: bidi-override; direction: rtl; }
                  .hidden-line { display: block; visibility: hidden; }
                ]]></style>
              </head>
              <body id="body-root">
                <p id="p-direct">Direct paragraph in body.</p>
                <h1 id="h1-direct">Heading 1</h1>
                <h2 id="h2-direct">Heading 2</h2>
                <h3 id="h3-direct">Heading 3</h3>
                <h4 id="h4-direct">Heading 4</h4>
                <h5 id="h5-direct">Heading 5</h5>
                <h6 id="h6-direct">Heading 6</h6>
                <section id="sec">
                  <blockquote id="bq">
                    <div id="div-standard">
                      <p id="p-in-bq">Quoted paragraph inside section and div.</p>
                      <h2 id="h2-in-bq">Quoted heading</h2>
                    </div>
                  </blockquote>
                </section>

                <!-- Anonymous CSS text lines (e.g. Hebrew Bible poetry/verse spans) -->
                <div id="stanza">
                  <span id="line-1" class="verse-line">  In the beginning   was the Word  </span>
                  <span id="line-2" class="verse-upper">  and the word was   with frogreader </span>
                  <span id="line-3" class="verse-before">Decorated before verse line</span>
                  <span id="line-4" class="verse-after">Decorated after verse line</span>
                  <span id="line-5" class="verse-drop">Drop cap verse line</span>
                  <span id="line-6" class="hidden-line">Hidden visibility verse line</span>
                  <em id="line-em" class="verse-line">Italicized anonymous block line</em>
                  <span id="line-rtl" class="verse-line" dir="rtl">שלום עולם</span>
                </div>

                <!-- Inline links wrapping blocks (internal note, internal link, external link) -->
                <a id="a-note" epub:type="noteref" href="chap.xhtml#fn1">
                  <p id="p-in-a-note">Block paragraph wrapped in noteref link</p>
                  <div id="div-in-a-note">Loose text in div inside noteref link</div>
                </a>
                <a id="a-internal" href="chap.xhtml#sec">
                  <div id="div-in-a-internal">
                    <p id="p-in-a-internal">Nested block paragraph inside internal link</p>
                    <span id="line-in-a" class="verse-line">Anonymous CSS text line inside internal link</span>
                  </div>
                </a>
                <a id="a-external" href="https://example.org/docs">
                  <p id="p-in-a-ext">Block paragraph inside external link</p>
                </a>

                <!-- Bidi inline scopes wrapping blocks and anonymous CSS text lines -->
                <span id="span-bidi" dir="rtl">
                  <p id="p-in-span-bidi">Paragraph inside dir=rtl span</p>
                  <span id="line-in-span-bidi" class="verse-line">Verse line inside dir=rtl span</span>
                </span>
                <bdi id="bdi-scope">
                  <p id="p-in-bdi">Paragraph inside bdi isolate scope</p>
                </bdi>
                <bdo id="bdo-scope" dir="rtl">
                  <div id="div-in-bdo">
                    <p id="p-in-bdo">Paragraph inside bdo override scope</p>
                  </div>
                </bdo>
                <em id="em-bidi" class="bidi-embed">
                  <p id="p-in-em-bidi">Paragraph inside CSS bidi-embed em</p>
                </em>
                <span id="span-override" class="bidi-override">
                  <span id="line-in-override" class="verse-line">Verse line inside CSS bidi-override</span>
                </span>

                <aside id="fn1" epub:type="footnote"><p id="p-fn1">Footnote target text.</p></aside>
              </body>
            </html>
        """.trimIndent()

        val cssText = """
            .verse-line { display: block; text-indent: -1.5em; padding-left: 1.5em; }
            .verse-upper { display: block; text-transform: uppercase; font-family: monospace; }
            .verse-before { display: block; }
            .verse-before::before { content: "¶ "; }
            .verse-after { display: block; }
            .verse-after::after { content: " ♪"; }
            .verse-drop { display: block; }
            .verse-drop::first-letter { float: left; font-size: 2em; }
            .bidi-embed { unicode-bidi: embed; direction: rtl; }
            .bidi-override { unicode-bidi: bidi-override; direction: rtl; }
            .hidden-line { display: block; visibility: hidden; }
        """.trimIndent()

        // 1. Verify ATTR_ANC_HAS_FLOW_SCOPE classification and DOM mutation invalidation
        checkNotNull(RustChapterMarkup.parseRetained(xhtml.encodeToByteArray())).use { doc ->
            fun elem(id: String): RustChapterMarkup.PreindexedElement =
                doc.getElementById(id) as RustChapterMarkup.PreindexedElement

            for (id in listOf(
                "body-root",
                "p-direct", "h1-direct", "h2-direct", "h3-direct", "h4-direct", "h5-direct", "h6-direct",
                "sec", "bq", "div-standard", "p-in-bq", "h2-in-bq",
                "stanza", "fn1", "p-fn1",
            )) {
                assertFalse(
                    "expected ATTR_ANC_HAS_FLOW_SCOPE == false on #$id",
                    elem(id).hasAttrFlag(RustChapterMarkup.ATTR_ANC_HAS_FLOW_SCOPE),
                )
            }

            for (id in listOf(
                "line-1", "line-2", "line-3", "line-4", "line-5", "line-6", "line-em", "line-rtl",
                "a-note", "p-in-a-note", "div-in-a-note",
                "a-internal", "div-in-a-internal", "p-in-a-internal", "line-in-a",
                "a-external", "p-in-a-ext",
                "span-bidi", "p-in-span-bidi", "line-in-span-bidi",
                "bdi-scope", "p-in-bdi",
                "bdo-scope", "div-in-bdo", "p-in-bdo",
                "em-bidi", "p-in-em-bidi",
                "span-override", "line-in-override",
            )) {
                assertTrue(
                    "expected ATTR_ANC_HAS_FLOW_SCOPE == true on #$id",
                    elem(id).hasAttrFlag(RustChapterMarkup.ATTR_ANC_HAS_FLOW_SCOPE),
                )
                assertTrue(
                    "expected SUBTREE_SIMPLE_LEAF_TEXT or ancestor flow scope on #$id",
                    elem(id).hasAttr(RustChapterMarkup.ATTR_ANC_HAS_FLOW_SCOPE),
                )
            }

            // Anonymous CSS text-line spans have SUBTREE_SIMPLE_LEAF_TEXT
            for (id in listOf("line-1", "line-2", "line-3", "line-4", "line-5", "line-6", "line-em", "line-rtl", "line-in-a")) {
                assertTrue(
                    "expected SUBTREE_SIMPLE_LEAF_TEXT on #$id",
                    elem(id).hasSubtreeFlag(RustChapterMarkup.SUBTREE_SIMPLE_LEAF_TEXT),
                )
            }

            // Cloning invalidates ATTR_ANC_HAS_FLOW_SCOPE on the clone without dirtying doc
            val clonedInA = elem("p-in-a-note").clone() as RustChapterMarkup.PreindexedElement
            assertFalse(clonedInA.hasAttrFlag(RustChapterMarkup.ATTR_ANC_HAS_FLOW_SCOPE))
            assertTrue(elem("p-in-a-note").hasAttrFlag(RustChapterMarkup.ATTR_ANC_HAS_FLOW_SCOPE))

            // Structural DOM mutation invalidates ATTR_ANC_HAS_FLOW_SCOPE across the document
            elem("div-standard").appendElement("span").text("mutated")
            assertTrue(doc.treeMutated)
            assertFalse(elem("p-in-a-note").hasAttrFlag(RustChapterMarkup.ATTR_ANC_HAS_FLOW_SCOPE))
            assertFalse(elem("p-in-bdi").hasAttrFlag(RustChapterMarkup.ATTR_ANC_HAS_FLOW_SCOPE))
        }

        // 2. Full parity against Jsoup with CSS
        val rustCssResult = checkNotNull(RustChapterMarkup.parseRetained(xhtml.encodeToByteArray())).use { doc ->
            mapWithParityMapper(doc.bodyElement!!, cssText)
        }
        val jsoupCssDoc = Jsoup.parse(xhtml, "", Parser.xmlParser())
        val jsoupCssResult = mapWithParityMapper(jsoupCssDoc.selectFirst("body")!!, cssText)
        assertParity(jsoupCssResult, rustCssResult)

        // 3. Full parity against Jsoup without CSS
        val rustPlainResult = checkNotNull(RustChapterMarkup.parseRetained(xhtml.encodeToByteArray())).use { doc ->
            mapWithParityMapper(doc.bodyElement!!, cssText = null)
        }
        val jsoupPlainDoc = Jsoup.parse(xhtml, "", Parser.xmlParser())
        val jsoupPlainResult = mapWithParityMapper(jsoupPlainDoc.selectFirst("body")!!, cssText = null)
        assertParity(jsoupPlainResult, rustPlainResult)

        // 4. Parity when a standard <p> (originally ATTR_ANC_HAS_FLOW_SCOPE == false) is reparented into <a> or <span dir="rtl"> post-parse
        fun reparentIntoFlowScopes(doc: Document) {
            val pDirect = doc.getElementById("p-direct")!!
            val aExternal = doc.getElementById("a-external")!!
            aExternal.appendChild(pDirect)

            val pInBq = doc.getElementById("p-in-bq")!!
            val spanBidi = doc.getElementById("span-bidi")!!
            spanBidi.appendChild(pInBq)
        }
        val rustReparentedResult = checkNotNull(RustChapterMarkup.parseRetained(xhtml.encodeToByteArray())).use { doc ->
            reparentIntoFlowScopes(doc)
            assertTrue(doc.treeMutated)
            mapWithParityMapper(doc.bodyElement!!, cssText)
        }
        val jsoupReparentedDoc = Jsoup.parse(xhtml, "", Parser.xmlParser())
        reparentIntoFlowScopes(jsoupReparentedDoc)
        val jsoupReparentedResult = mapWithParityMapper(jsoupReparentedDoc.selectFirst("body")!!, cssText)
        assertParity(jsoupReparentedResult, rustReparentedResult)
    }

    @Test
    fun `RustEpubArchive parseChapterDocument matches byte-array parsing for retained and non-retained trees`() {
        val cssText = """
            .verse-line { display: block; text-indent: -1.2em; }
            .lead { color: #224466; font-weight: 600; }
            .lead::first-letter { float: left; font-size: 2em; }
            .note-box { border: 1px solid #333; padding: 0.5em; }
        """.trimIndent()
        val xhtmlChapter = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="en">
              <head>
                <title>Zip Chapter 1</title>
                <link rel="stylesheet" href="styles/main.css"/>
              </head>
              <body>
                <h1 id="ch1">Chapter One</h1>
                <p id="p1" class="lead">First paragraph with <a id="ref1" epub:type="noteref" href="ch1.xhtml#fn1"><sup>1</sup></a> and <bdi>abc</bdi>.</p>
                <div id="poem">
                  <span id="v1" class="verse-line">Line one of verse</span>
                  <span id="v2" class="verse-line">Line two of verse</span>
                </div>
                <a id="wrap-link" href="https://example.org/zip"><p id="p-linked">Linked block</p></a>
                <aside id="fn1" epub:type="footnote" class="note-box"><p id="fn1-body">Footnote body</p></aside>
              </body>
            </html>
        """.trimIndent()
        val utf16Chapter = (byteArrayOf(0xfe.toByte(), 0xff.toByte()) + """
            <?xml version="1.0" encoding="UTF-16"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>UTF16 Chapter</title></head>
              <body><p id="u1">שלום עולם</p></body>
            </html>
        """.trimIndent().toByteArray(Charsets.UTF_16BE))
        val htmlFragmentChapter = "<title>HTML Fallback</title><p id='f1'>Recovered <em>HTML</em><p id='f2'>Second"

        val epubFile = File.createTempFile("rust-zip-markup-test-", ".epub")
        try {
            ZipOutputStream(epubFile.outputStream().buffered()).use { zipOut ->
                fun put(name: String, bytes: ByteArray) {
                    zipOut.putNextEntry(ZipEntry(name))
                    zipOut.write(bytes)
                    zipOut.closeEntry()
                }
                put("OEBPS/ch1.xhtml", xhtmlChapter.encodeToByteArray())
                put("OEBPS/ch2.xhtml", utf16Chapter)
                put("OEBPS/ch3.html", htmlFragmentChapter.encodeToByteArray())
            }

            fun assertPreindexedTreeIdentical(expected: Document, actual: Document) {
                assertEquals(expected.outerHtml(), actual.outerHtml())
                val expDoc = expected as RustChapterMarkup.MarkupDocument
                val actDoc = actual as RustChapterMarkup.MarkupDocument
                assertEquals(expDoc.docFlags, actDoc.docFlags)
                assertEquals(expDoc.headTitleElement?.text(), actDoc.headTitleElement?.text())
                assertEquals(
                    expDoc.stylesheetElements.map { it.outerHtml() },
                    actDoc.stylesheetElements.map { it.outerHtml() },
                )
                val expElems = expected.getAllElements()
                val actElems = actual.getAllElements()
                assertEquals(expElems.size, actElems.size)
                for (i in expElems.indices) {
                    val expEl = expElems[i]
                    val actEl = actElems[i]
                    assertEquals(expEl.tagName(), actEl.tagName())
                    if (expEl is RustChapterMarkup.PreindexedElement) {
                        val actPre = actEl as RustChapterMarkup.PreindexedElement
                        assertEquals("tagFlags on ${expEl.tagName()}", expEl.tagFlags, actPre.tagFlags)
                        assertEquals("attrFlags on ${expEl.tagName()}", expEl.attrFlags, actPre.attrFlags)
                        assertEquals("subtreeFlags on ${expEl.tagName()}", expEl.subtreeFlags, actPre.subtreeFlags)
                        assertEquals(expEl.anchorId, actPre.anchorId)
                        assertEquals(expEl.hrefAttr, actPre.hrefAttr)
                    }
                    assertEquals(expEl.childNodeSize(), actEl.childNodeSize())
                    for (c in 0 until expEl.childNodeSize()) {
                        val expChild = expEl.childNode(c)
                        val actChild = actEl.childNode(c)
                        if (expChild is RustChapterMarkup.PreindexedTextNode) {
                            val actText = actChild as RustChapterMarkup.PreindexedTextNode
                            assertEquals(expChild.textFlags, actText.textFlags)
                            assertEquals(expChild.wholeText, actText.wholeText)
                        }
                    }
                }
            }

            val limits = ReaderResourceLimits()
            RustEpubArchive.open(epubFile, limits).use { archive ->
                for (name in listOf("OEBPS/ch1.xhtml", "OEBPS/ch2.xhtml", "OEBPS/ch3.html")) {
                    val entry = checkNotNull(archive.getEntry(name))
                    val rawBytes = archive.readRequired(entry, limits.maxChapterBytes, name)

                    val expectedRetained = checkNotNull(RustChapterMarkup.parseRetained(rawBytes))
                    val actualRetained = checkNotNull(
                        archive.parseChapterDocument(
                            entry = entry,
                            maxBytes = limits.maxChapterBytes,
                            label = name,
                            optional = false,
                            retainTree = true,
                        ),
                    )
                    assertTrue(actualRetained is RustChapterMarkup.RetainedDocument)

                    expectedRetained.use { exp ->
                        (actualRetained as RustChapterMarkup.RetainedDocument).use { act ->
                            assertPreindexedTreeIdentical(exp, act)
                            val expMapped = mapWithParityMapper(exp.bodyElement!!, cssText)
                            val actMapped = mapWithParityMapper(act.bodyElement!!, cssText)
                            assertParity(expMapped, actMapped)
                        }
                    }

                    val actualNonRetained = checkNotNull(
                        archive.parseChapterDocument(
                            entry = entry,
                            maxBytes = limits.maxChapterBytes,
                            label = name,
                            optional = false,
                            retainTree = false,
                        ),
                    )
                    assertFalse(actualNonRetained is RustChapterMarkup.RetainedDocument)
                    assertTrue(actualNonRetained is RustChapterMarkup.MarkupDocument)
                    val expectedNonRetained = checkNotNull(RustChapterMarkup.parse(rawBytes, htmlOnly = false))
                    assertPreindexedTreeIdentical(expectedNonRetained, actualNonRetained)
                    val expMapped = mapWithParityMapper(
                        (expectedNonRetained as RustChapterMarkup.MarkupDocument).bodyElement!!,
                        cssText,
                    )
                    val actMapped = mapWithParityMapper(
                        (actualNonRetained as RustChapterMarkup.MarkupDocument).bodyElement!!,
                        cssText,
                    )
                    assertParity(expMapped, actMapped)
                }

                // Budget enforcement on parseChapterDocument
                val ch1 = checkNotNull(archive.getEntry("OEBPS/ch1.xhtml"))
                assertNull(
                    archive.parseChapterDocument(
                        entry = ch1,
                        maxBytes = 16L,
                        label = "OEBPS/ch1.xhtml",
                        optional = true,
                        retainTree = true,
                    ),
                )
                try {
                    archive.parseChapterDocument(
                        entry = ch1,
                        maxBytes = 16L,
                        label = "OEBPS/ch1.xhtml",
                        optional = false,
                        retainTree = true,
                    )
                    fail("expected ResourceLimitException")
                } catch (_: ResourceLimitException) {
                    // expected
                }
            }

            // Invalid handles / negative indices return null cleanly
            assertNull(RustChapterMarkup.parseFromZip(0L, 0, htmlOnly = false))
            assertNull(RustChapterMarkup.parseRetainedFromZip(0L, 0))
            assertNull(RustChapterMarkup.parseFromZip(1L, -1, htmlOnly = false))
            assertNull(RustChapterMarkup.parseRetainedFromZip(1L, -1))
        } finally {
            epubFile.delete()
        }
    }

    @Test
    fun `SUBTREE_HAS_INLINE_FLOW_CANDIDATE CSS flow block and explicit floated initial fast paths maintain parity`() {
        // 1. Verify CssResolver.hasDisplayRules classification
        val noDisplayCss = CssResolver(listOf(CssResolver.Sheet("p { text-indent: 1.5em; color: #223344; }", "")))
        assertFalse(noDisplayCss.hasDisplayRules)
        noDisplayCss.close()

        val displayCss = CssResolver(listOf(CssResolver.Sheet("span.block { display: block; }", "")))
        assertTrue(displayCss.hasDisplayRules)
        displayCss.close()

        val customPropCss = CssResolver(listOf(CssResolver.Sheet("body { --box-display: block; }", "")))
        assertTrue(customPropCss.hasDisplayRules)
        customPropCss.close()

        // 2. Verify SUBTREE_HAS_INLINE_FLOW_CANDIDATE flag on plain vs inline-containing chapters
        val plainXhtml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body id="plain-body">
                <div id="plain-div">
                  <p id="plain-p">Plain text paragraph.</p>
                  <p id="plain-br">Line one<br/>Line two</p>
                </div>
              </body>
            </html>
        """.trimIndent()
        checkNotNull(RustChapterMarkup.parseRetained(plainXhtml.encodeToByteArray())).use { doc ->
            val body = doc.bodyElement as RustChapterMarkup.PreindexedElement
            val div = doc.getElementById("plain-div") as RustChapterMarkup.PreindexedElement
            val p = doc.getElementById("plain-p") as RustChapterMarkup.PreindexedElement
            val pBr = doc.getElementById("plain-br") as RustChapterMarkup.PreindexedElement
            assertFalse(body.hasSubtree(RustChapterMarkup.SUBTREE_HAS_INLINE_FLOW_CANDIDATE))
            assertFalse(div.hasSubtree(RustChapterMarkup.SUBTREE_HAS_INLINE_FLOW_CANDIDATE))
            assertFalse(p.hasSubtree(RustChapterMarkup.SUBTREE_HAS_INLINE_FLOW_CANDIDATE))
            assertFalse(pBr.hasSubtree(RustChapterMarkup.SUBTREE_HAS_INLINE_FLOW_CANDIDATE))
            val jsoupPlain = Jsoup.parse(plainXhtml, "", Parser.xmlParser())
            assertParity(
                mapWithParityMapper(jsoupPlain.selectFirst("body")!!, "span.block { display: block; }"),
                mapWithParityMapper(doc.bodyElement!!, "span.block { display: block; }"),
            )
        }

        // 3. Verify SUBTREE_HAS_INLINE_FLOW_CANDIDATE, inline display:block, stylesheet display:block,
        //    and explicitFloatedInitial parity (both with and without stylesheet display rules)
        val xhtml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" lang="en">
              <head><title>Flow &amp; Initial Fast Paths</title></head>
              <body>
                <p id="p-plain">Plain paragraph with no inline elements.</p>
                <p id="p-with-span"><span id="inline-span">Normal inline span</span> inside paragraph.</p>
                <div id="inline-flow-group">
                  <span id="inline-block-1" style="display: block">Inline block line one</span>
                  <div style="display: none">Hidden sibling</div>
                  <br/>
                  <span id="inline-block-2" style="display: block">Inline block line two</span>
                </div>
                <div id="sheet-flow-group">
                  <span id="sheet-block-1" class="block">Stylesheet block line one</span>
                  <span id="sheet-block-2" class="block">Stylesheet block line two</span>
                </div>
                <p id="p-inline-drop"><span style="float: left; font-size: 2.5em; font-weight: bold">T</span>his opens with an inline floated initial.</p>
                <p id="p-sheet-drop"><em class="drop">W</em>ith a stylesheet floated initial.</p>
                <p id="p-font-drop"><font style="float: right; font-size: 2em">R</font>ight-floated font initial.</p>
                <p id="p-mutate-target">Will become floated initial after mutation.</p>
              </body>
            </html>
        """.trimIndent()

        checkNotNull(RustChapterMarkup.parseRetained(xhtml.encodeToByteArray())).use { doc ->
            val pPlain = doc.getElementById("p-plain") as RustChapterMarkup.PreindexedElement
            val pWithSpan = doc.getElementById("p-with-span") as RustChapterMarkup.PreindexedElement
            val inlineSpan = doc.getElementById("inline-span") as RustChapterMarkup.PreindexedElement
            val body = doc.bodyElement as RustChapterMarkup.PreindexedElement
            assertFalse(pPlain.hasSubtree(RustChapterMarkup.SUBTREE_HAS_INLINE_FLOW_CANDIDATE))
            assertTrue(pWithSpan.hasSubtree(RustChapterMarkup.SUBTREE_HAS_INLINE_FLOW_CANDIDATE))
            assertTrue(inlineSpan.hasSubtree(RustChapterMarkup.SUBTREE_HAS_INLINE_FLOW_CANDIDATE))
            assertTrue(body.hasSubtree(RustChapterMarkup.SUBTREE_HAS_INLINE_FLOW_CANDIDATE))
        }

        // 3a. Stylesheet WITHOUT display rules (exercises !hasDisplayRules + SUBTREE_HAS_STYLE fast path)
        val cssWithoutDisplay = """
            p { text-indent: 1.2em; }
            .drop { float: left; font-size: 2.2em; }
        """.trimIndent()
        val rustNoDisplay = checkNotNull(RustChapterMarkup.parseRetained(xhtml.encodeToByteArray())).use { doc ->
            mapWithParityMapper(doc.bodyElement!!, cssWithoutDisplay)
        }
        val jsoupNoDisplay = mapWithParityMapper(
            Jsoup.parse(xhtml, "", Parser.xmlParser()).selectFirst("body")!!,
            cssWithoutDisplay,
        )
        assertParity(jsoupNoDisplay, rustNoDisplay)

        // 3b. Stylesheet WITH display rules (span.block { display: block })
        val cssWithDisplay = """
            p { text-indent: 1.2em; }
            span.block { display: block; margin-top: 0.4em; }
            .drop { float: left; font-size: 2.2em; }
        """.trimIndent()
        val rustWithDisplay = checkNotNull(RustChapterMarkup.parseRetained(xhtml.encodeToByteArray())).use { doc ->
            mapWithParityMapper(doc.bodyElement!!, cssWithDisplay)
        }
        val jsoupWithDisplay = mapWithParityMapper(
            Jsoup.parse(xhtml, "", Parser.xmlParser()).selectFirst("body")!!,
            cssWithDisplay,
        )
        assertParity(jsoupWithDisplay, rustWithDisplay)

        // 3c. Post-parse style attribute mutation (invalidates MUTATED_STYLE when !hasDisplayRules)
        fun addInlineBlockStyle(doc: Document) {
            doc.getElementById("sheet-block-1")!!.attr("style", "display: block")
        }
        val rustStyleMutated = checkNotNull(RustChapterMarkup.parseRetained(xhtml.encodeToByteArray())).use { doc ->
            addInlineBlockStyle(doc)
            assertTrue((doc.mutatedAttrs and RustChapterMarkup.MUTATED_STYLE) != 0)
            mapWithParityMapper(doc.bodyElement!!, cssWithoutDisplay)
        }
        val jsoupStyleMutatedDoc = Jsoup.parse(xhtml, "", Parser.xmlParser())
        addInlineBlockStyle(jsoupStyleMutatedDoc)
        assertParity(
            mapWithParityMapper(jsoupStyleMutatedDoc.selectFirst("body")!!, cssWithoutDisplay),
            rustStyleMutated,
        )

        // 3d. Post-parse DOM mutation inserting a floated initial into a previously plain <p>
        fun insertFloatedInitial(doc: Document) {
            val target = doc.getElementById("p-mutate-target")!!
            target.prepend("<span style=\"float: left; font-size: 2.4em\">M</span>")
        }
        val rustDomMutatedDoc = checkNotNull(
            RustChapterMarkup.parse(xhtml.encodeToByteArray(), htmlOnly = false),
        ) as RustChapterMarkup.MarkupDocument
        insertFloatedInitial(rustDomMutatedDoc)
        assertTrue(rustDomMutatedDoc.treeMutated)
        val rustDomMutated = mapWithParityMapper(rustDomMutatedDoc.bodyElement!!, cssWithoutDisplay)
        val jsoupDomMutatedDoc = Jsoup.parse(xhtml, "", Parser.xmlParser())
        insertFloatedInitial(jsoupDomMutatedDoc)
        assertParity(
            mapWithParityMapper(jsoupDomMutatedDoc.selectFirst("body")!!, cssWithoutDisplay),
            rustDomMutated,
        )
    }

    @Test
    fun `MRK2 ATTR_HAS_PRESENTATIONAL mutation tracking and horizontal row fast exit maintain parity`() {
        val xhtml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body id="body-root">
                <div id="title-no-pres" class="title5"><h2>Section</h2></div>
                <div id="title-with-pres" class="title5" align="center"><h2>Centered</h2></div>
                <p id="plain-p">Plain paragraph</p>
                <p id="empty-pres" align="" color="" text="" bgcolor="">Empty attrs</p>
                <p id="aligned-p" ALIGN="right">Right aligned</p>
                <p id="colored-p"><font id="colored-font" color="#336699">Blue text</font></p>
                <p id="bg-p" bgcolor="#eeeeee">Shaded paragraph</p>
                <p id="dir-p" dir="rtl">שלום עולם</p>
                <div id="plain-row"><span>Left</span><span>Right</span></div>
                <div id="inline-flex-row" style="display: flex"><span>A</span><span>B</span></div>
                <div id="mutate-flex-row"><span>C</span><span>D</span></div>
              </body>
            </html>
        """.trimIndent()

        // 1. Verify preindex flags before mutation.
        checkNotNull(RustChapterMarkup.parseRetained(xhtml.encodeToByteArray())).use { doc ->
            fun pre(id: String) = doc.getElementById(id) as RustChapterMarkup.PreindexedElement
            assertFalse(pre("plain-p").hasAttrFlag(RustChapterMarkup.ATTR_HAS_PRESENTATIONAL))
            assertFalse(pre("empty-pres").hasAttrFlag(RustChapterMarkup.ATTR_HAS_PRESENTATIONAL))
            assertFalse(pre("title-no-pres").hasAttrFlag(RustChapterMarkup.ATTR_HAS_PRESENTATIONAL))
            assertEquals(6, pre("title-no-pres").titleClassLevel)

            assertTrue(pre("title-with-pres").hasAttrFlag(RustChapterMarkup.ATTR_HAS_PRESENTATIONAL))
            assertEquals(6, pre("title-with-pres").titleClassLevel)
            assertTrue(pre("aligned-p").hasAttrFlag(RustChapterMarkup.ATTR_HAS_PRESENTATIONAL))
            assertTrue(pre("colored-font").hasAttrFlag(RustChapterMarkup.ATTR_HAS_PRESENTATIONAL))
            assertTrue(pre("bg-p").hasAttrFlag(RustChapterMarkup.ATTR_HAS_PRESENTATIONAL))
            assertTrue(pre("dir-p").hasAttrFlag(RustChapterMarkup.ATTR_HAS_DIR))
            assertFalse(pre("plain-p").hasAttrFlag(RustChapterMarkup.ATTR_HAS_DIR))
            assertTrue(pre("inline-flex-row").hasAttrFlag(RustChapterMarkup.ATTR_HAS_STYLE))
            assertFalse(pre("plain-row").hasAttrFlag(RustChapterMarkup.ATTR_HAS_STYLE))
        }

        // 2. Verify unmutated parity with a stylesheet that has no display rules (exercises horizontal row fast exit).
        val cssNoDisplay = "p { text-indent: 1em; }"
        val rustBase = checkNotNull(RustChapterMarkup.parseRetained(xhtml.encodeToByteArray())).use { doc ->
            mapWithParityMapper(doc.bodyElement!!, cssNoDisplay)
        }
        val jsoupBase = mapWithParityMapper(
            Jsoup.parse(xhtml, "", Parser.xmlParser()).selectFirst("body")!!,
            cssNoDisplay,
        )
        assertParity(jsoupBase, rustBase)
        assertTrue(rustBase.elements.any { it is ContentElement.Table })

        // 3. Verify post-parse mutations of align, color, text, bgcolor, style, and dir invalidate flags and keep parity.
        fun mutateAttributes(doc: Document) {
            doc.getElementById("body-root")!!.attr("text", "#222222")
            doc.getElementById("plain-p")!!.attr("align", "center")
            doc.getElementById("aligned-p")!!.removeAttr("ALIGN")
            doc.getElementById("empty-pres")!!.attr("bgcolor", "#dddddd")
            doc.getElementById("colored-font")!!.attr("color", "#990000")
            doc.getElementById("plain-row")!!.attr("dir", "rtl")
            doc.getElementById("dir-p")!!.removeAttr("dir")
            doc.getElementById("mutate-flex-row")!!.attr("style", "display: flex")
        }
        val rustMutated = checkNotNull(RustChapterMarkup.parseRetained(xhtml.encodeToByteArray())).use { doc ->
            mutateAttributes(doc)
            assertFalse(doc.treeMutated)
            assertTrue((doc.mutatedAttrs and RustChapterMarkup.MUTATED_PRESENTATIONAL) != 0)
            assertTrue((doc.mutatedAttrs and RustChapterMarkup.MUTATED_STYLE) != 0)
            assertTrue((doc.mutatedAttrs and RustChapterMarkup.MUTATED_OTHER) != 0)
            mapWithParityMapper(doc.bodyElement!!, cssNoDisplay)
        }
        val jsoupMutatedDoc = Jsoup.parse(xhtml, "", Parser.xmlParser())
        mutateAttributes(jsoupMutatedDoc)
        assertParity(
            mapWithParityMapper(jsoupMutatedDoc.selectFirst("body")!!, cssNoDisplay),
            rustMutated,
        )
        assertEquals(2, rustMutated.elements.count { it is ContentElement.Table })
    }

    @Test
    fun `CssResolver cascade winner cache reuses winners and matches Jsoup`() {
        val bodyParagraphs = buildString {
            repeat(40) { i ->
                append("""<p id="p-$i" class="prose">Paragraph $i</p>""")
                append('\n')
            }
            repeat(15) { i ->
                append("""<p id="note-$i" class="prose emphasis">Emphasized $i</p>""")
                append('\n')
            }
            append("""<p id="inline-1" class="prose" style="font-weight: 700; color: #aa2200">Inline override</p>""")
            append('\n')
            append("""<p id="aligned-1" class="prose" align="right">Aligned prose</p>""")
            append('\n')
            append("""<p id="rtl-1" class="prose" dir="rtl">שלום</p>""")
            append('\n')
            append("""<p id="auto-1" class="prose" dir="auto" style="margin-inline-start: 2em">שלום</p>""")
        }
        val xhtml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body>
                <div class="chapter">
                  $bodyParagraphs
                </div>
              </body>
            </html>
        """.trimIndent()
        val cssText = """
            p { text-indent: 1.5em; margin-bottom: 0.4em; }
            .chapter p.prose { font-size: 1.05em; text-align: justify; }
            .chapter p.prose:first-child { text-indent: 0; }
            p.emphasis { font-style: italic; font-weight: bold !important; }
        """.trimIndent()

        val jsoupDoc = Jsoup.parse(xhtml, "", Parser.xmlParser())
        val jsoupResolver = CssResolver(listOf(CssResolver.Sheet(cssText, "")))
        val rustResolver = CssResolver(listOf(CssResolver.Sheet(cssText, "")))
        try {
            checkNotNull(RustChapterMarkup.parseRetained(xhtml.encodeToByteArray())).use { rustDoc ->
                val rustPs = rustDoc.select("p")
                val jsoupPs = jsoupDoc.select("p")
                assertEquals(jsoupPs.size, rustPs.size)
                for (i in rustPs.indices) {
                    val expected = jsoupResolver.computed(jsoupPs[i])
                    val actual = rustResolver.computed(rustPs[i])
                    assertEquals("mismatch at index $i (${rustPs[i].id()})", expected.textIndentEm, actual.textIndentEm)
                    assertEquals("mismatch at index $i", expected.textAlign, actual.textAlign)
                    assertEquals("mismatch at index $i", expected.fontSizeEm, actual.fontSizeEm)
                    assertEquals("mismatch at index $i", expected.italic, actual.italic)
                    assertEquals("mismatch at index $i", expected.bold, actual.bold)
                    assertEquals("mismatch at index $i", expected.fontWeight, actual.fontWeight)
                    assertEquals("mismatch at index $i", expected.foregroundColorArgb, actual.foregroundColorArgb)
                    assertEquals("mismatch at index $i", expected.direction, actual.direction)
                    assertEquals("mismatch at index $i", expected.marginInlineStartEm, actual.marginInlineStartEm)
                }
                // 59 <p> elements share only 3 distinct rule-winner sets (first-child, prose, prose+emphasis).
                assertEquals(3, rustResolver.cascadeWinnerCacheSize)

                val rustMapped = mapWithParityMapper(rustDoc.bodyElement!!, cssText)
                val jsoupMapped = mapWithParityMapper(jsoupDoc.selectFirst("body")!!, cssText)
                assertParity(jsoupMapped, rustMapped)
            }
        } finally {
            jsoupResolver.close()
            rustResolver.close()
            assertEquals(0, rustResolver.cascadeWinnerCacheSize)
        }
    }

    @Test
    fun `MRK2 DOC_HAS_ANY_PRESENTATIONAL fast paths and mutations maintain parity`() {
        val plainXhtml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body id="b">
                <div id="wrap"><p id="p1">First <span>inline</span> paragraph</p><p id="p2">Second</p></div>
              </body>
            </html>
        """.trimIndent()
        val centerXhtml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body>
                <center><p>Centered legacy</p></center>
              </body>
            </html>
        """.trimIndent()

        // 1. Plain doc has DOC_HAS_ANY_PRESENTATIONAL == 0; <center> sets it.
        checkNotNull(RustChapterMarkup.parseRetained(plainXhtml.encodeToByteArray())).use { doc ->
            assertFalse(doc.hasDocFlag(RustChapterMarkup.DOC_HAS_ANY_PRESENTATIONAL))
            val rustMapped = mapWithParityMapper(doc.bodyElement!!, cssText = null)
            val jsoupMapped = mapWithParityMapper(
                Jsoup.parse(plainXhtml, "", Parser.xmlParser()).selectFirst("body")!!,
                cssText = null,
            )
            assertParity(jsoupMapped, rustMapped)
        }

        checkNotNull(RustChapterMarkup.parseRetained(centerXhtml.encodeToByteArray())).use { doc ->
            assertTrue(doc.hasDocFlag(RustChapterMarkup.DOC_HAS_ANY_PRESENTATIONAL))
            val rustMapped = mapWithParityMapper(doc.bodyElement!!, cssText = null)
            val jsoupMapped = mapWithParityMapper(
                Jsoup.parse(centerXhtml, "", Parser.xmlParser()).selectFirst("body")!!,
                cssText = null,
            )
            assertParity(jsoupMapped, rustMapped)
            val para = rustMapped.elements.single() as ContentElement.Paragraph
            assertEquals(BlockAlign.CENTER, para.block?.align)
        }

        // 2. Post-parse presentational attribute mutation on a doc that started with DOC_HAS_ANY_PRESENTATIONAL == 0.
        fun mutatePresentationalAttrs(doc: Document) {
            doc.getElementById("b")!!.attr("text", "#224466")
            doc.getElementById("wrap")!!.attr("align", "right")
            doc.getElementById("p2")!!.attr("bgcolor", "#eeeeee")
            doc.selectFirst("span")!!.attr("color", "#991122")
        }
        val rustAttrMutated = checkNotNull(RustChapterMarkup.parseRetained(plainXhtml.encodeToByteArray())).use { doc ->
            assertFalse(doc.hasDocFlag(RustChapterMarkup.DOC_HAS_ANY_PRESENTATIONAL))
            mutatePresentationalAttrs(doc)
            assertTrue((doc.mutatedAttrs and RustChapterMarkup.MUTATED_PRESENTATIONAL) != 0)
            mapWithParityMapper(doc.bodyElement!!, cssText = null)
        }
        val jsoupAttrDoc = Jsoup.parse(plainXhtml, "", Parser.xmlParser())
        mutatePresentationalAttrs(jsoupAttrDoc)
        assertParity(
            mapWithParityMapper(jsoupAttrDoc.selectFirst("body")!!, cssText = null),
            rustAttrMutated,
        )

        // 3. Post-parse DOM insertion of <center> and <div align="center"> on a doc with DOC_HAS_ANY_PRESENTATIONAL == 0.
        fun insertPresentationalDom(doc: Document) {
            doc.getElementById("b")!!.append("<center><p>Inserted center</p></center><div align=\"right\"><p>Inserted right</p></div>")
        }
        val rustDomDoc = checkNotNull(
            RustChapterMarkup.parse(plainXhtml.encodeToByteArray(), htmlOnly = false),
        ) as RustChapterMarkup.MarkupDocument
        assertFalse(rustDomDoc.hasDocFlag(RustChapterMarkup.DOC_HAS_ANY_PRESENTATIONAL))
        insertPresentationalDom(rustDomDoc)
        assertTrue(rustDomDoc.treeMutated)
        val rustDomMapped = mapWithParityMapper(rustDomDoc.bodyElement!!, cssText = null)
        val jsoupDomDoc = Jsoup.parse(plainXhtml, "", Parser.xmlParser())
        insertPresentationalDom(jsoupDomDoc)
        assertParity(
            mapWithParityMapper(jsoupDomDoc.selectFirst("body")!!, cssText = null),
            rustDomMapped,
        )
    }

    @Test
    fun `Computed standardBoxStyle caches and matches parity across flex replaced inline-block auto-dir and mutations`() {
        val xhtml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body>
                <div class="plain-wrap">
                  <p id="plain-1">Plain one</p>
                  <p id="plain-2">Plain two</p>
                  <p id="boxed-1" class="boxed">Boxed one</p>
                  <p id="boxed-2" class="boxed">Boxed two</p>
                  <p id="centered-1" class="centered-box">Centered box</p>
                  <p id="float-1" class="floated">Floated</p>
                  <p id="rtl-1" class="logical" dir="rtl">שלום</p>
                  <p id="auto-ltr" class="logical" dir="auto">Hello world</p>
                  <p id="auto-rtl" class="logical" dir="auto">שלום עולם</p>
                </div>
                <div id="text-center" class="align-center">
                  <img id="img-center" src="fig.png" class="sized-img"/>
                  <div id="ib-center" class="ib">Inline block</div>
                  <p id="p-in-center" class="boxed">Paragraph stays start-aligned box</p>
                </div>
                <div id="text-end-rtl" class="align-end" dir="rtl">
                  <img id="img-end-rtl" src="fig2.png" class="sized-img"/>
                </div>
                <div id="flex-inline" style="display: flex; flex-direction: column; justify-content: center; align-items: center; min-height: 80vh">
                  <p id="p-in-flex-inline" class="boxed">Flex inline child</p>
                </div>
                <div id="flex-mutate">
                  <p id="p-in-flex-mutate" class="boxed">Will become flex child</p>
                </div>
              </body>
            </html>
        """.trimIndent()
        val cssNoFlex = """
            .boxed { margin-bottom: 0.75em; }
            .centered-box { width: 60%; margin-left: auto; margin-right: auto; }
            .floated { float: left; width: 40%; margin-left: auto; margin-right: auto; }
            .logical { margin-inline-start: 2em; }
            .align-center { text-align: center; }
            .align-end { text-align: end; }
            .sized-img { width: 50%; }
            .ib { display: inline-block; width: 40%; }
        """.trimIndent()
        val cssWithFlex = cssNoFlex + "\n.flex-sheet { display: flex; flex-direction: column; justify-content: center; align-items: center; min-height: 80vh; }\n"

        val jsoupDoc = Jsoup.parse(xhtml, "", Parser.xmlParser())
        val jsoupResolver = CssResolver(listOf(CssResolver.Sheet(cssNoFlex, "")))
        val rustResolver = CssResolver(listOf(CssResolver.Sheet(cssNoFlex, "")))
        try {
            checkNotNull(RustChapterMarkup.parseRetained(xhtml.encodeToByteArray())).use { rustDoc ->
                // Plain paragraphs return null box style and share identical Computed + encodedFontTypography.
                val plain1 = rustDoc.getElementById("plain-1")!!
                val plain2 = rustDoc.getElementById("plain-2")!!
                assertNull(rustResolver.publisherBoxStyle(plain1))
                assertNull(rustResolver.publisherBoxStyle(plain2))
                val cPlain1 = rustResolver.computed(plain1)
                val cPlain2 = rustResolver.computed(plain2)
                assertSame(cPlain1, cPlain2)
                assertEquals(cPlain1.fontTypography.encode(), cPlain1.encodedFontTypography)

                // Shared boxed paragraphs reuse the exact same PublisherBoxStyle instance via standardBoxStyle.
                val boxed1 = rustResolver.publisherBoxStyle(rustDoc.getElementById("boxed-1")!!)
                val boxed2 = rustResolver.publisherBoxStyle(rustDoc.getElementById("boxed-2")!!)
                assertNotNull(boxed1)
                assertSame(boxed1, boxed2)
                assertEquals(PublisherBoxAlign.START, boxed1!!.horizontalAlign)

                // Verify all special cases against Jsoup.
                val ids = listOf(
                    "plain-1", "plain-2", "boxed-1", "boxed-2", "centered-1", "float-1",
                    "rtl-1", "auto-ltr", "auto-rtl", "img-center", "ib-center", "p-in-center",
                    "img-end-rtl", "p-in-flex-inline", "p-in-flex-mutate",
                )
                for (id in ids) {
                    assertEquals(
                        "publisherBoxStyle mismatch for #$id",
                        jsoupResolver.publisherBoxStyle(jsoupDoc.getElementById(id)!!),
                        rustResolver.publisherBoxStyle(rustDoc.getElementById(id)!!),
                    )
                }
                assertEquals(
                    PublisherBoxAlign.CENTER,
                    rustResolver.publisherBoxStyle(rustDoc.getElementById("img-center")!!)!!.horizontalAlign,
                )
                assertEquals(
                    PublisherBoxAlign.CENTER,
                    rustResolver.publisherBoxStyle(rustDoc.getElementById("ib-center")!!)!!.horizontalAlign,
                )
                assertEquals(
                    PublisherBoxAlign.START,
                    rustResolver.publisherBoxStyle(rustDoc.getElementById("img-end-rtl")!!)!!.horizontalAlign,
                )
                assertEquals(
                    PublisherBoxAlign.CENTER,
                    rustResolver.publisherBoxStyle(rustDoc.getElementById("p-in-flex-inline")!!)!!.horizontalAlign,
                )
                assertEquals(
                    PublisherBoxAlign.START,
                    rustResolver.publisherBoxStyle(rustDoc.getElementById("p-in-flex-mutate")!!)!!.horizontalAlign,
                )
                assertEquals(2f, rustResolver.publisherBoxStyle(rustDoc.getElementById("auto-ltr")!!)!!.marginLeftEm)
                assertEquals(2f, rustResolver.publisherBoxStyle(rustDoc.getElementById("auto-rtl")!!)!!.marginRightEm)

                // Post-parse inline style mutation turning #flex-mutate into a flex centering container.
                rustDoc.getElementById("flex-mutate")!!.attr(
                    "style",
                    "display: flex; flex-direction: column; justify-content: center; align-items: center; min-height: 80vh",
                )
                rustResolver.clearCache()
                assertEquals(
                    PublisherBoxAlign.CENTER,
                    rustResolver.publisherBoxStyle(rustDoc.getElementById("p-in-flex-mutate")!!)!!.horizontalAlign,
                )
            }
        } finally {
            jsoupResolver.close()
            rustResolver.close()
        }

        // Verify stylesheet-driven flex centering (`hasFlexCenteringRules == true`) parity via HtmlMapper.
        val sheetFlexXhtml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body>
                <div class="flex-sheet"><p class="boxed">Sheet flex child</p></div>
              </body>
            </html>
        """.trimIndent()
        val rustFlexMapped = checkNotNull(RustChapterMarkup.parseRetained(sheetFlexXhtml.encodeToByteArray())).use { doc ->
            mapWithParityMapper(doc.bodyElement!!, cssWithFlex)
        }
        val jsoupFlexMapped = mapWithParityMapper(
            Jsoup.parse(sheetFlexXhtml, "", Parser.xmlParser()).selectFirst("body")!!,
            cssWithFlex,
        )
        assertParity(jsoupFlexMapped, rustFlexMapped)
    }

    @Test
    fun `blockStyleFor ancestor pageBreakBefore fast path propagation and empty container minHeight maintain parity`() {
        val noStyleXhtml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body>
                <section id="sec">
                  <div id="wrap">
                    <p id="p1">First leaf</p>
                    <p id="p2">Second leaf</p>
                  </div>
                </section>
                <div id="empty-box" class="blank-page"></div>
                <div id="non-empty-box" class="blank-page"><p>Not empty</p></div>
              </body>
            </html>
        """.trimIndent()

        // 1. No page-break rules & DOC_HAS_ANY_STYLE == 0 -> fast path skips ancestor walk.
        val cssNoBreak = ".blank-page { min-height: 5em; }"
        checkNotNull(RustChapterMarkup.parseRetained(noStyleXhtml.encodeToByteArray())).use { doc ->
            assertFalse(doc.hasDocFlag(RustChapterMarkup.DOC_HAS_ANY_STYLE))
            val rustMapped = mapWithParityMapper(doc.bodyElement!!, cssNoBreak)
            val jsoupMapped = mapWithParityMapper(
                Jsoup.parse(noStyleXhtml, "", Parser.xmlParser()).selectFirst("body")!!,
                cssNoBreak,
            )
            assertParity(jsoupMapped, rustMapped)
            assertEquals(1, rustMapped.elements.count { it is ContentElement.Spacer })
            val spacer = rustMapped.elements.first { it is ContentElement.Spacer } as ContentElement.Spacer
            assertEquals(5f, spacer.heightEm)
        }

        // 2. Stylesheet break-before / page-break-before on ancestor propagates to first block child.
        val cssWithBreak = "#sec { break-before: page; } .blank-page { min-height: 5em; }"
        checkNotNull(RustChapterMarkup.parseRetained(noStyleXhtml.encodeToByteArray())).use { doc ->
            val rustMapped = mapWithParityMapper(doc.bodyElement!!, cssWithBreak)
            val jsoupMapped = mapWithParityMapper(
                Jsoup.parse(noStyleXhtml, "", Parser.xmlParser()).selectFirst("body")!!,
                cssWithBreak,
            )
            assertParity(jsoupMapped, rustMapped)
            val firstPara = rustMapped.elements[0] as ContentElement.Paragraph
            assertTrue(firstPara.block?.pageBreakBefore == true)
        }

        // 3. Inline style page-break-before on ancestor with no stylesheet break rules.
        val inlineBreakXhtml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body>
                <div style="page-break-before: always"><p>After inline break</p><p>Second</p></div>
              </body>
            </html>
        """.trimIndent()
        checkNotNull(RustChapterMarkup.parseRetained(inlineBreakXhtml.encodeToByteArray())).use { doc ->
            assertTrue(doc.hasDocFlag(RustChapterMarkup.DOC_HAS_ANY_STYLE))
            val rustMapped = mapWithParityMapper(doc.bodyElement!!, cssNoBreak)
            val jsoupMapped = mapWithParityMapper(
                Jsoup.parse(inlineBreakXhtml, "", Parser.xmlParser()).selectFirst("body")!!,
                cssNoBreak,
            )
            assertParity(jsoupMapped, rustMapped)
            val firstPara = rustMapped.elements[0] as ContentElement.Paragraph
            val secondPara = rustMapped.elements[1] as ContentElement.Paragraph
            assertTrue(firstPara.block?.pageBreakBefore == true)
            assertFalse(secondPara.block?.pageBreakBefore == true)
        }

        // 4. Post-parse style mutation adding page-break-before on an ancestor in a doc with DOC_HAS_ANY_STYLE == 0.
        val rustMutated = checkNotNull(RustChapterMarkup.parseRetained(noStyleXhtml.encodeToByteArray())).use { doc ->
            assertFalse(doc.hasDocFlag(RustChapterMarkup.DOC_HAS_ANY_STYLE))
            doc.getElementById("wrap")!!.attr("style", "page-break-before: always")
            assertTrue((doc.mutatedAttrs and RustChapterMarkup.MUTATED_STYLE) != 0)
            mapWithParityMapper(doc.bodyElement!!, cssNoBreak)
        }
        val jsoupMutatedDoc = Jsoup.parse(noStyleXhtml, "", Parser.xmlParser())
        jsoupMutatedDoc.getElementById("wrap")!!.attr("style", "page-break-before: always")
        assertParity(
            mapWithParityMapper(jsoupMutatedDoc.selectFirst("body")!!, cssNoBreak),
            rustMutated,
        )
        assertTrue((rustMutated.elements[0] as ContentElement.Paragraph).block?.pageBreakBefore == true)
        assertFalse((rustMutated.elements[1] as ContentElement.Paragraph).block?.pageBreakBefore == true)
    }

    @Test
    fun `inlineStyleFor and inlineBidiMode zero-allocation fast paths maintain exact parity`() {
        val xhtml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" lang="en">
              <head>
                <title>Inline Fast Path Parity</title>
                <style><![CDATA[
                  .base-sup { vertical-align: super; }
                  .base-sub { vertical-align: sub; }
                  .base-bg { background-color: #ffeeaa; }
                  .base-clear-bg { background-color: rgba(255, 0, 0, 0); }
                  .ul { text-decoration: underline; }
                  .st { text-decoration: line-through; }
                  .both { text-decoration: underline line-through; }
                  .fancy {
                    font-feature-settings: "smcp" 1;
                    font-style: italic;
                    font-weight: 700;
                    font-size: 1.5em;
                    color: #224466;
                    background-color: #ddeeff;
                  }
                  .un-italic { font-style: normal; }
                  .reset-bidi { unicode-bidi: normal; }
                  .embed-rtl { unicode-bidi: embed; direction: rtl; }
                ]]></style>
              </head>
              <body>
                <p id="p-same-plain"><span><span>Plain inherited span</span></span></p>
                <p id="p-same-lang"><span><span lang="he">שלום</span></span></p>
                <p id="p-same-sup"><span class="base-sup">Super</span></p>
                <p id="p-same-sub"><span class="base-sub">Sub</span></p>
                <p id="p-same-mono"><code>code</code> <kbd>kbd</kbd> <samp>samp</samp> <tt>tt</tt></p>
                <p id="p-same-bg"><span class="base-bg">BG</span></p>
                <p id="p-same-clear"><span class="base-clear-bg">Clear BG</span></p>
                <p id="p-same-sup-bg-lang"><code class="base-sup base-bg" lang="fr">combo</code></p>
                <p id="p-decor">
                  <span class="ul">under</span>
                  <span class="st">strike</span>
                  <span class="both">both</span>
                  <span class="ul"><span class="both">add-strike</span></span>
                </p>
                <p id="p-overrides">
                  <span class="fancy" lang="de">Fancy <span class="un-italic">normal</span></span>
                  <sup>sup-default</sup>
                  <sub>sub-default</sub>
                  <mark>marked</mark>
                </p>
                <p id="p-bidi">
                  <span>plain</span>
                  <bdi>isolated</bdi>
                  <bdo dir="rtl">override</bdo>
                  <span dir="rtl">dir-span</span>
                  <bdi class="reset-bidi">reset-bdi</bdi>
                  <span class="embed-rtl">embed</span>
                </p>
              </body>
            </html>
        """.trimIndent()

        val cssText = """
            .base-sup { vertical-align: super; }
            .base-sub { vertical-align: sub; }
            .base-bg { background-color: #ffeeaa; }
            .base-clear-bg { background-color: rgba(255, 0, 0, 0); }
            .ul { text-decoration: underline; }
            .st { text-decoration: line-through; }
            .both { text-decoration: underline line-through; }
            .fancy {
              font-feature-settings: "smcp" 1;
              font-style: italic;
              font-weight: 700;
              font-size: 1.5em;
              color: #224466;
              background-color: #ddeeff;
            }
            .un-italic { font-style: normal; }
            .reset-bidi { unicode-bidi: normal; }
            .embed-rtl { unicode-bidi: embed; direction: rtl; }
        """.trimIndent()

        // 1. Full parity with CSS (Rust RetainedDocument vs Jsoup)
        val rustCss = checkNotNull(RustChapterMarkup.parseRetained(xhtml.encodeToByteArray())).use { doc ->
            mapWithParityMapper(doc.bodyElement!!, cssText)
        }
        val jsoupCss = mapWithParityMapper(
            Jsoup.parse(xhtml, "", Parser.xmlParser()).selectFirst("body")!!,
            cssText,
        )
        assertParity(jsoupCss, rustCss)

        // 2. Full parity without CSS (exercises tagStyleFor + legacyInlineStyle + inlineBidiMode without CSS)
        val rustPlain = checkNotNull(RustChapterMarkup.parseRetained(xhtml.encodeToByteArray())).use { doc ->
            mapWithParityMapper(doc.bodyElement!!, cssText = null)
        }
        val jsoupPlain = mapWithParityMapper(
            Jsoup.parse(xhtml, "", Parser.xmlParser()).selectFirst("body")!!,
            cssText = null,
        )
        assertParity(jsoupPlain, rustPlain)

        // 3. Verify key SpanStyles and Bidi annotations directly
        fun para(id: String): ContentElement.Paragraph {
            val idx = rustCss.anchors.getValue(id)
            return rustCss.elements[idx] as ContentElement.Paragraph
        }

        // Plain inherited spans emit zero SpanStyles
        assertTrue(para("p-same-plain").text.spanStyles.isEmpty())

        // Lang override on own === base emits only localeList
        val langStyles = para("p-same-lang").text.spanStyles
        assertEquals(1, langStyles.size)
        assertEquals("he", langStyles.single().item.localeList?.get(0)?.toLanguageTag())

        // Semantic monospace tags on own === base emit FontFamily.Monospace
        val monoStyles = para("p-same-mono").text.spanStyles
        assertEquals(4, monoStyles.size)
        assertTrue(monoStyles.all { it.item.fontFamily == FontFamily.Monospace })

        // Super + BG + Monospace + Lang combined on own === base
        val comboStyle = para("p-same-sup-bg-lang").text.spanStyles.single().item
        assertEquals(BaselineShift.Superscript, comboStyle.baselineShift)
        assertEquals(TextUnit(0.75f, TextUnitType.Em), comboStyle.fontSize)
        assertEquals(FontFamily.Monospace, comboStyle.fontFamily)
        assertEquals(Color(0xFFFFEEAA), comboStyle.background)
        assertEquals("fr", comboStyle.localeList?.get(0)?.toLanguageTag())

        // Transparent background emits zero SpanStyles
        assertTrue(para("p-same-clear").text.spanStyles.isEmpty())

        // Underline, LineThrough, and combined Underline+LineThrough
        val decorPara = para("p-decor").text
        val combinedDeco = TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
        assertTrue(decorPara.spanStyles.any { it.item.textDecoration == TextDecoration.Underline })
        assertTrue(decorPara.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
        assertTrue(decorPara.spanStyles.any { it.item.textDecoration == combinedDeco })

        // Fancy overrides + nested normal font-style override
        val overrides = para("p-overrides").text.spanStyles.map { it.item }
        assertTrue(overrides.any {
            it.fontFeatureSettings == "'smcp' 1" &&
                it.fontStyle == FontStyle.Italic &&
                it.fontWeight == FontWeight(700) &&
                it.fontSize == TextUnit(1.5f, TextUnitType.Em) &&
                it.color == Color(0xFF224466) &&
                it.background == Color(0xFFDDEEFF) &&
                it.localeList?.get(0)?.toLanguageTag() == "de"
        })
        assertTrue(overrides.any { it.fontStyle == FontStyle.Normal })

        // Bidi annotations: bdi, bdo, dir="rtl", embed-rtl present; plain and reset-bidi absent
        val bidiAnnotations = para("p-bidi").text.getStringAnnotations(BIDI_TAG, 0, para("p-bidi").text.length)
        assertEquals(
            listOf(
                InlineBidiMode.ISOLATE_AUTO.name,
                InlineBidiMode.OVERRIDE_RTL.name,
                InlineBidiMode.ISOLATE_RTL.name,
                InlineBidiMode.EMBED_RTL.name,
            ),
            bidiAnnotations.map { it.item },
        )
    }

    private data class MappedSnapshot(
        val elements: List<ContentElement>,
        val anchors: Map<String, Int>,
        val linkTargets: Set<String>,
        val noteTargets: Set<String>,
        val noteDocuments: Map<String, NoteDocument>,
        val publisherBoxes: List<Any>,
    )

    private fun mapWithParityMapper(
        root: org.jsoup.nodes.Element,
        cssText: String? = null,
    ): MappedSnapshot {
        val css = cssText?.let { CssResolver(listOf(CssResolver.Sheet(it, ""))) }
        val mapper = HtmlMapper(
            resolveImage = { src -> "/cache/${src.trim()}" },
            resolveLink = { href -> href.takeIf { '#' in it } },
            css = css,
            resolveInlineSvg = { markup -> "/cache/svg_${markup.length}.svg" },
        )
        val mapped = mapper.map(root)
        return MappedSnapshot(
            elements = mapped,
            anchors = mapper.anchors.toMap(),
            linkTargets = mapper.linkTargets.toSet(),
            noteTargets = mapper.noteTargets.toSet(),
            noteDocuments = mapper.noteDocuments.toMap(),
            publisherBoxes = mapper.publisherBoxes.toList(),
        )
    }

    private fun assertParity(expected: MappedSnapshot, actual: MappedSnapshot) {
        if (expected.elements.size != actual.elements.size) {
            fun describe(elements: List<ContentElement>): List<String> = elements.map { element ->
                when (element) {
                    is ContentElement.Paragraph -> "P(${element.text.text.replace("\n", "\\n")})"
                    is ContentElement.Heading -> "H${element.level}(${element.text})"
                    is ContentElement.Table -> "T(${element.flatText().replace("\n", "\\n")})"
                    else -> element.toString()
                }
            }
            assertEquals(
                "element count mismatch:\nexpected=${describe(expected.elements)}\nactual=${describe(actual.elements)}",
                expected.elements.size,
                actual.elements.size,
            )
        }
        assertEquals(expected.elements.size, actual.elements.size)
        for (i in expected.elements.indices) {
            assertElementEquals(expected.elements[i], actual.elements[i])
        }
        assertEquals(expected.anchors, actual.anchors)
        assertEquals(expected.linkTargets, actual.linkTargets)
        assertEquals(expected.noteTargets, actual.noteTargets)
        assertEquals(expected.noteDocuments.keys, actual.noteDocuments.keys)
        for ((key, expNote) in expected.noteDocuments) {
            val actNote = actual.noteDocuments.getValue(key)
            assertEquals(expNote.elements.size, actNote.elements.size)
            for (i in expNote.elements.indices) {
                assertElementEquals(expNote.elements[i], actNote.elements[i])
            }
            assertEquals(expNote.publisherBoxes, actNote.publisherBoxes)
        }
        assertEquals(expected.publisherBoxes, actual.publisherBoxes)
    }

    private fun assertElementEquals(expected: ContentElement, actual: ContentElement) {
        if (expected is ContentElement.Table && actual is ContentElement.Table) {
            assertEquals(expected.block, actual.block)
            assertEquals(expected.publisherRowLayout, actual.publisherRowLayout)
            assertEquals(expected.centerCellsVertically, actual.centerCellsVertically)
            assertEquals(expected.stackCellsBelowCssPx, actual.stackCellsBelowCssPx)
            assertEquals(expected.publisherBox, actual.publisherBox)
            assertTableRowsEquals(expected.rows, actual.rows)
        } else {
            assertEquals(expected, actual)
        }
    }

    private fun assertTableRowsEquals(expected: List<TableRow>, actual: List<TableRow>) {
        assertEquals(expected.size, actual.size)
        for (r in expected.indices) {
            val expRow = expected[r]
            val actRow = actual[r]
            assertEquals(expRow.isHeader, actRow.isHeader)
            assertEquals(expRow.cells.size, actRow.cells.size)
            for (c in expRow.cells.indices) {
                assertTableCellEquals(expRow.cells[c], actRow.cells[c])
            }
        }
    }

    private fun assertTableCellEquals(expected: TableCell, actual: TableCell) {
        assertEquals(expected.text, actual.text)
        assertEquals(expected.colSpan, actual.colSpan)
        assertEquals(expected.rowSpan, actual.rowSpan)
        assertEquals(expected.align, actual.align)
        assertEquals(expected.header, actual.header)
        assertEquals(expected.block, actual.block)
        assertEquals(expected.publisherBox, actual.publisherBox)
        assertEquals(expected.publisherPaddingSpecified, actual.publisherPaddingSpecified)
        assertEquals(expected.publisherBorderSpecified, actual.publisherBorderSpecified)
        assertEquals(expected.narrowStyle, actual.narrowStyle)
    }
}
