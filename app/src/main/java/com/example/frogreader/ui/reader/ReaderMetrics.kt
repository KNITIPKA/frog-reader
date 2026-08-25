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
import com.example.frogreader.data.model.HeadingDefaults
import com.example.frogreader.data.model.ParagraphStyle
import com.example.frogreader.data.parser.LanguageTag
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
     * Effective logical start/end padding beyond the base column padding.
     * Logical and physical CSS insets are retained separately in [BlockStyle];
     * folding physical left/right into this pair keeps pagination dependent
     * only on the sum while preserving the correct side at render time.
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
        var end = (contentWidth * block.indentEndFrac) +
            (fontSize * block.indentEndEm).dp
        // A quote whose CSS specifies no indent of its own still keeps the
        // reader's default inset, so quotations stay visually set off.
        if (start < kindInset) start = kindInset
        val left = (contentWidth * block.indentLeftFrac) +
            (fontSize * block.indentLeftEm).dp
        val right = (contentWidth * block.indentRightFrac) +
            (fontSize * block.indentRightEm).dp
        if (isRtl(element)) {
            start += right
            end += left
        } else {
            start += left
            end += right
        }
        return start.coerceAtMost(contentWidth * 0.45f) to
            end.coerceAtMost(contentWidth * 0.45f)
    }

    /**
     * Converts the author's logical start/end indents to physical left/right
     * independently of the Android UI locale. Pagination only needs their
     * sum; rendering needs this mapping to place an RTL block on the correct
     * side of the page.
     */
    fun physicalHorizontalInsets(
        element: ContentElement,
        contentWidth: Dp,
        fontSize: Float,
    ): Pair<Dp, Dp> {
        val (start, end) = horizontalInsets(element, contentWidth, fontSize)
        return if (isRtl(element)) end to start else start to end
    }

    /** Effective base direction for geometry; AUTO/null follows first strong text. */
    fun isRtl(element: ContentElement): Boolean {
        val block = blockOf(element)
        when (block?.direction) {
            BookTextDirection.LTR -> return false
            BookTextDirection.RTL -> return true
            BookTextDirection.AUTO, null -> Unit
        }
        val text = when (element) {
            is ContentElement.Paragraph -> element.text.text
            is ContentElement.Heading -> element.text
            is ContentElement.Table -> element.flatText()
            else -> ""
        }
        firstStrongRtl(text)?.let { return it }
        return LanguageTag.isRtl(block?.language)
    }

    private fun firstStrongRtl(text: String): Boolean? {
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            when (Character.getDirectionality(codePoint)) {
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
            }
            offset += Character.charCount(codePoint)
        }
        return null
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
        val resolvedLanguage = block?.language ?: language
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
            localeList = resolvedLanguage?.let { LocaleList(it) },
            textDirection = when (block?.direction) {
                BookTextDirection.LTR -> TextDirection.Ltr
                BookTextDirection.RTL -> TextDirection.Rtl
                BookTextDirection.AUTO, null -> autoTextDirection(resolvedLanguage)
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
            BlockAlign.LEFT -> TextAlign.Left
            BlockAlign.RIGHT -> TextAlign.Right
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
        // null means the publisher did not size the heading. An explicit
        // 1em is meaningful and must override the semantic H1-H6 default.
        val scale = block?.fontScale ?: headingScale(element.level)
        val defaultAlign = if (element.level <= 2) TextAlign.Center else TextAlign.Start
        val align = when (block?.align) {
            BlockAlign.CENTER -> TextAlign.Center
            BlockAlign.END -> TextAlign.End
            BlockAlign.START -> TextAlign.Start
            BlockAlign.JUSTIFY -> defaultAlign
            BlockAlign.LEFT -> TextAlign.Left
            BlockAlign.RIGHT -> TextAlign.Right
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

    /**
     * Reader defaults for the complete HTML/ebook heading hierarchy.
     *
     * Keep every level distinct: EPUB/KF8 expose h1…h6 directly and FB2/MOBI
     * normalize their structural depth to the same range.  The values are
     * deliberately gentler than a browser's desktop UA stylesheet, because
     * the narrow reading column must remain usable at the user's maximum base
     * font size.  They still form a clear hierarchy around body text.
     */
    fun headingScale(level: Int): Float = HeadingDefaults.scale(level)

    /** Height of the divider line itself (padding is added separately). */
    val dividerHeight = 1.dp

    /** Inner padding of a table cell (all four sides). */
    val tableCellPadding = 6.dp

    /**
     * Style of a drop cap glyph (pseudo or explicit float) at an exact,
     * geometry-fitted size. Font padding is stripped so the glyph fills
     * its box instead of hanging above four shortened lines.
     */
    fun dropCapStyle(
        settings: ReaderSettings,
        capFontSizeSp: Float,
        cap: com.example.frogreader.data.model.FirstLetterStyle?,
        bookFonts: Map<String, FontFamily>,
        language: String?,
    ): TextStyle {
        val resolvedLanguage = cap?.language ?: language
        return TextStyle(
            fontSize = capFontSizeSp.sp,
            lineHeight = capFontSizeSp.sp,
            fontFamily = if (settings.bookStyles) {
                cap?.fontFamily?.let { family ->
                    bookFonts[family] ?: when (family) {
                        "serif" -> FontFamily.Serif
                        "sans-serif", "sans" -> FontFamily.SansSerif
                        "monospace" -> FontFamily.Monospace
                        "cursive" -> FontFamily.Cursive
                        else -> null
                    }
                }
            } else {
                null
            } ?: fontFamilyFor(settings.font, settings.customFontPath),
            fontWeight = if (cap?.bold == true) FontWeight.Bold else null,
            fontStyle = if (cap?.italic == true) FontStyle.Italic else FontStyle.Normal,
            localeList = resolvedLanguage?.let { LocaleList(it) },
            textDirection = when (cap?.direction) {
                BookTextDirection.LTR -> TextDirection.Ltr
                BookTextDirection.RTL -> TextDirection.Rtl
                BookTextDirection.AUTO, null -> autoTextDirection(resolvedLanguage)
            },
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both,
            ),
        )
    }

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
        tableBlock: BlockStyle? = null,
        cellBlock: BlockStyle? = null,
        bookFonts: Map<String, FontFamily> = emptyMap(),
    ): TextStyle {
        // Table CSS belongs to the grid as a whole; cell AnnotatedString
        // spans then carry only their relative overrides (for example a td
        // at 80% inside a table at 150%).  Applying the table scale here is
        // essential because both pagination and rendering call this exact
        // function.  Previously Table.block affected only outer margins, so
        // publisher font-size/family/line-height silently vanished.
        val blockScale = cellBlock?.fontScale ?: tableBlock?.fontScale ?: 1f
        val resolvedSize = fontSize * 0.92f * scale * blockScale
        val publisherLineHeight = cellBlock?.lineHeightMult ?: tableBlock?.lineHeightMult
        val resolvedLineHeight = if (settings.bookStyles && publisherLineHeight != null) {
            publisherLineHeight
        } else {
            1.3f
        }
        val fontBlock = when {
            cellBlock?.fontFamily != null -> cellBlock
            else -> tableBlock
        }
        val resolvedBold = cellBlock?.bold ?: tableBlock?.bold
        val resolvedItalic = cellBlock?.italic ?: tableBlock?.italic
        val resolvedLanguage = cellBlock?.language ?: tableBlock?.language ?: language
        val resolvedDirection = cellBlock?.direction ?: tableBlock?.direction
        return TextStyle(
            fontSize = resolvedSize.sp,
            lineHeight = (resolvedSize * resolvedLineHeight).sp,
            fontFamily = bookFamilyFor(fontBlock, settings, bookFonts)
                ?: fontFamilyFor(settings.font, settings.customFontPath),
            fontWeight = when {
                resolvedBold == true -> FontWeight.Bold
                resolvedBold == false -> FontWeight.Normal
                header -> FontWeight.Bold
                else -> null
            },
            fontStyle = when (resolvedItalic) {
                true -> FontStyle.Italic
                false -> FontStyle.Normal
                null -> FontStyle.Normal
            },
            hyphens = Hyphens.None,
            lineBreak = LineBreak.Paragraph,
            localeList = resolvedLanguage?.let { LocaleList(it) },
            textDirection = when (resolvedDirection) {
                BookTextDirection.LTR -> TextDirection.Ltr
                BookTextDirection.RTL -> TextDirection.Rtl
                BookTextDirection.AUTO, null -> autoTextDirection(resolvedLanguage)
            },
        )
    }

    private fun autoTextDirection(language: String?): TextDirection =
        if (LanguageTag.isRtl(language)) TextDirection.ContentOrRtl else TextDirection.ContentOrLtr
}
