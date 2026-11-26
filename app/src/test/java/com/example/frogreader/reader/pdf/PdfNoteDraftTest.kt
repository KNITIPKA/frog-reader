package com.example.frogreader.reader.pdf

import androidx.compose.runtime.saveable.SaverScope
import com.example.frogreader.data.model.PdfTextBounds
import com.example.frogreader.ui.reader.pdf.PdfNoteDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PdfNoteDraftTest {
    private val bundleValues = object : SaverScope {
        override fun canBeSaved(value: Any) = value is String || value is Int || value is Float
    }

    @Test fun `new note restores selected pages typed text and stable retry identity`() {
        val draft = PdfNoteDraft(
            text = "A passage spanning\ntwo PDF pages.",
            bounds = listOf(
                PdfTextBounds(75, 20.5f, 600.25f, 310f, 615.75f),
                PdfTextBounds(76, 20.5f, 30.25f, 170f, 45.75f),
            ),
            note = "Проверить перевод\nKeep this for the introduction.",
            saveId = "stable-in-flight-note",
        )
        assertEquals(draft, restore(draft))
    }

    @Test fun `editing a note restores its existing quote identity`() {
        val draft = PdfNoteDraft(
            text = "Selected text", bounds = listOf(PdfTextBounds(4, 1f, 2f, 3f, 4f)),
            quoteId = "existing-note", note = "Edited thought", saveId = "edit-session",
        )
        assertEquals(draft, restore(draft))
    }

    @Test fun `no open note contributes no saved draft`() {
        val saved = with(PdfNoteDraft.Saver) { bundleValues.save(null) }
        assertNull(saved)
    }

    private fun restore(draft: PdfNoteDraft): PdfNoteDraft? {
        val saved = with(PdfNoteDraft.Saver) { bundleValues.save(draft) }
            ?: error("A nonempty note draft must be saveable")
        return PdfNoteDraft.Saver.restore(saved)
    }
}
