package com.example.frogreader.data.diagnostics

import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.ReaderSettings

/** Only the last reader session is retained in memory; no book text or font objects. */
data class ReaderDiagnosticSnapshot(
    val bookId: String,
    val capturedAtMillis: Long,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val contentWidthPx: Int,
    val contentHeightPx: Int,
    val density: Float,
    val fontScale: Float,
    val baseFontSizePx: Float,
    val renderSettings: ReaderSettings,
    val theme: AppTheme,
    val imagesInverted: Boolean,
    val flatItemIndex: Int,
    val charOffset: Int?,
    val scrollOffsetPx: Int,
    val itemCount: Int,
    val chapterCount: Int,
    val language: String?,
    val pageProgression: String,
    val embeddedFontFamilies: List<String>,
    val calculatedPages: Int?,
    val paginationPartial: Boolean?,
)

class ReaderDiagnosticsTracker {
    @Volatile private var latest: ReaderDiagnosticSnapshot? = null

    fun record(snapshot: ReaderDiagnosticSnapshot) {
        if (snapshot.viewportWidthPx > 0 && snapshot.viewportHeightPx > 0) latest = snapshot
    }

    fun snapshotFor(bookId: String): ReaderDiagnosticSnapshot? = latest?.takeIf { it.bookId == bookId }
}
