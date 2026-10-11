package com.example.frogreader.ui.reader

import android.graphics.Region
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.QuoteColor
import com.example.frogreader.ui.reader.selection.BookAnchor
import com.example.frogreader.ui.reader.selection.BookSelection
import com.example.frogreader.ui.reader.selection.SelectionController
import com.example.frogreader.ui.reader.selection.TextFragment
import com.example.frogreader.ui.reader.selection.readerHighlights
import com.example.frogreader.ui.reader.selection.rememberReaderHighlights
import com.example.frogreader.ui.reader.selection.rememberTextFragment
import com.example.frogreader.ui.theme.FrogReaderTheme

private val selectionPassages = listOf(
    "The Mouse did not notice this question, but hurriedly went on, “—found it advisable " +
        "to go with Edgar Atheling to meet William and offer him the crown. William’s " +
        "conduct at first was moderate. But the insolence of his Normans—’ How are you " +
        "getting on now, my dear?” it continued, turning to Alice as it spoke.",
    "“As wet as ever,” said Alice in a melancholy tone: “it doesn’t seem to dry me at all.”",
)

@Preview(name = "Selection · hyphens", widthDp = 393, heightDp = 650)
@Composable
private fun SelectionHyphens() = SelectionPreview(AppTheme.WHITE)

@Preview(name = "Selection · partial first line", widthDp = 393, heightDp = 650)
@Composable
private fun SelectionHyphensPartial() = SelectionPreview(AppTheme.WHITE, partial = true)

@Preview(name = "Selection · narrow Beige", widthDp = 320, heightDp = 840)
@Composable
private fun SelectionHyphensBeige() = SelectionPreview(AppTheme.SEPIA)

@Composable
private fun SelectionPreview(theme: AppTheme, partial: Boolean = false) {
    val settings = remember {
        ReaderSettings(fontSizeSp = 18f, lineHeight = 1.35f, justify = true, hyphenation = true)
    }
    val controller = remember {
        SelectionController().apply {
            set(BookSelection.of(
                BookAnchor(0, if (partial) 10 else 0),
                BookAnchor(1, selectionPassages[1].length),
            ))
        }
    }
    FrogReaderTheme(theme) {
        val colors = readerColors(theme)
        val highlights = rememberReaderHighlights(
            controller, emptyList(), QuoteColor.entries.associateWith(colors::quoteHighlight), colors.selection,
        )
        Column(Modifier.fillMaxSize().background(colors.background).padding(24.dp)) {
            selectionPassages.forEachIndexed { index, passage ->
                val fragment = rememberTextFragment(highlights, index, 0, passage.length)
                Text(
                    text = passage,
                    style = ReaderMetrics.textStyle(
                        ContentElement.Paragraph(AnnotatedString(passage)), settings,
                        settings.fontSizeSp, isParagraphStart = true, language = "en",
                    ).copy(color = colors.text),
                    onTextLayout = {
                        fragment?.layout = it
                        if (fragment != null) reportHyphenCoverage(it, fragment, controller)
                    },
                    modifier = Modifier.readerHighlights(fragment, highlights),
                )
            }
        }
    }
}

/** Native-render diagnostics: the point in an inserted hyphen must be covered. */
private fun reportHyphenCoverage(
    layout: TextLayoutResult,
    fragment: TextFragment,
    controller: SelectionController,
) {
    val span = controller.selection?.intersect(fragment.itemIndex, 0, fragment.length) ?: return
    val clip = Region(0, 0, layout.size.width, layout.size.height)
    val native = Region().apply { setPath(layout.getPathForRange(span.start, span.end).asAndroidPath(), clip) }
    val actual = Region().apply { setPath(fragment.pathFor(layout, span).asAndroidPath(), clip) }
    for (line in 0 until layout.lineCount) {
        val end = layout.getLineEnd(line, visibleEnd = true)
        if (end <= span.start || end > span.end || end == 0) continue
        val glyph = layout.getBoundingBox(end - 1)
        if (glyph.width > 1f) {
            val x = (minOf(glyph.right, layout.getLineRight(line), layout.size.width.toFloat()) - 1).toInt()
            val y = glyph.center.y.toInt()
            println("Selection ending: line=$line native=${native.contains(x, y)} actual=${actual.contains(x, y)}")
            check(actual.contains(x, y)) { "Selected line ending is not highlighted" }
        }
    }
}
