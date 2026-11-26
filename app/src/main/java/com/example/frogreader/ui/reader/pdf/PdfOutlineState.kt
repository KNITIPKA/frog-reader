package com.example.frogreader.ui.reader.pdf

import com.example.frogreader.data.model.PdfOutline

/** Kept separate from document loading: contents never gate the first PDF page. */
internal sealed interface PdfOutlineState {
    data object Idle : PdfOutlineState
    data object Loading : PdfOutlineState
    data class Ready(val outline: PdfOutline) : PdfOutlineState
    data object Error : PdfOutlineState
}
