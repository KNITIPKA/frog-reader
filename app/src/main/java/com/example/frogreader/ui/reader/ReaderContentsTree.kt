package com.example.frogreader.ui.reader

/** Precomputed hierarchy for the flat reading-order TOC. No recursive row scans. */
internal class ReaderContentsTree(depths: List<Int>) {
    val depth = depths.map { it.coerceAtLeast(0) }
    val parent = IntArray(depth.size) { -1 }
    val isGroup = BooleanArray(depth.size)

    init {
        val ancestors = ArrayDeque<Int>()
        depth.indices.forEach { index ->
            while (ancestors.isNotEmpty() && depth[ancestors.last()] >= depth[index]) {
                ancestors.removeLast()
            }
            ancestors.lastOrNull()?.let {
                parent[index] = it
                isGroup[it] = true
            }
            ancestors.addLast(index)
        }
    }

    fun ancestorsOf(index: Int): List<Int> = buildList {
        var ancestor = parent.getOrElse(index) { -1 }
        while (ancestor >= 0) {
            add(ancestor)
            ancestor = parent[ancestor]
        }
    }

    /** Hidden descendants never become zero-height LazyColumn items. */
    fun visibleIndices(isCollapsed: (Int) -> Boolean): List<Int> = buildList {
        var hiddenBelow: Int? = null
        depth.indices.forEach { index ->
            val hiddenDepth = hiddenBelow
            if (hiddenDepth != null && depth[index] > hiddenDepth) return@forEach
            hiddenBelow = null
            add(index)
            if (isGroup[index] && isCollapsed(index)) hiddenBelow = depth[index]
        }
    }
}
