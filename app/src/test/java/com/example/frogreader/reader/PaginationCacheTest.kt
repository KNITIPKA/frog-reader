package com.example.frogreader.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.example.frogreader.data.model.BlockStyle
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.PublisherBoxStyle
import com.example.frogreader.data.model.PublisherClear
import com.example.frogreader.data.model.PublisherFloatSide
import com.example.frogreader.data.model.TableCell
import com.example.frogreader.data.model.TableRow
import com.example.frogreader.ui.reader.BookPage
import com.example.frogreader.ui.reader.PagePart
import com.example.frogreader.ui.reader.PaginationCache
import com.example.frogreader.ui.reader.ReaderPublisherBox
import com.example.frogreader.ui.reader.ReaderPublisherFloat
import com.example.frogreader.ui.reader.ReaderPublisherFloatContent
import com.example.frogreader.ui.reader.ReaderItem
import com.example.frogreader.ui.reader.SideBoxSpec
import com.example.frogreader.ui.reader.TableGrid
import com.example.frogreader.ui.reader.TableLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PaginationCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `same-length text spans and table styles invalidate cached geometry`() {
        val original = table("AAAA", tableScale = 1f, cellScale = null)
        val originalItems = listOf(ReaderItem(0, original))
        val cache = tempFolder.newFile("pagination.json")
        PaginationCache.save(cache, "layout", originalItems, pagesFor(original))
        assertNotNull(PaginationCache.load(cache, "layout", originalItems))

        val changedText = table("BBBB", tableScale = 1f, cellScale = null)
        assertNull(
            PaginationCache.load(cache, "layout", listOf(ReaderItem(0, changedText))),
        )

        val changedTableStyle = table("AAAA", tableScale = 1.4f, cellScale = null)
        assertNull(
            PaginationCache.load(cache, "layout", listOf(ReaderItem(0, changedTableStyle))),
        )

        val changedCellStyle = table("AAAA", tableScale = 1f, cellScale = 0.8f)
        assertNull(
            PaginationCache.load(cache, "layout", listOf(ReaderItem(0, changedCellStyle))),
        )

        val bold = AnnotatedString.Builder("AAAA").apply {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), 0, 4)
        }.toAnnotatedString()
        val changedSpan = table(bold, tableScale = 1f, cellScale = null)
        assertNull(
            PaginationCache.load(cache, "layout", listOf(ReaderItem(0, changedSpan))),
        )
    }

    @Test
    fun `every publisher layout input participates in content signature`() {
        val paragraph = ContentElement.Paragraph(AnnotatedString("Body"))
        val baseBox = publisherBox(
            style = PublisherBoxStyle(
                paddingLeftEm = 0.5f,
                backgroundColorArgb = 0xffddeeff.toInt(),
            ),
        )
        val baseFloat = publisherFloat(PublisherFloatSide.LEFT)
        val base = ReaderItem(
            chapterIndex = 0,
            element = paragraph,
            publisherBoxes = listOf(baseBox),
            publisherFloat = baseFloat,
            suppressedByPublisherFloat = false,
            publisherClearBefore = PublisherClear.NONE,
        )
        val baseSignature = PaginationCache.contentSignature(listOf(base))

        assertNotEquals(
            baseSignature,
            PaginationCache.contentSignature(
                listOf(
                    ReaderItem(
                        0,
                        paragraph,
                        publisherBoxes = listOf(
                            baseBox.copy(
                                style = baseBox.style.copy(paddingLeftEm = 0.75f),
                            ),
                        ),
                        publisherFloat = baseFloat,
                    ),
                ),
            ),
        )
        assertNotEquals(
            baseSignature,
            PaginationCache.contentSignature(
                listOf(
                    ReaderItem(
                        0,
                        paragraph,
                        publisherBoxes = listOf(baseBox),
                        publisherFloat = baseFloat,
                        suppressedByPublisherFloat = true,
                    ),
                ),
            ),
        )
        assertNotEquals(
            baseSignature,
            PaginationCache.contentSignature(
                listOf(
                    ReaderItem(
                        0,
                        paragraph,
                        publisherBoxes = listOf(baseBox),
                        publisherFloat = baseFloat,
                        publisherClearBefore = PublisherClear.BOTH,
                    ),
                ),
            ),
        )
        assertNotEquals(
            baseSignature,
            PaginationCache.contentSignature(
                listOf(
                    ReaderItem(
                        0,
                        paragraph,
                        publisherBoxes = listOf(baseBox),
                        publisherFloat = publisherFloat(PublisherFloatSide.RIGHT),
                    ),
                ),
            ),
        )

        val tableWithoutPublisherCellStyle = table(
            "AAAA",
            tableScale = 1f,
            cellScale = null,
        )
        val tableWithPublisherCellStyle = table(
            "AAAA",
            tableScale = 1f,
            cellScale = null,
            cellPublisherBox = PublisherBoxStyle(
                paddingRightEm = 0.4f,
                backgroundColorArgb = 0xffccddee.toInt(),
            ),
        )
        assertNotEquals(
            PaginationCache.contentSignature(
                listOf(ReaderItem(0, tableWithoutPublisherCellStyle)),
            ),
            PaginationCache.contentSignature(
                listOf(ReaderItem(0, tableWithPublisherCellStyle)),
            ),
        )
    }

    @Test
    fun `authored margin presence participates in content signature`() {
        fun item(beforeSpecified: Boolean, afterSpecified: Boolean) = ReaderItem(
            chapterIndex = 0,
            element = ContentElement.Paragraph(
                AnnotatedString("Same text"),
                block = BlockStyle(
                    spaceBeforeSpecified = beforeSpecified,
                    spaceAfterSpecified = afterSpecified,
                ),
            ),
        )

        val base = PaginationCache.contentSignature(listOf(item(false, false)))
        assertNotEquals(
            base,
            PaginationCache.contentSignature(listOf(item(true, false))),
        )
        assertNotEquals(
            base,
            PaginationCache.contentSignature(listOf(item(false, true))),
        )

        fun image(beforeSpecified: Boolean, afterSpecified: Boolean) = ReaderItem(
            chapterIndex = 0,
            element = ContentElement.Image(
                path = "/nonexistent/same-image.png",
                spaceBeforeSpecified = beforeSpecified,
                spaceAfterSpecified = afterSpecified,
            ),
        )
        val imageBase = PaginationCache.contentSignature(listOf(image(false, false)))
        assertNotEquals(
            imageBase,
            PaginationCache.contentSignature(listOf(image(true, false))),
        )
        assertNotEquals(
            imageBase,
            PaginationCache.contentSignature(listOf(image(false, true))),
        )
    }

    @Test
    fun `publisher boxes float cell style and fragment edges survive cache round trip`() {
        val float = publisherFloat(PublisherFloatSide.LEFT)
        val targetBoxes = listOf(
            publisherBox(
                id = "panel",
                style = PublisherBoxStyle(
                    paddingTopEm = 0.25f,
                    paddingBottomEm = 0.5f,
                    backgroundColorArgb = 0xffe4f2df.toInt(),
                ),
            ),
        )
        val body = ContentElement.Paragraph(AnnotatedString("Body paragraph"))
        val cellPublisherBox = PublisherBoxStyle(
            paddingTopEm = 0.2f,
            paddingRightEm = 0.3f,
            paddingBottomEm = 0.2f,
            paddingLeftEm = 0.3f,
            backgroundColorArgb = 0xffffddaa.toInt(),
            widthFrac = 0.6f,
        )
        val table = table(
            "Cell",
            tableScale = 1f,
            cellScale = null,
            cellPublisherBox = cellPublisherBox,
        )
        val tableBoxes = listOf(
            publisherBox(
                id = "table-frame",
                style = PublisherBoxStyle(marginTopEm = 0.5f),
            ),
        )
        val items = listOf(
            ReaderItem(
                0,
                float.contents[0].element,
                suppressedByPublisherFloat = true,
            ),
            ReaderItem(
                0,
                ContentElement.Paragraph(AnnotatedString("Caption")),
                suppressedByPublisherFloat = true,
            ),
            ReaderItem(
                chapterIndex = 0,
                element = body,
                publisherBoxes = targetBoxes,
                publisherFloat = float,
                publisherClearBefore = PublisherClear.RIGHT,
            ),
            ReaderItem(
                chapterIndex = 0,
                element = table,
                publisherBoxes = tableBoxes,
            ),
        )
        val sideBox = SideBoxSpec(
            capText = null,
            imagePath = null,
            leftSide = true,
            boxWidthPx = 84,
            boxHeightPx = 128,
            besideWidthPx = 196,
            besideEndChar = 9,
            compositeHeightPx = 140,
            publisherFloat = float,
            publisherFloatItemHeightsPx = listOf(91, 37),
        )
        val tableGrid = TableGrid.build(table.rows)
        val page = BookPage(
            parts = listOf(
                PagePart(
                    itemIndex = 2,
                    element = body,
                    text = body.text,
                    charStart = 0,
                    charEnd = body.text.length,
                    sideBox = sideBox,
                    publisherBoxes = targetBoxes,
                    publisherStartsElement = false,
                    publisherEndsElement = false,
                ),
                PagePart(
                    itemIndex = 3,
                    element = table,
                    rowStart = 0,
                    rowEnd = 1,
                    tableLayout = TableLayout(
                        grid = tableGrid,
                        colWidthsPx = intArrayOf(180),
                        rowHeightsPx = intArrayOf(42),
                        fontScale = 0.9f,
                    ),
                    publisherBoxes = tableBoxes,
                    publisherStartsElement = true,
                    publisherEndsElement = false,
                    allowsVerticalScroll = true,
                ),
            ),
            firstItemIndex = 2,
            firstCharOffset = 0,
        )
        val cache = tempFolder.newFile("publisher-round-trip.json")

        PaginationCache.save(cache, "publisher-layout", items, listOf(page))
        val loadedPages = PaginationCache.load(cache, "publisher-layout", items)

        assertNotNull(loadedPages)
        val loaded = loadedPages!!.single()
        assertEquals(2, loaded.firstItemIndex)
        assertEquals(2, loaded.parts.size)

        val loadedBody = loaded.parts[0]
        assertEquals(targetBoxes, loadedBody.publisherBoxes)
        assertFalse(loadedBody.publisherStartsElement)
        assertFalse(loadedBody.publisherEndsElement)
        assertEquals(float, loadedBody.sideBox?.publisherFloat)
        assertEquals(listOf(91, 37), loadedBody.sideBox?.publisherFloatItemHeightsPx)

        val loadedTable = loaded.parts[1]
        assertEquals(tableBoxes, loadedTable.publisherBoxes)
        assertTrue(loadedTable.publisherStartsElement)
        assertFalse(loadedTable.publisherEndsElement)
        assertTrue(loadedTable.allowsVerticalScroll)
        val loadedTableElement = loadedTable.element as ContentElement.Table
        assertEquals(cellPublisherBox, loadedTableElement.rows.single().cells.single().publisherBox)
        assertEquals(180, loadedTable.tableLayout?.colWidthsPx?.single())
        assertEquals(42, loadedTable.tableLayout?.rowHeightsPx?.single())
    }

    private fun table(
        text: String,
        tableScale: Float,
        cellScale: Float?,
        cellPublisherBox: PublisherBoxStyle? = null,
    ): ContentElement.Table = table(
        AnnotatedString(text),
        tableScale,
        cellScale,
        cellPublisherBox,
    )

    private fun table(
        text: AnnotatedString,
        tableScale: Float,
        cellScale: Float?,
        cellPublisherBox: PublisherBoxStyle? = null,
    ): ContentElement.Table = ContentElement.Table(
        rows = listOf(
            TableRow(
                cells = listOf(
                    TableCell(
                        text,
                        block = cellScale?.let { BlockStyle(fontScale = it) },
                        publisherBox = cellPublisherBox,
                    ),
                ),
                isHeader = false,
            ),
        ),
        block = BlockStyle(fontScale = tableScale),
    )

    private fun publisherBox(
        id: String = "box",
        style: PublisherBoxStyle,
    ) = ReaderPublisherBox(
        id = id,
        parentId = null,
        style = style,
        floatSide = null,
        startsAtElement = true,
        endsAtElement = true,
    )

    private fun publisherFloat(side: PublisherFloatSide) = ReaderPublisherFloat(
        side = side,
        style = PublisherBoxStyle(
            widthFrac = 0.32f,
            marginRightEm = 0.5f,
            backgroundColorArgb = 0xffeef5e7.toInt(),
        ),
        contents = listOf(
            ReaderPublisherFloatContent(
                sourceItemIndex = 0,
                element = ContentElement.Image(
                    path = "/nonexistent/publisher-float-image.jpg",
                    widthFrac = 1f,
                    altText = "Diagram",
                ),
                publisherBoxes = listOf(
                    publisherBox(
                        id = "float-image",
                        style = PublisherBoxStyle(paddingBottomEm = 0.2f),
                    ),
                ),
            ),
            ReaderPublisherFloatContent(
                sourceItemIndex = 1,
                element = ContentElement.Paragraph(AnnotatedString("Caption")),
            ),
        ),
    )

    private fun pagesFor(table: ContentElement.Table): List<BookPage> {
        val grid = TableGrid.build(table.rows)
        val layout = TableLayout(grid, intArrayOf(100), intArrayOf(24), 1f)
        return listOf(
            BookPage(
                parts = listOf(
                    PagePart(
                        itemIndex = 0,
                        element = table,
                        rowStart = 0,
                        rowEnd = 1,
                        tableLayout = layout,
                    ),
                ),
                firstItemIndex = 0,
            ),
        )
    }
}
