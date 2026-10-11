package com.example.frogreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.PageTurnAnimation
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.QuoteColor
import com.example.frogreader.ui.reader.selection.BookAnchor
import com.example.frogreader.ui.reader.selection.BookSelection
import com.example.frogreader.ui.reader.selection.SelectionController
import com.example.frogreader.ui.reader.selection.SelectionHandles
import com.example.frogreader.ui.reader.selection.availableQuoteColors
import com.example.frogreader.ui.reader.selection.quoteHighlights
import com.example.frogreader.ui.reader.selection.readerHighlights
import com.example.frogreader.ui.reader.selection.rememberReaderHighlights
import com.example.frogreader.ui.reader.selection.rememberTextFragment
import com.example.frogreader.ui.theme.FrogReaderTheme

private val quotePreviewText = "Феті Виприн не гаяв часу. Щойно побачивши, що темні постаті надходять зі саду, він уже знав, " +
    "що мусить або рятуватись втечею, або загинути. І він утік, побіг через чорний вхід, через сад і через поля. " +
    "Спершу ніхто не міг уторопати, про що він белькоче. Та потому побігли зрозуміли, що прийшли вороги."

private val quotePreviewQuotes = listOf(
    Quote("original", quotePreviewText.substring(40, 260), 0, 1L,
        startItem = 0, startChar = 40, endItem = 0, endChar = 260),
    Quote("blue", quotePreviewText.substring(80, 220), 0, 2L,
        startItem = 0, startChar = 80, endItem = 0, endChar = 220, color = QuoteColor.BLUE),
    Quote("rose", quotePreviewText.substring(110, 175), 0, 3L,
        startItem = 0, startChar = 110, endItem = 0, endChar = 175, color = QuoteColor.ROSE),
    Quote("enclosing", quotePreviewText, 0, 4L,
        startItem = 0, startChar = 0, endItem = 0, endChar = quotePreviewText.length, color = QuoteColor.VIOLET),
)

@Preview(name = "Quotes · Light", widthDp = 393, heightDp = 690)
@Composable
private fun QuotesLight() = QuotePagePreview(AppTheme.WHITE)

@Preview(name = "Quotes · Beige · narrow", widthDp = 320, heightDp = 800)
@Composable
private fun QuotesBeige() = QuotePagePreview(AppTheme.SEPIA)

@Preview(name = "Quotes · Midnight", widthDp = 393, heightDp = 690)
@Composable
private fun QuotesMidnight() = QuotePagePreview(AppTheme.OLED)

@Preview(name = "Quotes · Midnight · selection", widthDp = 393, heightDp = 690)
@Composable
private fun QuotesMidnightSelection() = QuotePagePreview(AppTheme.OLED, selecting = true)

@Preview(name = "Quotes · menu · large text", widthDp = 320, heightDp = 800, fontScale = 1.5f)
@Composable
private fun QuotesLargeText() = QuotePagePreview(AppTheme.SEPIA, selecting = true)

@Preview(name = "Quotes · existing quote menu", widthDp = 393, heightDp = 690)
@Composable
private fun QuotesMenu() = QuotePagePreview(AppTheme.WHITE, selecting = true)

@Preview(name = "Quotes · nested color choice", widthDp = 320, heightDp = 360, fontScale = 1.5f)
@Composable
private fun QuotesPalette() {
    FrogReaderTheme(AppTheme.SEPIA) {
        val colors = readerColors(AppTheme.SEPIA)
        Surface(color = colors.chrome, contentColor = colors.onChrome) {
            Column {
                QuoteColorChoices(colors, listOf(QuoteColor.ROSE, QuoteColor.AMBER, QuoteColor.VIOLET)) { }
            }
        }
    }
}

@Preview(name = "Handles · Stack", widthDp = 393, heightDp = 690)
@Composable
private fun HandlesStack() = HandleTurnPreview(PageTurnAnimation.CASCADE, 0.32f)

@Preview(name = "Handles · Curl", widthDp = 393, heightDp = 690)
@Composable
private fun HandlesCurl() = HandleTurnPreview(PageTurnAnimation.PAGE_CURL, 0.45f)

@Preview(name = "Handles · Slide", widthDp = 393, heightDp = 690)
@Composable
private fun HandlesSlide() = HandleTurnPreview(PageTurnAnimation.SLIDE, 0.32f)

@Composable
private fun QuotePagePreview(theme: AppTheme, selecting: Boolean = false) {
    FrogReaderTheme(theme) {
        val colors = readerColors(theme, MaterialTheme.colorScheme)
        val controller = remember {
            SelectionController().apply {
                if (selecting) set(BookSelection.of(BookAnchor(0, 90), BookAnchor(0, 190)))
            }
        }
        val highlights = rememberReaderHighlights(controller, quoteHighlights(quotePreviewQuotes),
            QuoteColor.entries.associateWith(colors::quoteHighlight), colors.selection)
        val fragment = rememberTextFragment(highlights, 0, 0, quotePreviewText.length)
        Column(Modifier.fillMaxSize().background(colors.background).padding(top = 32.dp)) {
            if (selecting) {
                Surface(color = colors.chrome, contentColor = colors.onChrome) {
                    SelectionToolbarActions(colors, setOf("original", "blue", "rose", "enclosing"),
                        availableQuoteColors(controller.selection, quotePreviewQuotes), {}, {}, {}, {})
                }
                Spacer(Modifier.height(24.dp))
            }
            Text(quotePreviewText,
                style = MaterialTheme.typography.bodyLarge.copy(color = colors.text),
                modifier = Modifier.padding(horizontal = 24.dp).readerHighlights(fragment, highlights),
                onTextLayout = { fragment?.layout = it })
            Spacer(Modifier.height(32.dp))
            QuoteColor.entries.forEach { color ->
                Surface(color = colors.quoteHighlight(color).compositeOver(colors.background), contentColor = colors.text) {
                    Text(color.name, Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun HandleTurnPreview(animation: PageTurnAnimation, position: Float) {
    FrogReaderTheme(AppTheme.WHITE) {
        val colors = readerColors(AppTheme.WHITE)
        val controller = remember {
            SelectionController().apply { set(BookSelection.of(BookAnchor(0, 44), BookAnchor(0, 155))) }
        }
        val pager = rememberPagerState(initialPage = 0, initialPageOffsetFraction = position) { 2 }
        val touch = remember { PageCurlTouch() }
        Box(Modifier.fillMaxSize().background(colors.background).onPlaced { controller.space = it }) {
            HorizontalPager(pager, userScrollEnabled = false, beyondViewportPageCount = 1) { page ->
                val content: @Composable () -> Unit = {
                    val highlights = rememberReaderHighlights(controller, quoteHighlights(quotePreviewQuotes),
                        QuoteColor.entries.associateWith(colors::quoteHighlight), colors.selection)
                    val fragment = rememberTextFragment(highlights, page, 0, quotePreviewText.length)
                    Box(Modifier.fillMaxSize().background(colors.background)) {
                        Text(quotePreviewText,
                            style = ReaderMetrics.textStyle(com.example.frogreader.data.model.ContentElement.Paragraph(
                                androidx.compose.ui.text.AnnotatedString(quotePreviewText)), ReaderSettings(), 18f).copy(color = colors.text),
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 96.dp).readerHighlights(fragment, highlights),
                            onTextLayout = { fragment?.layout = it })
                        SelectionHandles(controller, colors.accent, pageEpoch = highlights.epoch)
                    }
                }
                when (animation) {
                    PageTurnAnimation.CASCADE -> CascadePage(pager, page, false, touch, colors, content)
                    PageTurnAnimation.PAGE_CURL -> PaperPage(pager, page, false, touch, colors, content)
                    else -> content()
                }
            }
        }
    }
}
