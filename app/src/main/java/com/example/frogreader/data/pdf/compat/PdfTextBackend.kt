package com.example.frogreader.data.pdf.compat

import java.io.Closeable

/** Text-layer operations only. Implementations keep native parsing outside the application process. */
internal interface PdfTextBackend : Closeable {
    val pageCount: Int
    suspend fun pageContent(page: Int, width: Int, height: Int): PdfTextPageData
    suspend fun selection(page: Int, width: Int, height: Int, start: PdfTextBoundary, stop: PdfTextBoundary): PdfTextSelectionData?
    suspend fun search(page: Int, width: Int, height: Int, query: String): List<PdfTextMatchData>
}
