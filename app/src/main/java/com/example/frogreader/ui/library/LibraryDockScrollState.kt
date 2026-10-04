package com.example.frogreader.ui.library

/** Gesture intent, independent of item count, scroll position and fling physics. */
internal class LibraryDockScrollState(
    private val hideThreshold: Float,
    private val showThreshold: Float,
) {
    var visible: Boolean = true
        private set
    private var distance = 0f

    fun onDrag(deltaY: Float): Boolean {
        if (!deltaY.isFinite() || deltaY == 0f) return visible
        if ((distance < 0f && deltaY > 0f) || (distance > 0f && deltaY < 0f)) {
            distance = 0f
        }
        distance = (distance + deltaY).coerceIn(-hideThreshold, showThreshold)
        if (visible && distance <= -hideThreshold) {
            visible = false
            distance = 0f
        } else if (!visible && distance >= showThreshold) {
            visible = true
            distance = 0f
        }
        return visible
    }

    fun endGesture() {
        distance = 0f
    }

    fun reset() {
        visible = true
        endGesture()
    }
}
