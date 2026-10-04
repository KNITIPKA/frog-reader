package com.example.frogreader.ui.profile

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Only viewport coordinates change during a gesture; persistence happens once, on release. */
internal class ReadingQueueDragState(val list: LazyListState) {
    var id by mutableStateOf<String?>(null)
        private set
    var top by mutableFloatStateOf(0f)
        private set
    private var height = 0
    private var pointerY = 0f

    fun start(key: String, handleY: Float, visualTop: Float? = null): Boolean {
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return false
        id = key
        // A new gesture may interrupt this card's return animation.
        top = visualTop ?: item.offset.toFloat()
        height = item.size
        pointerY = handleY + top - item.offset
        return true
    }

    fun drag(delta: Float) {
        top += delta
        pointerY += delta
    }

    fun translation(key: String): Float {
        if (id != key) return 0f
        return top - (list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset ?: top.toInt())
    }

    fun stop() { id = null }

    /** Wait for the previous move's layout before asking for the next swap. */
    fun targetIndex(keys: List<String>, leadingItems: Int = 0): Int? {
        val key = id ?: return null
        val from = keys.indexOf(key)
        val visible = list.layoutInfo.visibleItemsInfo
        val item = visible.firstOrNull { it.key == key } ?: return null
        if (from < 0 || item.index != from + leadingItems) return null
        val center = top + height / 2f
        val candidate = if (center > item.offset + item.size / 2f) {
            visible.firstOrNull { it.index == from + leadingItems + 1 }?.takeIf { center > it.offset + it.size / 2f }
        } else {
            visible.firstOrNull { it.index == from + leadingItems - 1 }?.takeIf { center < it.offset + it.size / 2f }
        }
        return candidate?.index?.minus(leadingItems)?.takeIf { it in keys.indices }
    }

    fun scrollSpeed(edge: Float, maxSpeed: Float): Float {
        val layout = list.layoutInfo
        val start = layout.viewportStartOffset + layout.beforeContentPadding
        val end = layout.viewportEndOffset - layout.afterContentPadding
        return when {
            pointerY < start + edge -> -maxSpeed * ((start + edge - pointerY) / edge).coerceIn(0f, 1f)
            pointerY > end - edge -> maxSpeed * ((pointerY - end + edge) / edge).coerceIn(0f, 1f)
            else -> 0f
        }
    }
}
