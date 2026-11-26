package com.example.frogreader.ui.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import com.example.frogreader.data.BookChapterWindow
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.BookFont
import com.example.frogreader.data.model.BookIndex
import com.example.frogreader.data.model.BookNavigationTarget
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.LinkedDocumentTarget
import com.example.frogreader.data.model.NoteDocument
import com.example.frogreader.data.model.TableCell
import com.example.frogreader.data.model.TableRow
import com.example.frogreader.data.model.publisherStyleOf
import com.example.frogreader.data.model.withFootnoteRefStyle
import com.example.frogreader.data.model.withoutFootnotes
import java.io.File

/** Builds an immutable reader snapshot on Default; indexed lookups remain memory-only. */
internal fun buildReaderReady(
    book: Book,
    content: BookContent,
    cover: File?,
    hideFootnotes: Boolean,
    initialFlatIndex: Int,
    contentComplete: Boolean,
    index: BookIndex? = null,
    window: BookChapterWindow? = null,
    auxiliaryContentReady: Boolean = true,
    epubOpening: ReaderEpubOpening? = null,
    sourceGeneration: Int = 0,
): ReaderState {
    // A cover is chrome around the reading order, not a substitute for
    // one.  In particular an EPUB whose entire spine is linear="no" must
    // not become Ready with an empty chapterStarts list: ReaderScreen's
    // chapter/progress coordinate space would be undefined.
    if (index?.let { it.totalElements <= 0 } ?: !content.hasSequentialContent()) return ReaderState.Error

    // Title page: the cover opens the book before the text.
    val coverItem = cover?.takeIf { epubOpening?.includesCover != false }?.let { cover: File ->
        ReaderItem(0, ContentElement.Image(cover.absolutePath))
    }

    val chapterTitles = index?.chapters?.map { it.title } ?: content.chapters.map { it.title }
    val chapterDepths = index?.chapters?.map { it.depth } ?: content.chapters.map { it.depth }
    val fonts = index?.fonts ?: content.fonts
    val bookFonts = loadReaderBookFonts(fonts)
    fun adjusted(element: ContentElement) = adjustReaderElement(element, hideFootnotes)
    val items: List<ReaderItem>
    val chapterStarts: List<Int>
    val windowRange: IntRange?
    if (index != null) {
        val indexed = IndexedReaderItems(index, requireNotNull(window), coverItem, ::adjusted)
        items = indexed
        chapterStarts = indexed.chapterStarts
        windowRange = indexed.loadedRange
    } else {
        val completeItems = ReaderChapterItems(content.chapters, coverItem, ::adjusted)
        completeItems.prepareAt(initialFlatIndex)
        items = completeItems
        chapterStarts = completeItems.chapterStarts
        windowRange = null
    }

    val linkedDocuments = content.linkedDocuments.mapValues { (_, document) ->
        val adjustedElements = document.elements.map(::adjusted)
        val plan = planPublisherChapter(adjustedElements, document.publisherBoxes)
        ReaderLinkedDocument(
            id = document.id,
            title = document.title,
            items = adjustedElements.mapIndexed { elementIndex, element ->
                val plannedFloat = plan.floatByTarget[elementIndex]
                ReaderItem(
                    chapterIndex = 0,
                    element = element,
                    publisherBoxes = plan.boxesByElement[elementIndex],
                    publisherFloat = plannedFloat?.let { float ->
                        ReaderPublisherFloat(
                            side = float.side,
                            style = float.style,
                            contents = float.contents.map { content ->
                                ReaderPublisherFloatContent(
                                    sourceItemIndex = content.sourceElementIndex,
                                    element = content.element,
                                    publisherBoxes = content.publisherBoxes,
                                )
                            },
                        )
                    },
                    suppressedByPublisherFloat = elementIndex in plan.suppressedElements,
                    publisherClearBefore = plan.clearBefore[elementIndex],
                )
            },
        )
    }
    val navigation = (index?.navigation ?: content.navigation).mapNotNull { entry ->
        val target = when (val source = entry.target) {
            is BookNavigationTarget.ReadingOrder -> {
                val start = chapterStarts.getOrNull(source.chapterIndex)
                    ?: return@mapNotNull null
                ReaderNavigationTarget.ReadingOrder(
                    flatItemIndex = readerFlatTarget(start, source.elementIndex, items.size),
                    chapterIndex = source.chapterIndex,
                )
            }

            is BookNavigationTarget.Linked -> {
                if (index == null || auxiliaryContentReady) {
                    val document = linkedDocuments[source.documentId] ?: return@mapNotNull null
                    if (source.elementIndex !in document.items.indices) return@mapNotNull null
                }
                ReaderNavigationTarget.Linked(
                    LinkedDocumentTarget(source.documentId, source.elementIndex),
                )
            }
        }
        ReaderNavigationEntry(entry.title, entry.depth, target)
    }.ifEmpty {
        // FB2/MOBI and EPUBs without nav/NCX retain the historical flat
        // chapter list, now expressed through the same typed target.
        chapterTitles.mapIndexed { index, title ->
            ReaderNavigationEntry(
                title = title,
                depth = chapterDepths[index],
                target = ReaderNavigationTarget.ReadingOrder(
                    flatItemIndex = chapterStarts[index],
                    chapterIndex = index,
                ),
            )
        }
    }

    if (items.isEmpty()) {
        return ReaderState.Error
    } else {
        return ReaderState.Ready(
            book = book,
            items = items,
            chapterStarts = chapterStarts,
            chapterTitles = chapterTitles,
            chapterDepths = chapterDepths,
            notes = if (hideFootnotes) {
                emptyMap()
            } else {
                LazyMappedValuesMap(content.notes) { _, note ->
                    NoteDocument(
                        elements = note.elements.map(::adjusted),
                        publisherBoxes = note.publisherBoxes,
                    )
                }
            },
            // (chapter, element) → flat index the readers actually seek to.
            linkTargets = (index?.linkTargets ?: content.linkTargets).mapNotNull { (key, target) ->
                val (chapter, element) = target
                chapterStarts.getOrNull(chapter)?.let { start ->
                    key to readerFlatTarget(start, element, items.size)
                }
            }.toMap(),
            linkedDocuments = linkedDocuments,
            linkedDocumentTargets = (index?.linkedDocumentTargets ?: content.linkedDocumentTargets).filterValues { target ->
                val document = linkedDocuments[target.documentId]
                index != null && !auxiliaryContentReady ||
                    document != null && target.elementIndex in document.items.indices
            },
            navigation = epubOpening?.structure?.navigation?.map { entry ->
                ReaderNavigationEntry(entry.title, entry.depth, ReaderNavigationTarget.EpubSource(entry.destination))
            } ?: navigation,
            bookFonts = bookFonts,
            embeddedFontSignature = embeddedFontSignature(fonts),
            language = if (index != null) index.language else content.language,
            pageProgression = ReaderProgression.resolve(
                index?.pageProgression ?: content.pageProgression,
                if (index != null) index.language else content.language,
            ),
            contentComplete = contentComplete || windowRange == items.indices,
            publisherStyle = if (index != null) index.publisherStyle else publisherStyleOf(content),
            globalCoordinatesReady = index != null || contentComplete,
            windowRange = windowRange,
            auxiliaryContentReady = auxiliaryContentReady,
            epubOpening = epubOpening?.structure,
            epubDestination = epubOpening?.destination,
            sourceGeneration = sourceGeneration,
        )
    }
}

/** A single text transform shared by indexed windows, full books and explicit search. */
internal fun adjustReaderElement(element: ContentElement, hideFootnotes: Boolean): ContentElement {
    fun adjustedText(text: AnnotatedString) =
        if (hideFootnotes) text.withoutFootnotes() else text.withFootnoteRefStyle()
    return when (element) {
        is ContentElement.Paragraph -> element.copy(text = adjustedText(element.text))
        is ContentElement.Heading -> element.copy(styledText = adjustedText(element.styledText))
        is ContentElement.Table -> element.copy(
            rows = element.rows.map { row ->
                TableRow(
                    cells = row.cells.map { cell ->
                        TableCell(
                            text = adjustedText(cell.text),
                            colSpan = cell.colSpan,
                            rowSpan = cell.rowSpan,
                            align = cell.align,
                            header = cell.header,
                            block = cell.block,
                            publisherBox = cell.publisherBox,
                            publisherPaddingSpecified = cell.publisherPaddingSpecified,
                            publisherBorderSpecified = cell.publisherBorderSpecified,
                            narrowStyle = cell.narrowStyle,
                        )
                    },
                    isHeader = row.isHeader,
                )
            },
        )
        else -> element
    }
}

private fun loadReaderBookFonts(fonts: List<BookFont>): Map<String, FontFamily> {
    val availableFaces = fonts.filter { File(it.path).isFile }
    if (availableFaces.isEmpty()) return emptyMap()
    return availableFaces.groupBy { it.family }.mapNotNull { (family, faces) ->
        com.example.frogreader.ui.theme.publisherFontFamily(faces)?.let { family to it }
    }.toMap()
}

private fun readerFlatTarget(start: Int, element: Int, itemCount: Int): Int =
    (start.toLong() + element).coerceIn(0L, (itemCount - 1).coerceAtLeast(0).toLong()).toInt()
