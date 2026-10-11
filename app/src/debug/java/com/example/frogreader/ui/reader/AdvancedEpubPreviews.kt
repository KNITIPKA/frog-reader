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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.parser.CssResolver
import com.example.frogreader.data.parser.HtmlMapper
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.R
import com.example.frogreader.ui.theme.FrogReaderTheme
import org.jsoup.Jsoup

@Preview(name = "Exiles · stage directions and dialogue", widthDp = 393, heightDp = 1700)
@Composable
private fun AdvancedDrama() = AdvancedEpubPreview(AdvancedEpubFixtures.dramaHtml, AdvancedEpubFixtures.dramaCss)

@Preview(name = "Exiles · narrow large text", widthDp = 320, heightDp = 2400, fontScale = 1.3f)
@Composable
private fun AdvancedDramaLarge() = AdvancedEpubPreview(AdvancedEpubFixtures.dramaHtml, AdvancedEpubFixtures.dramaCss)

@Preview(name = "Tristram Shandy · actual embedded Blackletter", widthDp = 393, heightDp = 950)
@Composable
private fun AdvancedBlackletter() {
    // CC0 publisher face from the supplied edition; debug resource only.
    val fonts = remember { mapOf("standard blackletter" to
        FontFamily(Font(R.font.epub_audit_blackletter, FontWeight.Bold))) }
    AdvancedEpubPreview(
        """<p>“When old age comes on, and repentance calls him to look back upon this black account, and state it over again with his conscience⁠—<b>Conscience</b> looks into the <b>Statutes at Large</b>;⁠—finds no express law broken by what he has done;⁠—perceives no penalty or forfeiture of goods and chattels incurred;⁠—sees no scourge waving over his head, or prison opening his gates upon him:⁠—What is there to affright his conscience?⁠—Conscience has got safely entrenched behind the Letter of the Law; sits there invulnerable, fortified with <b class="blackletter">Cases</b> and <b class="blackletter">Reports</b> so strongly on all sides;⁠—that it is not preaching can dispossess it of its hold.”</p>""",
        ".blackletter {font-family: 'Standard Blackletter';}", bookFonts = fonts,
    )
}

@Preview(name = "Tangled Tale · ten column symbols", widthDp = 393, heightDp = 400)
@Composable
private fun AdvancedMathTable() = AdvancedEpubPreview(AdvancedEpubFixtures.mathHtml, AdvancedEpubFixtures.mathCss)

@Preview(name = "Tangled Tale · narrow large text", widthDp = 320, heightDp = 500, fontScale = 1.3f)
@Composable
private fun AdvancedMathTableLarge() = AdvancedEpubPreview(AdvancedEpubFixtures.mathHtml, AdvancedEpubFixtures.mathCss)

@Preview(name = "Looking Glass · arithmetic rule", widthDp = 393, heightDp = 360)
@Composable
private fun AdvancedArithmetic() = AdvancedEpubPreview(AdvancedEpubFixtures.arithmeticHtml, AdvancedEpubFixtures.lookingGlassCss)

@Preview(name = "Looking Glass · arithmetic OLED", widthDp = 393, heightDp = 360)
@Composable
private fun AdvancedArithmeticOled() = AdvancedEpubPreview(AdvancedEpubFixtures.arithmeticHtml,
    AdvancedEpubFixtures.lookingGlassCss, AppTheme.OLED)

@Preview(name = "Looking Glass · indented verse", widthDp = 393, heightDp = 420)
@Composable
private fun AdvancedVerse() = AdvancedEpubPreview(AdvancedEpubFixtures.verseHtml, AdvancedEpubFixtures.lookingGlassCss)

@Preview(name = "Looking Glass · verse narrow large text", widthDp = 320, heightDp = 620, fontScale = 1.3f)
@Composable
private fun AdvancedVerseLarge() = AdvancedEpubPreview(AdvancedEpubFixtures.verseHtml, AdvancedEpubFixtures.lookingGlassCss)

@Preview(name = "Tristram Shandy · deliberate blank", widthDp = 393, heightDp = 1200)
@Composable
private fun AdvancedBlankPage() = AdvancedEpubPreview(AdvancedEpubFixtures.blankHtml, AdvancedEpubFixtures.blankCss)

@Preview(name = "Tristram Shandy · distributed stars", widthDp = 393, heightDp = 500)
@Composable
private fun AdvancedStars() = AdvancedEpubPreview(AdvancedEpubFixtures.starsHtml, AdvancedEpubFixtures.shandyRowsCss)

@Preview(name = "Tristram Shandy · stars narrow large text", widthDp = 320, heightDp = 620, fontScale = 1.3f)
@Composable
private fun AdvancedStarsLarge() = AdvancedEpubPreview(AdvancedEpubFixtures.starsHtml, AdvancedEpubFixtures.shandyRowsCss)

@Preview(name = "Tristram Shandy · brace composition", widthDp = 393, heightDp = 400)
@Composable
private fun AdvancedBrace() = AdvancedEpubPreview(AdvancedEpubFixtures.braceHtml, AdvancedEpubFixtures.shandyRowsCss)

@Preview(name = "Tristram Shandy · brace narrow large text", widthDp = 320, heightDp = 600, fontScale = 1.3f)
@Composable
private fun AdvancedBraceLarge() = AdvancedEpubPreview(AdvancedEpubFixtures.braceHtml, AdvancedEpubFixtures.shandyRowsCss)

@Composable
internal fun AdvancedEpubPreview(html: String, css: String, theme: AppTheme = AppTheme.WHITE,
    bookFonts: Map<String, FontFamily> = emptyMap()) {
    val parsed = remember(html, css) {
        val mapper = HtmlMapper(
            resolveImage = { null },
            css = CssResolver(listOf(CssResolver.Sheet(AdvancedEpubFixtures.coreCss), CssResolver.Sheet(css))),
        )
        val elements = mapper.map(Jsoup.parse(html).body())
        elements to planPublisherChapter(elements, mapper.publisherBoxes)
    }
    val settings = ReaderSettings(bookStyles = true, fontSizeSp = 18f, lineHeight = 1.3f)
    FrogReaderTheme(theme) {
        val colors = readerColors(theme)
        BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
            val width = maxWidth - ReaderMetrics.horizontalPadding(settings.pageMargins) * 2
            val viewportHeight = maxHeight
            Column(Modifier.padding(vertical = 12.dp)) {
                parsed.first.forEachIndexed { index, element ->
                    val displayElement = when (element) {
                        is ContentElement.Paragraph -> element.copy(text = element.text.withBookFonts(bookFonts))
                        is ContentElement.Heading -> element.copy(styledText = element.styledText.withBookFonts(bookFonts))
                        else -> element
                    }
                    RenderPart(
                        element = displayElement,
                        textOverride = null,
                        isParagraphStart = true,
                        imageHeightPx = null,
                        settings = settings,
                        appSettings = AppSettings(theme = theme),
                        fontSize = settings.fontSizeSp,
                        colors = colors,
                        contentWidth = width,
                        language = "en-GB",
                        bookFonts = bookFonts,
                        publisherBoxes = parsed.second.boxesByElement[index],
                        viewportHeight = viewportHeight,
                    )
                }
            }
        }
    }
}
