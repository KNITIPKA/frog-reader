@file:Suppress("RestrictedApi")

package com.example.frogreader.ui.reader.pdf

import android.annotation.SuppressLint
import androidx.pdf.PdfDocument
import androidx.pdf.PdfFeature

/** Query the document engine, since the compatibility engine also supplies a genuine text layer. */
@SuppressLint("RestrictedApi")
internal fun PdfDocument.supportsTextSearch(): Boolean = isFeatureSupported(PdfFeature.SEARCH)

@SuppressLint("RestrictedApi")
internal fun PdfDocument.supportsTextSelection(): Boolean = isFeatureSupported(PdfFeature.TEXT_SELECTION)
