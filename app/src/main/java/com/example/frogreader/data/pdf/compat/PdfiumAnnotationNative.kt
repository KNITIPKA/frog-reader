package com.example.frogreader.data.pdf.compat

import androidx.annotation.Keep

/** Thin annotation calls into the already loaded PDFium binary, inside the existing worker. */
@Keep
internal object PdfiumAnnotationNative {
    init { System.loadLibrary("frog_pdf_annotations") }

    @JvmStatic external fun removeOwned(pagePointer: Long, prefix: String)
    @JvmStatic external fun addHighlight(pagePointer: Long, name: String, contents: String, color: Int, quadPoints: FloatArray)
}
