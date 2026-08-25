package com.example.frogreader.parser

import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.LINK_TAG
import com.example.frogreader.data.parser.EpubParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Contents page of a real trade EPUB: tight spacing from the book's own
 * `.5em` margins, a letter-sized ornament, and entries that jump.
 */
class ContentsPageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun book(): File {
        val local = File(
            "/Users/frog/AndroidStudioProjects/FrogReader/.testbooks/Irresistible_en.epub",
        )
        return local
    }

    @Test
    fun `contents entries are compact, linked, and the ornament stays small`() {
        val file = book()
        assumeTrue(file.exists() && file.canRead())

        val content = EpubParser.parseContent(file, tempFolder.newFolder())
        val chapter = content.chapters.first { ch ->
            ch.elements.any { it is ContentElement.Heading && it.text.contains("CONTENTS", true) }
        }

        // ".5em" bottom margins must parse as 0.5em, not 5em (the leading-dot
        // number bug made every gap ten times too wide and clamped to 3em).
        val entry = chapter.elements.filterIsInstance<ContentElement.Paragraph>()
            .first { it.text.text.startsWith("Also by Adam Alter") }
        assertEquals(0.5f, entry.block!!.spaceAfterEm, 0.001f)
        // The stylesheet uses physical `margin-left`, not logical
        // `margin-inline-start`; it must stay on the left in an RTL layout.
        assertEquals(0.0f, entry.block!!.indentStartEm, 0.001f)
        assertEquals(1.0f, entry.block!!.indentLeftEm, 0.001f)

        // The ornament is `height: 1em` — it must not fill the column.
        val ornament = chapter.elements.filterIsInstance<ContentElement.Image>().first()
        assertEquals(1f, ornament.heightEm!!, 0.001f)

        // Every entry links to the file it names, and the link resolves.
        val links = chapter.elements.filterIsInstance<ContentElement.Paragraph>()
            .flatMap { it.text.getStringAnnotations(LINK_TAG, 0, it.text.length) }
        assertTrue("links: ${links.size}", links.size >= 20)
        assertTrue(links.all { it.item in content.linkTargets })

        // "1. The Rise of Behavioral Addiction" points at its own chapter.
        val rise = chapter.elements.filterIsInstance<ContentElement.Paragraph>()
            .first { it.text.text.contains("The Rise of Behavioral Addiction") }
        val target = rise.text.getStringAnnotations(LINK_TAG, 0, rise.text.length).single()
        val (chapterIndex, elementIndex) = content.linkTargets.getValue(target.item)
        val landing = content.chapters[chapterIndex].elements.drop(elementIndex)
        assertTrue(
            "landed on: ${landing.firstOrNull()}",
            landing.any {
                it is ContentElement.Heading && it.text.contains("Rise of Behavioral Addiction")
            },
        )
    }

    @Test
    fun `endnote backlinks navigate instead of becoming popup notes`() {
        val file = book()
        assumeTrue(file.exists() && file.canRead())

        val content = EpubParser.parseContent(file, tempFolder.newFolder())
        // This EPUB does not put noteref links in the prose. Its Notes chapter
        // instead links each bibliography paragraph *back* to the cited phrase
        // (`…xhtml#EndnotePhraseInTextN`). Treating every fragment link as a
        // footnote used to turn more than a hundred ordinary backlinks into
        // popup notes and made real cross-references impossible.
        val backlinks = content.chapters
            .flatMap { it.elements }
            .filterIsInstance<ContentElement.Paragraph>()
            .flatMap { it.text.getStringAnnotations(LINK_TAG, 0, it.text.length) }
            .filter { "#EndnotePhraseInText" in it.item }
        assertTrue("backlinks: ${backlinks.size}", backlinks.size > 100)
        assertTrue(backlinks.all { it.item in content.linkTargets })
        assertTrue(backlinks.none { it.item in content.notes })
    }
}
