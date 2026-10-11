package com.example.frogreader.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.frogreader.data.model.ContentElement

internal data class PublisherImageAppearance(val invert: Boolean, val background: Color?)

/** The book's dark media rules distinguish diagrams from realistic line art. */
internal fun publisherImageAppearance(
    image: ContentElement.Image,
    publisherMode: Boolean,
    surroundingBackground: Color,
    currentColor: Color,
    defaultInvert: Boolean,
): PublisherImageAppearance {
    val authored = image.darkModeInvert || image.darkModeBackgroundArgb != null ||
        image.darkModeBackgroundCurrentColor
    if (!publisherMode || !authored || surroundingBackground.luminance() >= 0.5f) {
        return PublisherImageAppearance(defaultInvert, null)
    }
    val background = when {
        image.darkModeBackgroundCurrentColor -> currentColor
        image.darkModeBackgroundArgb != null -> Color(image.darkModeBackgroundArgb)
        else -> null
    }
    return PublisherImageAppearance(image.darkModeInvert, background)
}
