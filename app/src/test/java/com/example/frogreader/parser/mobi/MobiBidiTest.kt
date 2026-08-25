package com.example.frogreader.parser.mobi

import com.example.frogreader.data.model.BIDI_TAG
import com.example.frogreader.data.model.BookTextDirection
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.InlineBidiMode
import com.example.frogreader.data.parser.mobi.MobiParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MobiBidiTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `mobi6 and kf8 retain rtl blocks isolates overrides and table cells`() {
        val fragment = """
            <p lang="ar" dir="rtl">فقرة عربية 2026 (ABC).</p>
            <p dir="rtl">اسم <bdi>Frog-42</bdi> <bdo dir="ltr">ABC 123</bdo>.</p>
            <table dir="rtl"><tr><td>خلية</td><td dir="ltr">SKU-12</td></tr></table>
        """.trimIndent()
        val mobi6 = tempFolder.newFile("rtl.mobi")
        MobiBuilder.buildMobi6(mobi6, "<html><body>$fragment</body></html>")
        val kf8 = tempFolder.newFile("rtl.azw3")
        MobiBuilder.buildKf8(
            kf8,
            MobiBuilder.Kf8Spec(
                skeletons = listOf("<html><body></body></html>"),
                fragments = listOf(listOf(fragment)),
                css = "",
            ),
        )

        listOf(mobi6, kf8).forEach { file ->
            val elements = MobiParser.parseContent(file, tempFolder.newFolder())
                .chapters.flatMap { it.elements }
            val paragraphs = elements.filterIsInstance<ContentElement.Paragraph>()
            assertEquals(file.name, BookTextDirection.RTL, paragraphs[0].block?.direction)
            assertEquals(file.name, "ar", paragraphs[0].block?.language)
            val mixed = paragraphs.single { "Frog-42" in it.text.text }
            assertFalse(file.name, mixed.text.text.any(::isBidiControl))
            assertEquals(
                file.name,
                setOf(InlineBidiMode.ISOLATE_AUTO.name, InlineBidiMode.OVERRIDE_LTR.name),
                mixed.text.getStringAnnotations(BIDI_TAG, 0, mixed.text.length)
                    .map { it.item }.toSet(),
            )
            val table = elements.filterIsInstance<ContentElement.Table>().single()
            assertEquals(file.name, BookTextDirection.RTL, table.block?.direction)
            assertEquals(
                file.name,
                BookTextDirection.LTR,
                table.rows.single().cells[1].block?.direction,
            )
        }
    }

    private fun isBidiControl(char: Char): Boolean = char in setOf(
        '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
        '\u2066', '\u2067', '\u2068', '\u2069',
    )
}
