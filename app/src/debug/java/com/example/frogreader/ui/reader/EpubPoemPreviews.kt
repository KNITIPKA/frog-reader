package com.example.frogreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.data.parser.HtmlMapper
import com.example.frogreader.ui.theme.FrogReaderTheme
import org.jsoup.Jsoup

@Preview(name = "Alice 1 · CSS poem", widthDp = 393, heightDp = 1780)
@Composable
private fun Alice1Poem() = EpubPoemPreview(1)

@Preview(name = "Alice 2 · CSS poem", widthDp = 393, heightDp = 1500)
@Composable
private fun Alice2Poem() = EpubPoemPreview(2)

@Preview(name = "Alice 2 · narrow large text", widthDp = 320, heightDp = 2100, fontScale = 1.3f)
@Composable
private fun Alice2PoemLarge() = EpubPoemPreview(2)

@Preview(name = "Alice 1 · reader typography", widthDp = 393, heightDp = 1780)
@Composable
private fun Alice1ReaderTypography() = EpubPoemPreview(1, bookStyles = false)

@Preview(name = "Alice 2 · reader typography", widthDp = 393, heightDp = 1500)
@Composable
private fun Alice2ReaderTypography() = EpubPoemPreview(2, bookStyles = false)

@Composable
private fun EpubPoemPreview(number: Int, bookStyles: Boolean = true) {
    val parsed = remember(number) {
        val mapper = HtmlMapper(
            resolveImage = { null },
            css = CssResolver(listOf(CssResolver.Sheet(EpubPoemFixtures.css(number)))),
        )
        val elements = mapper.map(Jsoup.parse(EpubPoemFixtures.html(number)).body())
        elements to planPublisherChapter(elements, mapper.publisherBoxes)
    }
    val settings = ReaderSettings(bookStyles = bookStyles, fontSizeSp = 18f, lineHeight = 1.3f)
    FrogReaderTheme(AppTheme.WHITE) {
        val colors = readerColors(AppTheme.WHITE)
        BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
            val width = maxWidth - ReaderMetrics.horizontalPadding(settings.pageMargins) * 2
            Column(Modifier.padding(vertical = 12.dp)) {
                parsed.first.forEachIndexed { index, element ->
                    RenderPart(
                        element = element,
                        textOverride = null,
                        isParagraphStart = true,
                        imageHeightPx = null,
                        settings = settings,
                        appSettings = AppSettings(theme = AppTheme.WHITE),
                        fontSize = settings.fontSizeSp,
                        colors = colors,
                        contentWidth = width,
                        language = "en",
                        publisherBoxes = parsed.second.boxesByElement[index],
                    )
                }
            }
        }
    }
}
