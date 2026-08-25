package com.example.frogreader.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withAnnotation
import androidx.compose.ui.text.withStyle
import com.example.frogreader.data.model.BlockStyle
import com.example.frogreader.ui.reader.contrastRatio
import com.example.frogreader.ui.reader.publisherColorPair
import com.example.frogreader.ui.reader.withPublisherColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublisherColorsTest {

    @Test
    fun `publisher toggle off ignores author block colors`() {
        val pair = publisherColorPair(
            BlockStyle(
                foregroundColorArgb = 0xffff0000.toInt(),
                backgroundColorArgb = 0xff000080.toInt(),
            ),
            enabled = false,
            defaultForeground = Color.Black,
            surroundingBackground = Color.White,
        )

        assertEquals(Color.Black, pair.foreground)
        assertNull(pair.background)
        assertEquals(Color.White, pair.effectiveBackground)
    }

    @Test
    fun `complete author pair is honored verbatim`() {
        val pair = publisherColorPair(
            BlockStyle(
                foregroundColorArgb = 0xff777777.toInt(),
                backgroundColorArgb = 0xff888888.toInt(),
            ),
            enabled = true,
            defaultForeground = Color.Black,
            surroundingBackground = Color.White,
        )

        assertEquals(0xff777777.toInt(), pair.foreground.toArgb())
        assertEquals(0xff888888.toInt(), pair.background?.toArgb())
    }

    @Test
    fun `a lone author color is adjusted only when contrast is unsafe`() {
        val unsafeForeground = publisherColorPair(
            foregroundArgb = 0xffffffff.toInt(),
            backgroundArgb = null,
            enabled = true,
            defaultForeground = Color.Black,
            surroundingBackground = Color.White,
        )
        assertTrue(contrastRatio(unsafeForeground.foreground, Color.White) >= 4.5f)
        assertTrue(unsafeForeground.foreground != Color.White)

        val unsafeBackground = publisherColorPair(
            foregroundArgb = null,
            backgroundArgb = 0xff111111.toInt(),
            enabled = true,
            defaultForeground = Color.Black,
            surroundingBackground = Color.White,
        )
        assertTrue(
            contrastRatio(
                unsafeBackground.foreground,
                unsafeBackground.effectiveBackground,
            ) >= 4.5f,
        )
    }

    @Test
    fun `span gating preserves annotations and geometry styles`() {
        val raw = buildAnnotatedString {
            withAnnotation("target", "note") {
                withStyle(
                    SpanStyle(
                        color = Color.Red,
                        background = Color.Yellow,
                        fontWeight = FontWeight.Bold,
                    ),
                ) {
                    append("styled")
                }
            }
        }
        val base = publisherColorPair(
            block = null,
            enabled = true,
            defaultForeground = Color.Black,
            surroundingBackground = Color.White,
        )

        val disabled = raw.withPublisherColors(enabled = false, base = base)
        val disabledSpan = disabled.spanStyles.single().item
        assertEquals(Color.Unspecified, disabledSpan.color)
        assertEquals(Color.Unspecified, disabledSpan.background)
        assertEquals(FontWeight.Bold, disabledSpan.fontWeight)
        assertEquals("note", disabled.getStringAnnotations("target", 0, disabled.length).single().item)

        val enabled = raw.withPublisherColors(enabled = true, base = base)
        assertEquals(Color.Red, enabled.spanStyles.single().item.color)
        assertEquals(Color.Yellow, enabled.spanStyles.single().item.background)
    }

    @Test
    fun `overlapping foreground and background count as one deliberate author pair`() {
        val raw = buildAnnotatedString {
            withStyle(SpanStyle(background = Color.Black)) {
                withStyle(SpanStyle(color = Color.Black)) { append("inner") }
            }
        }
        val base = publisherColorPair(
            block = null,
            enabled = true,
            defaultForeground = Color.Black,
            surroundingBackground = Color.White,
        )

        val enabled = raw.withPublisherColors(enabled = true, base = base)
        // Fidelity wins for a complete pair, even when it is intentionally
        // low-contrast and the two declarations live on nested spans.
        assertEquals(raw.spanStyles, enabled.spanStyles)
    }
}
