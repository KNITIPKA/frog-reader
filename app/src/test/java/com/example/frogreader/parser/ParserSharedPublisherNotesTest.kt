package com.example.frogreader.parser

import androidx.compose.ui.text.AnnotatedString
import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.PublisherBoxSpan
import com.example.frogreader.data.model.PublisherBoxStyle
import com.example.frogreader.data.parser.buildNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserSharedPublisherNotesTest {

    @Test
    fun `legacy note fallback slices and rebases publisher boxes`() {
        val chapter = Chapter(
            title = "Notes",
            elements = listOf("Before", "First note", "Second note").map {
                ContentElement.Paragraph(AnnotatedString(it))
            },
            publisherBoxes = listOf(
                PublisherBoxSpan(
                    id = "panel",
                    startElement = 0,
                    endElementExclusive = 3,
                    style = PublisherBoxStyle(backgroundColorArgb = 0xffeeeeee.toInt()),
                ),
            ),
        )

        val note = buildNotes(
            chapters = listOf(chapter),
            anchorLocations = mapOf("#note" to (0 to 1)),
            linkTargets = setOf("#note"),
        ).getValue("#note")

        assertEquals(listOf("First note", "Second note"), note.elements.map {
            (it as ContentElement.Paragraph).text.text
        })
        val box = note.publisherBoxes.single()
        assertEquals(0, box.startElement)
        assertEquals(2, box.endElementExclusive)
        assertFalse(box.drawStart)
        assertTrue(box.drawEnd)
    }
}
