package com.example.frogreader.parser

import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.parser.HtmlMapper
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * Temporary diagnostic: compares raw text in each spine file of the user's
 * real EPUBs against what HtmlMapper extracts, to find where text is lost.
 */
class UserBookDiagnosticTest {

    private val scratch =
        "/private/tmp/claude-501/-Users-frog-AndroidStudioProjects-FrogReader/" +
            "cb39f77b-3af0-4258-9949-697358e86436/scratchpad"

    @Test
    fun `find text loss per spine file`() {
        for (name in listOf("user_book1.epub", "user_book2.epub")) {
            val file = File(scratch, name)
            assumeTrue(file.exists())
            println("=== $name ===")

            ZipFile(file).use { zip ->
                val container = zip.getInputStream(zip.getEntry("META-INF/container.xml"))
                    .use { Jsoup.parse(it, "UTF-8", "", Parser.xmlParser()) }
                val opfPath = container.selectFirst("rootfile")!!.attr("full-path")
                val opfDir = opfPath.substringBeforeLast('/', "")
                val opf = zip.getInputStream(zip.getEntry(opfPath))
                    .use { Jsoup.parse(it, "UTF-8", "", Parser.xmlParser()) }

                val hrefById = opf.select("manifest > item")
                    .associate { it.attr("id") to it.attr("href") }

                val itemrefs = opf.select("spine > itemref")
                val linearNo = itemrefs.count { it.attr("linear").equals("no", true) }
                val mediaTypes = opf.select("manifest > item")
                    .groupingBy { it.attr("media-type") }.eachCount()
                println(
                    "spine=${itemrefs.size} linearNo=$linearNo " +
                        "mediaTypes=$mediaTypes",
                )
                println("first itemrefs: " + itemrefs.take(8).joinToString(" ") {
                    "${it.attr("idref")}(linear=${it.attr("linear")})"
                })

                var sumRaw = 0L
                var sumExtracted = 0L
                var files = 0
                for (itemref in opf.select("spine > itemref")) {
                    val href = hrefById[itemref.attr("idref")] ?: continue
                    val path = if (opfDir.isEmpty()) href else "$opfDir/$href"
                    val entry = zip.getEntry(path) ?: continue
                    val doc = zip.getInputStream(entry).use { Jsoup.parse(it, null, "") }
                    val mapper = HtmlMapper(resolveImage = { null })
                    sumRaw += doc.body().text().length
                    sumExtracted += mapper.map(doc.body()).sumOf { el ->
                        when (el) {
                            is ContentElement.Paragraph -> el.text.text.length
                            is ContentElement.Heading -> el.text.length
                            else -> 0
                        }
                    }
                    files++
                }
                println("ALL FILES: n=$files sumRaw=$sumRaw sumExtracted=$sumExtracted")

                // Compare against the real parser output.
                val content = com.example.frogreader.data.parser.EpubParser.parseContent(
                    file,
                    java.io.File.createTempFile("imgs", "").let {
                        it.delete(); it.apply { mkdirs() }
                    },
                )
                println("NOTES: ${content.notes.size}")
                content.notes.entries
                    .filter { it.value.text.length > 60 }
                    .take(3)
                    .forEach { println("  NOTE ${it.key} -> ${it.value.text.take(110)}") }
                val parsed = content.chapters
                val parsedChars = parsed.sumOf { ch ->
                    ch.elements.sumOf { el ->
                        when (el) {
                            is ContentElement.Paragraph -> el.text.text.length
                            is ContentElement.Heading -> el.text.length
                            else -> 0
                        }
                    }
                }
                println("PARSER: chapters=${parsed.size} chars=$parsedChars")

                var shown = 0
                for (itemref in opf.select("spine > itemref")) {
                    val href = hrefById[itemref.attr("idref")] ?: continue
                    val path = if (opfDir.isEmpty()) href else "$opfDir/$href"
                    val entry = zip.getEntry(path) ?: continue
                    if (!href.contains("htm", ignoreCase = true) &&
                        !href.contains("xhtml", ignoreCase = true)
                    ) {
                        continue
                    }

                    val doc = zip.getInputStream(entry).use { Jsoup.parse(it, null, "") }
                    val rawChars = doc.body().text().length
                    val mapper = HtmlMapper(resolveImage = { null })
                    val elements = mapper.map(doc.body())
                    val extracted = elements.sumOf { el ->
                        when (el) {
                            is ContentElement.Paragraph -> el.text.text.length
                            is ContentElement.Heading -> el.text.length
                            else -> 0
                        }
                    }
                    val lossy = rawChars > 0 && extracted < rawChars * 0.8
                    if (lossy || shown < 6) {
                        println(
                            "  $href raw=$rawChars extracted=$extracted" +
                                if (lossy) "   <-- LOSS" else "",
                        )
                        if (lossy && shown < 8) {
                            // Show the structure of the first lossy file.
                            val bodyChildren = doc.body().children()
                                .joinToString(" ") { "<${it.tagName()}>" }
                            println("    body children: ${bodyChildren.take(300)}")
                            doc.body().children().take(4).forEach { child ->
                                println(
                                    "    <${child.tagName()} class='${child.className()}'> " +
                                        "textLen=${child.text().length} " +
                                        "children=${child.children().take(6)
                                            .joinToString(" ") { "<${it.tagName()}>" }}",
                                )
                            }
                        }
                        shown++
                    }
                    if (shown > 14) break
                }
            }
        }
    }
}
