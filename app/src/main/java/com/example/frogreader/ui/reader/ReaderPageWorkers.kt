package com.example.frogreader.ui.reader

import androidx.compose.runtime.staticCompositionLocalOf

internal fun defaultReaderPageWorkerCount(): Int =
    defaultReaderPageWorkerCount(Runtime.getRuntime().availableProcessors())

internal fun defaultReaderPageWorkerCount(availableProcessors: Int): Int =
    (availableProcessors - 2).coerceIn(2, MAX_CHAPTER_LAYOUT_WORKERS)

internal val LocalReaderPageWorkerCount = staticCompositionLocalOf {
    defaultReaderPageWorkerCount()
}
