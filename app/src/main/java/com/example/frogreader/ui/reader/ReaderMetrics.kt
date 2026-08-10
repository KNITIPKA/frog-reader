package com.example.frogreader.ui.reader

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.frogreader.data.PageMargins
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.model.BlockAlign
import com.example.frogreader.data.model.BlockStyle
import com.example.frogreader.data.model.BookTextDirection
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.ParagraphStyle
import com.example.frogreader.ui.theme.fontFamilyFor

/**
 * Single source of truth for reader text styles and spacing.
 * Pagination measures with these exact values, and both reading modes render
 * with them — otherwise paged text would overflow its page.
 *
 * Books contribute [BlockStyle]s (from EPUB CSS or FB2 semantics); user
 * settings contribute the base font, size, line height and justification.
 * Everything here merges the two, so a change in either lands consistently
 * in measurement and drawing.
 */
object ReaderMetrics {

    /** Base horizontal padding of the reading column (user setting). */
    fun horizontalPadding(margins: PageMargins): Dp = when (margins) {
        PageMargins.NARROW -> 10.dp
        PageMargins.NORMAL -> 20.dp
        PageMargins.WIDE -> 34.dp
    }

    val maxImageHeight = 520.dp

    private fun blockOf(element: ContentElement): BlockStyle? = when (element) {
        is ContentElement.Paragraph -> element.block
        is ContentElement.Heading -> element.block
        is ContentElement.Table -> element.block
        else -> null
    }

    /**
     * Extra start/end padding beyond the base column padding.
     * Fractional indents (an epigraph's `margin-left: 30%`) need the width
     * of the content column; em indents scale with the base font size.
     */
    fun horizontalInsets(
        element: ContentElement,
        contentWidth: Dp,
        fontSize: Float,
    ): Pair<Dp, Dp> {
        val kindInset = when ((element as? ContentElement.Paragraph)?.style) {
            ParagraphStyle.QUOTE -> 20.dp
            ParagraphStyle.POEM -> 24.dp
            else -> 0.dp
        }
        val block = blockOf(element) ?: return kindInset to 0.dp

        var start = (contentWidth * block.indentStartFrac) +
            (fontSize * block.indentStartEm).dp
        // A quote whose CSS specifies no indent of its own still keeps the
        // reader's default inset, so quotations stay visually set off.
        if (start < kindInset) start = kindInset
        val end = (fontSize * block.indentEndEm).dp
        return start.coerceAtMost(contentWidth * 0.45f) to
            end.coerceAtMost(contentWidth * 0.2f)
    }

    /** Vertical padding above/below an element (top, bottom). */
    fun verticalPaddings(element: ContentElement, fontSize: Float): Pair<Dp, Dp> {
        val base = when (element) {
            is ContentElement.Paragraph -> when (element.style) {
                ParagraphStyle.NORMAL -> 3.dp
                else -> 6.dp
            }

            is ContentElement.Heading -> if (element.level <= 2) 28.dp else 16.dp
            is ContentElement.Image -> 12.dp
            ContentElement.Divider -> 20.dp
            is ContentElement.Spacer -> 0.dp
            is ContentElement.Table -> 12.dp
        }
        val block = blockOf(element) ?: return base to base
        // The book's own spacing wins when it asks for more than the default.
        val top = maxOf(base, (fontSize * block.spaceBeforeEm).dp)
        val bottom = maxOf(base, (fontSize * block.spaceAfterEm).dp)
        return top to bottom
    }

    /** Height of a deliberate blank line ([ContentElement.Spacer]). */
    fun spacerHeight(element: ContentElement.Spacer, fontSize: Float): Dp =
        (fontSize * element.heightEm.coerceIn(0.3f, 3f)).dp

    /**
     * Color-less text style for an element; colors are applied at render time.
     * [isParagraphStart] controls the first-line indent of continued fragments.
     * [bookFonts] maps the book's embedded font families ("publisher's
     * formatting" mode picks fonts from it).
     */
    fun textStyle(
        element: ContentElement,
        settings: ReaderSettings,
        fontSize: Float,
        isParagraphStart: Boolean = true,
        bookFonts: Map<String, FontFamily> = emptyMap(),
        language: String? = null,
    ): TextStyle {
        val block = blockOf(element)
        val hyphenate = if (settings.bookStyles && block?.hyphens != null) {
            block.hyphens == true
        } else {
            settings.hyphenation
        }
        val base = TextStyle(
            fontSize = fontSize.sp,
            lineHeight = (fontSize * settings.lineHeight).sp,
            fontFamily = bookFamilyFor(block, settings, bookFonts)
                ?: fontFamilyFor(settings.font, settings.customFontPath),
            hyphens = if (hyphenate) Hyphens.Auto else Hyphens.None,
            lineBreak = LineBreak.Paragraph,
            // The book's language picks the hyphenation patterns; without it
            // a Russian book on an en-US device never hyphenates at all.
            localeList = (block?.language ?: language)?.let { LocaleList(it) },
            textDirection = when (block?.direction) {
                BookTextDirection.LTR -> TextDirection.Ltr
                BookTextDirection.RTL -> TextDirection.Rtl
                null -> TextDirection.Unspecified
            },
        )
        return when (element) {
            is ContentElement.Paragraph -> paragraphStyle(
                element, settings, fontSize, isParagraphStart, base,
            )

            is ContentElement.Heading -> headingStyle(element, settings, fontSize, base)

            else -> base
        }
    }

    /** The book's own font for this block, when the toggle allows it. */
    private fun bookFamilyFor(
        block: BlockStyle?,
        settings: ReaderSettings,
        bookFonts: Map<String, FontFamily>,
    ): FontFamily? {
        if (!settings.bookStyles) return null
        val name = block?.fontFamily ?: return null
        bookFonts[name]?.let { return it }
        return when (name) {
            "serif" -> FontFamily.Serif
            "sans-serif" -> FontFamily.SansSerif
            "monospace" -> FontFamily.Monospace
            "cursive" -> FontFamily.Cursive
            else -> null
        }
    }

    /** Line height multiplier: the book's own (in publisher mode) or the user's. */
    private fun lineHeightMult(block: BlockStyle?, settings: ReaderSettings): Float =
        if (settings.bookStyles && block?.lineHeightMult != null) {
            block.lineHeightMult
        } else {
            settings.lineHeight
        }

    private fun paragraphStyle(
        element: ContentElement.Paragraph,
        settings: ReaderSettings,
        fontSize: Float,
        isParagraphStart: Boolean,
        base: TextStyle,
    ): TextStyle {
        val block = element.block
        val kind = element.style

        val scale = (block?.fontScale ?: 1f)
        val italic = block?.italic ?: (kind != ParagraphStyle.NORMAL)
        val bold = block?.bold ?: false

        val defaultAlign = when (kind) {
            ParagraphStyle.NORMAL ->
                if (settings.justify) TextAlign.Justify else TextAlign.Start

            else -> TextAlign.Start
        }
        // CENTER/END are structural (epigraphs, signatures) and always win;
        // START/JUSTIFY of body text only win in publisher mode — otherwise
        // the user's justification setting decides.
        val align = when (block?.align) {
            BlockAlign.CENTER -> TextAlign.Center
            BlockAlign.END -> TextAlign.End
            BlockAlign.START -> if (settings.bookStyles) TextAlign.Start else defaultAlign
            BlockAlign.JUSTIFY -> if (settings.bookStyles) TextAlign.Justify else defaultAlign
            null -> defaultAlign
        }

        // First-line indent: the book's explicit wish wins; otherwise normal
        // paragraphs are indented, quotes/poems are not. Centered blocks are
        // never indented — the indent would visually skew them.
        val wantsIndent = when {
            align == TextAlign.Center || align == TextAlign.End -> false
            block?.firstLineIndent != null -> block.firstLineIndent == true
            else -> kind == ParagraphStyle.NORMAL
        }
        // Publisher mode uses the exact indent width the book asked for.
        val indentEm = if (settings.bookStyles && block?.firstLineIndentEm != null) {
            block.firstLineIndentEm.coerceIn(0.4f, 4f)
        } else {
            1.4f
        }

        return base.copy(
            fontSize = (fontSize * scale).sp,
            lineHeight = (fontSize * scale * lineHeightMult(block, settings)).sp,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            fontWeight = if (bold) FontWeight.Bold else null,
            textAlign = align,
            textIndent = if (wantsIndent && isParagraphStart) {
                TextIndent(firstLine = (fontSize * indentEm).sp)
            } else {
                TextIndent.None
            },
        )
    }

    private fun headingStyle(
        element: ContentElement.Heading,
        settings: ReaderSettings,
        fontSize: Float,
        base: TextStyle,
    ): TextStyle {
        val block = element.block
        // fontScale == 1 means the book didn't size this heading itself;
        // fall back to the per-level defaults.
        val scale = block?.fontScale?.takeIf { it != 1f } ?: headingScale(element.level)
        val defaultAlign = if (element.level <= 2) TextAlign.Center else TextAlign.Start
        val align = when (block?.align) {
            BlockAlign.CENTER -> TextAlign.Center
            BlockAlign.END -> TextAlign.End
            BlockAlign.START -> TextAlign.Start
            BlockAlign.JUSTIFY -> defaultAlign
            null -> defaultAlign
        }
        val lineMult = if (settings.bookStyles && block?.lineHeightMult != null) {
            block.lineHeightMult
        } else {
            1.25f
        }
        return base.copy(
            fontSize = (fontSize * scale).sp,
            lineHeight = (fontSize * scale * lineMult).sp,
            fontWeight = if (block?.bold == false) FontWeight.Normal else FontWeight.Bold,
            fontStyle = if (block?.italic == true) FontStyle.Italic else FontStyle.Normal,
            textAlign = align,
            hyphens = Hyphens.None,
        )
    }

    fun headingScale(level: Int): Float = when (level) {
        1 -> 1.5f
        2 -> 1.32f
        3 -> 1.18f
        else -> 1.06f
    }

    /** Height of the divider line itself (padding is added separately). */
    val dividerHeight = 1.dp

    /** Inner padding of a table cell (all four sides). */
    val tableCellPadding = 6.dp

    /**
     * Style of a drop cap glyph (CSS `::first-letter`) at an exact,
     * geometry-fitted size. Font padding is stripped so the glyph fills
     * its box instead of hanging above four shortened lines.
     */
    fun dropCapStyle(
        settings: ReaderSettings,
        capFontSizeSp: Float,
        cap: com.example.frogreader.data.model.FirstLetterStyle?,
        bookFonts: Map<String, FontFamily>,
        language: String?,
    ): TextStyle = TextStyle(
        fontSize = capFontSizeSp.sp,
        lineHeight = capFontSizeSp.sp,
        fontFamily = cap?.fontFamily?.let { bookFonts[it] }
            ?: fontFamilyFor(settings.font, settings.customFontPath),
        fontWeight = if (cap?.bold == true) FontWeight.Bold else null,
        fontStyle = if (cap?.italic == true) FontStyle.Italic else FontStyle.Normal,
        localeList = language?.let { LocaleList(it) },
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both,
        ),
    )

    /**
     * Text style of a table cell. Slightly smaller than body text, no
     * hyphenation (cells are narrow, hyphens would riddle them), [scale]
     * steps down when the columns don't fit. Pagination measures and the
     * renderer draws with the exact same style.
     */
    fun tableCellStyle(
        settings: ReaderSettings,
        fontSize: Float,
        scale: Float,
        header: Boolean,
        language: String?,
    ): TextStyle = TextStyle(
        fontSize = (fontSize * 0.92f * scale).sp,
        lineHeight = (fontSize * 0.92f * scale * 1.3f).sp,
        fontFamily = fontFamilyFor(settings.font, settings.customFontPath),
        fontWeight = if (header) FontWeight.Bold else null,
        hyphens = Hyphens.None,
        lineBreak = LineBreak.Paragraph,
        localeList = language?.let { LocaleList(it) },
    )
}
