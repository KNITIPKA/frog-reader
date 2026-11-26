package com.example.frogreader.data.pdf

import android.os.Build
import android.os.ext.SdkExtensions
import com.example.frogreader.data.model.DocumentLocation
import com.example.frogreader.data.model.PdfTextBounds
import kotlin.math.max
import kotlin.math.min

/** Platform support for the AndroidX PDF 1.0.0-beta01 engine. */
object PdfCapabilities {
    const val MIN_RENDER_SDK = 28
    const val MIN_TEXT_EXTENSION = 13

    fun canRender(): Boolean = canRender(Build.VERSION.SDK_INT)

    fun canRender(sdkInt: Int): Boolean = sdkInt >= MIN_RENDER_SDK

    fun canUseText(): Boolean {
        if (Build.VERSION.SDK_INT >= 35) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return canUseText(Build.VERSION.SDK_INT, SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S))
    }

    /** Text, search and document links arrived with Android 15 / S extension 13. */
    fun canUseText(sdkInt: Int, sExtensionVersion: Int): Boolean =
        sdkInt >= 35 || (sdkInt >= 31 && sExtensionVersion >= MIN_TEXT_EXTENSION)

    /** A centre anchor may legitimately lie outside a page, for example in a page gap. */
    fun normalizeLocation(location: DocumentLocation.Pdf, pageCount: Int): DocumentLocation.Pdf =
        location.copy(
            pageIndex = location.pageIndex.coerceIn(0, pageCount.coerceAtLeast(1) - 1),
            x = location.x.takeIf(Float::isFinite) ?: 0f,
            y = location.y.takeIf(Float::isFinite) ?: 0f,
            // Preserve page-fit zoom below the gesture minimum, but respect the engine maximum.
            zoom = location.zoom.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(25f) ?: 1f,
        )

    /** Invalid saved rectangles must not paint another page or overflow the drawing surface. */
    fun normalizeBounds(bounds: List<PdfTextBounds>, pageCount: Int): List<PdfTextBounds> =
        bounds.mapNotNull { rect ->
            if (rect.pageIndex !in 0 until pageCount ||
                !rect.left.isFinite() || !rect.top.isFinite() ||
                !rect.right.isFinite() || !rect.bottom.isFinite()
            ) return@mapNotNull null
            val left = min(rect.left, rect.right).coerceAtLeast(0f)
            val top = min(rect.top, rect.bottom).coerceAtLeast(0f)
            val right = max(rect.left, rect.right).coerceAtLeast(0f)
            val bottom = max(rect.top, rect.bottom).coerceAtLeast(0f)
            if (right <= left || bottom <= top) null
            else rect.copy(left = left, top = top, right = right, bottom = bottom)
        }

    /** Opening a page is not completion: the offset within its content contributes separately. */
    fun progressFraction(pageIndex: Int, pageCount: Int, pageOffsetFraction: Float = 0f): Float {
        if (pageCount <= 0) return 0f
        val page = pageIndex.coerceIn(0, pageCount - 1)
        val offset = pageOffsetFraction.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        return ((page.toDouble() + offset) / pageCount).toFloat().coerceIn(0f, 1f)
    }
}
