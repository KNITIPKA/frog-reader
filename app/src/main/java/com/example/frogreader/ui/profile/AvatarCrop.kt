package com.example.frogreader.ui.profile

import kotlin.math.roundToInt

/** Image-relative coordinates: independent of preview size and device rotation. */
internal data class AvatarCrop(
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val zoom: Float = 1f,
) {
    fun constrained(width: Int, height: Int): AvatarCrop {
        require(width > 0 && height > 0)
        val scale = zoom.coerceIn(1f, 5f)
        val halfSide = minOf(width, height) / scale / 2f
        return AvatarCrop(
            centerX.coerceIn(halfSide / width, 1f - halfSide / width),
            centerY.coerceIn(halfSide / height, 1f - halfSide / height),
            scale,
        )
    }

    fun transform(width: Int, height: Int, viewport: Float, dx: Float, dy: Float, zoomChange: Float): AvatarCrop {
        if (viewport <= 0f) return this
        val scaled = copy(zoom = zoom * zoomChange).constrained(width, height)
        val imagePixelsPerPixel = minOf(width, height) / scaled.zoom / viewport
        return scaled.copy(
            centerX = scaled.centerX - dx * imagePixelsPerPixel / width,
            centerY = scaled.centerY - dy * imagePixelsPerPixel / height,
        ).constrained(width, height)
    }

    /** The same integer source rectangle is used by preview and export. */
    fun pixels(width: Int, height: Int): AvatarCropPixels {
        val crop = constrained(width, height)
        val side = (minOf(width, height) / crop.zoom).roundToInt().coerceIn(1, minOf(width, height))
        return AvatarCropPixels(
            (crop.centerX * width - side / 2f).roundToInt().coerceIn(0, width - side),
            (crop.centerY * height - side / 2f).roundToInt().coerceIn(0, height - side),
            side,
        )
    }
}

internal data class AvatarCropPixels(val left: Int, val top: Int, val side: Int)
