@file:Suppress("RestrictedApi", "DEPRECATION")
@file:OptIn(androidx.pdf.ExperimentalPdfApi::class)

package com.example.frogreader.ui.reader.pdf

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Parcelable
import android.util.SparseArray
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.pdf.view.PdfView
import com.example.frogreader.R
import com.example.frogreader.ui.reader.ReaderColors

/** Bridge the Compose reader palette to the embedded View without changing the activity theme. */
@Composable
internal fun PdfViewerStyle(colors: ReaderColors, document: androidx.pdf.PdfDocument? = null, content: @Composable (attachDocument: Boolean) -> Unit) {
    val context = LocalContext.current
    val composeView = LocalView.current
    val density = LocalDensity.current
    val controlsInsets = WindowInsets.pdfControlsSafeInsets
    val topInset = controlsInsets.getTop(density)
    val bottomInset = controlsInsets.getBottom(density)
    val currentTopInset by rememberUpdatedState(topInset)
    val currentBottomInset by rememberUpdatedState(bottomInset)
    val saved = remember { PdfViewerStyleState() }
    val styled = remember(context, colors) {
        // The native thumb caches reader colors at construction. Recreate it on palette changes,
        // retaining the View's own layout/zoom/selection state rather than reopening the document.
        if (saved.initialized) composeView.findPdfView(document)?.let { viewer ->
            saved.viewId = viewer.id
            saved.hierarchy = SparseArray<Parcelable>().also(viewer::saveHierarchyState)
        }
        saved.initialized = true
        PdfViewerContext(context, colors, { currentTopInset }, { currentBottomInset })
    }
    key(colors) {
        var attachDocument by remember { mutableStateOf(saved.hierarchy == null) }
        CompositionLocalProvider(LocalContext provides styled) { content(attachDocument) }
        SideEffect {
            saved.hierarchy?.let { hierarchy ->
                composeView.findPdfViewMatching { it.context === styled }?.let { viewer ->
                    // Keep the new composable's remembered ID valid for later activity restores.
                    val rekeyed = SparseArray<Parcelable>()
                    hierarchy[saved.viewId]?.let { rekeyed.put(viewer.id, it) }
                    viewer.restoreHierarchyState(rekeyed)
                    saved.hierarchy = null
                    // Restore before document attachment: beta01 creates its layout/selection
                    // collectors in onDocumentSet, and they must observe the restored managers.
                    attachDocument = true
                }
            }
        }
    }
}

private class PdfViewerStyleState {
    var initialized = false
    var viewId = View.NO_ID
    var hierarchy: SparseArray<Parcelable>? = null
}

private class PdfViewerContext(base: Context, colors: ReaderColors, topInset: () -> Int, bottomInset: () -> Int) :
    ContextThemeWrapper(base, R.style.ThemeOverlay_FrogReader_PdfViewer) {
    private val readerResources = PdfViewerResources(base.resources, colors, topInset, bottomInset)
    override fun getResources(): Resources = readerResources
}

/**
 * AndroidX beta01 loads its drawables via Resources and its label ink via MaterialColors.
 * Override those public resource entry points so both use the live Compose palette on API 28+.
 * No activity/global resources are modified, including when Material You is enabled.
 */
private class PdfViewerResources(base: Resources, private val colors: ReaderColors,
    private val topInset: () -> Int, private val bottomInset: () -> Int) :
    Resources(base.assets, base.displayMetrics, base.configuration) {
    private fun readerColor(id: Int): Int? = when (id) {
        R.color.pdf_viewer_on_chrome -> colors.onChrome.toArgb()
        R.color.pdf_viewer_chrome -> colors.chrome.toArgb()
        R.color.pdf_viewer_accent -> colors.accent.toArgb()
        else -> null
    }

    override fun getColor(id: Int, theme: Theme?): Int = readerColor(id) ?: super.getColor(id, theme)
    override fun getColorStateList(id: Int, theme: Theme?): ColorStateList =
        readerColor(id)?.let(ColorStateList::valueOf) ?: super.getColorStateList(id, theme)

    // Keep the thumb's entire travel outside status/navigation bars without padding the PDF.
    override fun getDimension(id: Int): Float = when (id) {
        androidx.pdf.R.dimen.scroller_top_margin -> maxOf(super.getDimension(id), topInset() + 8 * displayMetrics.density)
        androidx.pdf.R.dimen.scroller_bottom_margin -> maxOf(super.getDimension(id), bottomInset() + 8 * displayMetrics.density)
        else -> super.getDimension(id)
    }

    override fun getDrawable(id: Int, theme: Theme?): Drawable = when (id) {
        androidx.pdf.R.drawable.fast_scroll_thumb_drawable -> PdfScrollThumb(colors, displayMetrics.density)
        else -> super.getDrawable(id, theme)
    }
}

/** Give the narrow pill a full-sized touch target. */
private class PdfScrollThumb(colors: ReaderColors, private val density: Float) : Drawable() {
    private val fill = colors.chrome.toArgb()
    private val grip = colors.onChromeVariant.toArgb()
    private val edge = colors.onChromeVariant.copy(alpha = 0.32f).compositeOver(colors.chrome).toArgb()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var opacity = 255
    private var filter: ColorFilter? = null
    override fun getIntrinsicWidth(): Int = (48 * density).toInt()
    override fun getIntrinsicHeight(): Int = (56 * density).toInt()
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getAlpha(): Int = opacity
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { filter = colorFilter; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        paint.colorFilter = filter
        paint.style = Paint.Style.FILL
        paint.color = fill
        paint.alpha = opacity
        val right = bounds.right - 6 * density
        val left = right - 26 * density
        val top = bounds.top + 2 * density
        val bottom = bounds.bottom - 2 * density
        val radius = 13 * density
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = edge
        paint.alpha = opacity
        canvas.drawRoundRect(left + density / 2, top + density / 2,
            right - density / 2, bottom - density / 2, radius - density / 2, radius - density / 2, paint)

        paint.strokeWidth = 1.5f * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = grip
        paint.alpha = opacity
        val centerX = (left + right) / 2
        val centerY = (top + bottom) / 2
        for (row in -1..1) {
            val y = centerY + row * 4 * density
            canvas.drawLine(centerX - 4 * density, y, centerX + 4 * density, y, paint)
        }
    }
}

internal fun View.findPdfView(document: androidx.pdf.PdfDocument? = null): PdfView? =
    if (document != null) findPdfViewMatching { it.pdfDocument === document }
    else findPdfViewMatching { view ->
        val visible = android.graphics.Rect()
        view.isShown && view.getGlobalVisibleRect(visible) && visible.width() >= view.width / 2
    }

private fun View.findPdfViewMatching(predicate: (PdfView) -> Boolean): PdfView? {
    if (this is PdfView && predicate(this)) return this
    if (this is ViewGroup) for (index in 0 until childCount) {
        getChildAt(index).findPdfViewMatching(predicate)?.let { return it }
    }
    return null
}

