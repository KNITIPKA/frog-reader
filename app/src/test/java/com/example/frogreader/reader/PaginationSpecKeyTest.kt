package com.example.frogreader.ui.reader

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.frogreader.data.PageMargins
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.PageTurnAnimation
import com.example.frogreader.data.model.BookFont
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PaginationSpecKeyTest {

    @Test
    fun `Auto manual and family membership have distinct pagination identities`() {
        fun key(auto: Boolean, paths: List<String>) = PaginationSpec(
            contentWidthPx = 800, contentHeightPx = 1_200, density = Density(2f), fontSize = 18f,
            settings = ReaderSettings(customFontPath = "/fonts/regular.ttf", customFontAuto = auto,
                customFontFamilyPaths = paths),
        ).key
        val regular = listOf("/fonts/regular.ttf")
        val complete = regular + "/fonts/italic.ttf"
        assertNotEquals(key(false, regular), key(true, regular))
        assertNotEquals(key(true, regular), key(true, complete))
        assertEquals(key(true, complete), key(true, complete.reversed() + complete.first()))
    }

    @Test
    fun `instances from one font file have different page cache keys`() {
        fun key(axes: Map<String, Float>) = PaginationSpec(
            contentWidthPx = 800, contentHeightPx = 1_200, density = Density(2f), fontSize = 18f,
            settings = ReaderSettings(customFontPath = "/fonts/variable.ttf", customFontAxes = axes),
        ).key
        assertNotEquals(key(mapOf("wght" to 400f, "wdth" to 100f)), key(mapOf("wght" to 700f, "wdth" to 100f)))
        assertNotEquals(key(mapOf("wght" to 400f, "wdth" to 100f)), key(mapOf("wght" to 400f, "wdth" to 75f)))
        assertEquals(key(mapOf("wdth" to 75f, "wght" to 700f)), key(mapOf("wght" to 700f, "wdth" to 75f)))
    }

    @Test
    fun `numeric margins invalidate cached pages through the measured column width`() {
        val density = Density(2f)
        fun key(settings: ReaderSettings): String = with(density) {
            PaginationSpec(
                contentWidthPx = (360.dp - ReaderMetrics.horizontalPadding(settings) * 2).roundToPx(),
                contentHeightPx = 1_200,
                density = density,
                fontSize = 18f,
                settings = settings,
            ).key
        }

        assertNotEquals(key(ReaderSettings(pageMarginDp = 8)), key(ReaderSettings(pageMarginDp = 48)))
        assertEquals(
            key(ReaderSettings(pageMargins = PageMargins.NORMAL)),
            key(ReaderSettings(pageMarginDp = 20)),
        )
    }

    @Test
    fun `counter visibility and mode preserve measured pages`() {
        val keys = listOf(false, true).flatMap { visible ->
            listOf(false, true).map { chapterCounter ->
                PaginationSpec(
                    contentWidthPx = 800, contentHeightPx = 1_200,
                    density = Density(2f), fontSize = 18f,
                    settings = ReaderSettings(showPageCounter = visible, showChapterPagesLeft = chapterCounter),
                ).key
            }
        }
        assertEquals(1, keys.distinct().size)
    }

    @Test
    fun `switching page turn animation preserves measured pages`() {
        val keys = PageTurnAnimation.entries.map { animation ->
            PaginationSpec(
                contentWidthPx = 800,
                contentHeightPx = 1_200,
                density = Density(2f),
                settings = ReaderSettings(pageTurnAnimation = animation),
                fontSize = 18f,
            ).key
        }
        assertEquals(1, keys.distinct().size)
    }

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `density and system font scale invalidate page layout`() {
        fun key(density: Float, fontScale: Float) = PaginationSpec(
            contentWidthPx = 800,
            contentHeightPx = 1_200,
            density = Density(density = density, fontScale = fontScale),
            settings = ReaderSettings(),
            fontSize = 18f,
        ).key

        val baseline = key(density = 2f, fontScale = 1f)
        assertNotEquals(baseline, key(density = 2.25f, fontScale = 1f))
        assertNotEquals(baseline, key(density = 2f, fontScale = 1.2f))
    }

    @Test
    fun `changing embedded font bytes changes signature and pagination key`() {
        val file = temp.newFile("publisher.ttf")
        val face = BookFont("publisher serif", file.absolutePath, bold = false, italic = false)
        file.writeBytes(byteArrayOf(1, 2, 3))
        val firstSignature = embeddedFontSignature(listOf(face))
        val firstKey = keyForEmbeddedFonts(firstSignature)

        file.writeBytes(byteArrayOf(1, 2, 4))
        val secondSignature = embeddedFontSignature(listOf(face))
        val secondKey = keyForEmbeddedFonts(secondSignature)

        assertNotEquals(firstSignature, secondSignature)
        assertNotEquals(firstKey, secondKey)
    }

    @Test
    fun `font signature is stable across extraction paths and list order`() {
        val first = temp.newFile("first.ttf").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val second = temp.newFile("second.ttf").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val bold = temp.newFile("bold.ttf").apply { writeBytes(byteArrayOf(6, 5, 4)) }

        val one = embeddedFontSignature(
            listOf(
                BookFont("publisher serif", first.absolutePath, bold = false, italic = false),
                BookFont("publisher serif", bold.absolutePath, bold = true, italic = false),
            ),
        )
        val two = embeddedFontSignature(
            listOf(
                BookFont("publisher serif", bold.absolutePath, bold = true, italic = false),
                BookFont("publisher serif", second.absolutePath, bold = false, italic = false),
            ),
        )

        assertEquals(one, two)
    }

    @Test
    fun `missing font state differs from a present empty font`() {
        val missing = temp.root.resolve("missing.ttf")
        val empty = temp.newFile("empty.ttf")

        assertNotEquals(
            embeddedFontSignature(
                listOf(BookFont("publisher serif", missing.absolutePath, false, false)),
            ),
            embeddedFontSignature(
                listOf(BookFont("publisher serif", empty.absolutePath, false, false)),
            ),
        )
    }

    private fun keyForEmbeddedFonts(signature: String): String = PaginationSpec(
        contentWidthPx = 800,
        contentHeightPx = 1_200,
        density = Density(density = 2f, fontScale = 1f),
        settings = ReaderSettings(),
        fontSize = 18f,
        embeddedFontSignature = signature,
    ).key
}
