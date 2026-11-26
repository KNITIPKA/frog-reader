package com.example.frogreader.data.pdf.compat

import android.os.Bundle
import com.example.frogreader.data.model.PdfOutline
import com.example.frogreader.data.model.PdfOutlineEntry
import java.io.IOException

/** Primitive arrays keep a complete bounded outline comfortably below Binder's shared buffer. */
internal object PdfOutlineProtocol {
    private const val KEY_TITLES = "titles"
    private const val KEY_PAGES = "pages"
    private const val KEY_DEPTHS = "depths"
    private const val KEY_TRUNCATED = "truncated"

    fun encode(outline: PdfOutline, pageCount: Int): Bundle {
        if (!outline.isValidFor(pageCount)) throw IOException("Invalid PDF outline")
        return Bundle().apply {
            putStringArray(KEY_TITLES, outline.entries.map { it.title }.toTypedArray())
            putIntArray(KEY_PAGES, IntArray(outline.entries.size) { outline.entries[it].pageIndex ?: -1 })
            putIntArray(KEY_DEPTHS, IntArray(outline.entries.size) { outline.entries[it].depth })
            putBoolean(KEY_TRUNCATED, outline.truncated)
        }
    }

    fun decode(bundle: Bundle, pageCount: Int): PdfOutline {
        if (bundle.size() != 4 || !bundle.containsKey(KEY_TRUNCATED)) {
            throw IOException("Invalid PDF outline response")
        }
        val titles = bundle.getStringArray(KEY_TITLES) ?: throw IOException("Missing PDF outline titles")
        val pages = bundle.getIntArray(KEY_PAGES) ?: throw IOException("Missing PDF outline pages")
        val depths = bundle.getIntArray(KEY_DEPTHS) ?: throw IOException("Missing PDF outline depths")
        if (titles.size > PdfOutline.MAX_ENTRIES || pages.size != titles.size || depths.size != titles.size ||
            pages.any { it < -1 }
        ) throw IOException("Invalid PDF outline arrays")
        // Check size before building entries, including responses from an abandoned worker.
        var characters = 0
        titles.forEach { title ->
            if (title == null || title.length > PdfOutline.MAX_TITLE_CHARS) {
                throw IOException("Invalid PDF outline title")
            }
            characters += title.length
            if (characters > PdfOutline.MAX_TOTAL_TITLE_CHARS) throw IOException("PDF outline response is too large")
        }
        return PdfOutline(
            entries = titles.mapIndexed { index, title ->
                PdfOutlineEntry(title, pages[index].takeIf { it >= 0 }, depths[index])
            },
            truncated = bundle.getBoolean(KEY_TRUNCATED),
        ).also { if (!it.isValidFor(pageCount)) throw IOException("Invalid PDF outline response") }
    }
}
