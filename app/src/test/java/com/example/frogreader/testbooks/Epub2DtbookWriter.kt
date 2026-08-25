package com.example.frogreader.testbooks

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A small, valid EPUB 2 compatibility book whose spine document is DTBook,
 * not XHTML. It is intentionally separate from the four-way FrogCompare
 * corpus: putting DTBook in an EPUB 3 package would manufacture invalid input
 * and make a parser success impossible to classify.
 */
object Epub2DtbookWriter {

    fun write(target: File) {
        ZipOutputStream(target.outputStream()).use { zip ->
            zip.stored("mimetype", "application/epub+zip".toByteArray(Charsets.US_ASCII))
            zip.text("META-INF/container.xml", CONTAINER)
            zip.text("OEBPS/content.opf", OPF)
            zip.text("OEBPS/toc.ncx", NCX)
            zip.text("OEBPS/book.css", CSS)
            zip.text("OEBPS/book.xml", DTBOOK)
            zip.binary("OEBPS/images/ornament.png", TestAssets.images.getValue("ornament").bytes)
            zip.text("OEBPS/images/vector.svg", VECTOR_SVG)
        }
    }

    private fun ZipOutputStream.text(name: String, content: String) =
        binary(name, content.toByteArray(Charsets.UTF_8))

    private fun ZipOutputStream.binary(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name).apply { time = 0L })
        write(bytes)
        closeEntry()
    }

    private fun ZipOutputStream.stored(name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply {
            time = 0L
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }
        putNextEntry(entry)
        write(bytes)
        closeEntry()
    }

    private val CONTAINER = """
        <?xml version="1.0" encoding="utf-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent()

    private val OPF = """
        <?xml version="1.0" encoding="utf-8"?>
        <package xmlns="http://www.idpf.org/2007/opf"
                 xmlns:dc="http://purl.org/dc/elements/1.1/"
                 version="2.0" unique-identifier="uid">
          <metadata>
            <dc:title>FrogCompare — EPUB2 DTBook</dc:title>
            <dc:creator>Первый Автор</dc:creator>
            <dc:identifier id="uid">urn:uuid:frogcompare-dtbook-epub2</dc:identifier>
            <dc:language>ru</dc:language>
          </metadata>
          <manifest>
            <item id="dtbook" href="book.xml" media-type="application/x-dtbook+xml"/>
            <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
            <item id="css" href="book.css" media-type="text/css"/>
            <item id="ornament" href="images/ornament.png" media-type="image/png"/>
            <item id="vector" href="images/vector.svg" media-type="image/svg+xml"/>
          </manifest>
          <spine toc="ncx"><itemref idref="dtbook"/></spine>
        </package>
    """.trimIndent()

    private val NCX = """
        <?xml version="1.0" encoding="utf-8"?>
        <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
          <head><meta name="dtb:uid" content="urn:uuid:frogcompare-dtbook-epub2"/></head>
          <docTitle><text>FrogCompare — EPUB2 DTBook</text></docTitle>
          <navMap>
            <navPoint id="n1" playOrder="1">
              <navLabel><text>DTB-01. Структура DTBook</text></navLabel>
              <content src="book.xml#dtb-01"/>
              <navPoint id="n2" playOrder="2">
                <navLabel><text>DTB-02. Вложенный уровень</text></navLabel>
                <content src="book.xml#dtb-02"/>
                <navPoint id="n3" playOrder="3">
                  <navLabel><text>DTB-03. Уровень 3</text></navLabel>
                  <content src="book.xml#dtb-03"/>
                  <navPoint id="n4" playOrder="4">
                    <navLabel><text>DTB-04. Уровень 4</text></navLabel>
                    <content src="book.xml#dtb-04"/>
                    <navPoint id="n5" playOrder="5">
                      <navLabel><text>DTB-05. Уровень 5</text></navLabel>
                      <content src="book.xml#dtb-05"/>
                      <navPoint id="n6" playOrder="6">
                        <navLabel><text>DTB-06. Уровень 6</text></navLabel>
                        <content src="book.xml#dtb-06"/>
                      </navPoint>
                    </navPoint>
                  </navPoint>
                </navPoint>
              </navPoint>
            </navPoint>
            <navPoint id="n7" playOrder="7">
              <navLabel><text>DTB-07. Rearmatter</text></navLabel>
              <content src="book.xml#dtb-rear"/>
            </navPoint>
          </navMap>
        </ncx>
    """.trimIndent()

    /**
     * DTBook-specific vocabulary is deliberate: level1…level6, levelhd,
     * bridgehead, nested list, poem/line, imggroup, prodnote and rearmatter
     * must not depend on XHTML aliases to reach the common model.
     */
    private val DTBOOK = """
        <?xml version="1.0" encoding="utf-8"?>
        <dtbook xmlns="http://www.daisy.org/z3986/2005/dtbook/" version="2005-3" xml:lang="ru">
          <head>
            <meta name="dtb:uid" content="urn:uuid:frogcompare-dtbook-epub2"/>
            <link rel="stylesheet" type="text/css" href="book.css"/>
          </head>
          <book>
            <frontmatter>
              <doctitle>FrogCompare — EPUB2 DTBook</doctitle>
              <docauthor>Первый Автор</docauthor>
            </frontmatter>
            <bodymatter>
              <level1 id="dtb-01">
                <levelhd>DTB-01. Структура DTBook</levelhd>
                <p class="lead">Обычный CSS-абзац с <strong>жирным</strong>, <em>курсивом</em>,
                   H<sub>2</sub>O и x<sup>2</sup>.</p>
                <bridgehead>DTB-01A. Промежуточный заголовок</bridgehead>
                <blockquote><p>Цитата DTBook остаётся отдельным блоком.</p></blockquote>
                <list type="ol" enum="1" start="3">
                  <li><p>Третий пункт.</p>
                    <list type="ol" enum="a" start="2">
                      <li><p>Вложенный пункт b.</p></li>
                    </list>
                  </li>
                  <li><p>Четвёртый пункт.</p></li>
                </list>
                <poem><linegroup>
                  <line>Первая строка стихотворного блока,</line>
                  <line>вторая строка не склеивается с первой.</line>
                </linegroup></poem>
                <table>
                  <tr><th>DTBook A</th><th>DTBook B</th></tr>
                  <tr><td>A1</td><td>B1</td></tr>
                </table>
                <imggroup>
                  <img src="images/ornament.png" alt="Оранжевый inline-орнамент"/>
                  <caption><p>Подпись DTBook-картинки.</p></caption>
                </imggroup>
                <imggroup>
                  <img src="images/vector.svg" alt="DTBook SVG-диаграмма"/>
                  <caption><p>Подпись DTBook SVG.</p></caption>
                </imggroup>
                <prodnote id="dtb-prodnote"><p>DTB-01B. Производственное примечание.</p></prodnote>
                <level2 id="dtb-02">
                  <levelhd>DTB-02. Вложенный уровень</levelhd>
                  <p><a href="#dtb-target">DTBook anchor ведёт на уровень 6.</a></p>
                  <level3 id="dtb-03">
                    <levelhd>DTB-03. Уровень 3</levelhd>
                    <p>Третий уровень сохраняет глубину.</p>
                    <level4 id="dtb-04">
                      <levelhd>DTB-04. Уровень 4</levelhd>
                      <p>Четвёртый уровень сохраняет глубину.</p>
                      <level5 id="dtb-05">
                        <levelhd>DTB-05. Уровень 5</levelhd>
                        <p>Пятый уровень сохраняет глубину.</p>
                        <level6 id="dtb-06">
                          <levelhd>DTB-06. Уровень 6</levelhd>
                          <p id="dtb-target">Шестой уровень — точный anchor target.</p>
                        </level6>
                      </level5>
                    </level4>
                  </level3>
                </level2>
              </level1>
            </bodymatter>
            <rearmatter>
              <level1 id="dtb-rear">
                <levelhd>DTB-07. Rearmatter</levelhd>
                <p>Задняя часть остаётся после основного текста.</p>
              </level1>
            </rearmatter>
          </book>
        </dtbook>
    """.trimIndent()

    private val CSS = """
        .lead { text-align: center; font-size: 115%; }
        bridgehead { font-weight: bold; }
    """.trimIndent()

    private val VECTOR_SVG = """
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 60">
          <rect width="120" height="60" fill="#0c7c78"/>
          <circle cx="30" cy="30" r="18" fill="#ffe08a"/>
          <path d="M55 45 L105 15" stroke="#ffffff" stroke-width="5"/>
        </svg>
    """.trimIndent()
}
