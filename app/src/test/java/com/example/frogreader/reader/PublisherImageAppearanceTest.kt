package com.example.frogreader.reader

import androidx.compose.ui.graphics.Color
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.PublisherBorderSide
import com.example.frogreader.data.model.PublisherBorderStyle
import com.example.frogreader.ui.reader.publisherBorderColor
import com.example.frogreader.ui.reader.publisherImageAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublisherImageAppearanceTest {
    @Test
    fun `dark diagram inversion and realistic illustration backdrop follow separate publisher rules`() {
        val diagram = ContentElement.Image("diagram.svg", darkModeInvert = true)
        val realistic = ContentElement.Image("tenniel.svg", darkModeBackgroundCurrentColor = true)
        val diagramLook = publisherImageAppearance(diagram, true, Color.Black, Color.White, false)
        assertTrue(diagramLook.invert)
        assertNull(diagramLook.background)
        val realisticLook = publisherImageAppearance(realistic, true, Color.Black, Color.White, false)
        assertFalse(realisticLook.invert)
        assertEquals(Color.White, realisticLook.background)
        val realisticReaderTypography = publisherImageAppearance(realistic, false, Color.Black, Color.White, false)
        assertFalse(realisticReaderTypography.invert)
        assertEquals(Color.White, realisticReaderTypography.background)
    }

    @Test
    fun `light surfaces and reader typography preserve user image preference`() {
        val image = ContentElement.Image("diagram.svg", darkModeInvert = true,
            darkModeBackgroundCurrentColor = true)
        val light = publisherImageAppearance(image, true, Color.White, Color.Black, false)
        assertFalse(light.invert)
        assertNull(light.background)
        val reader = publisherImageAppearance(image, false, Color.Black, Color.White, true)
        assertTrue(reader.invert)
        assertNull(reader.background)
        val publisherInverted = publisherImageAppearance(image, true, Color.Black, Color.White, true)
        assertTrue(publisherInverted.invert)
        assertNull(publisherInverted.background)
        val unstyled = publisherImageAppearance(ContentElement.Image("photo.jpg"), true,
            Color.Black, Color.White, true)
        assertTrue(unstyled.invert)
    }

    @Test
    fun `current color rules use reader ink while explicit border color remains authored`() {
        val border = PublisherBorderSide(0.1f, 0xff000000.toInt(), PublisherBorderStyle.SOLID,
            usesCurrentColor = true)
        assertEquals(Color.White, publisherBorderColor(border, Color.White))
        assertEquals(Color.Black, publisherBorderColor(border.copy(usesCurrentColor = false), Color.White))
        assertEquals(Color.Black, publisherBorderColor(border, null))
    }
}
