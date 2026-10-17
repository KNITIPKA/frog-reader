package com.example.frogreader.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

/** Expands before the content scrolls; contracts only after the content reaches its top. */
internal class ReaderPanelNestedScrollConnection(
    private val motion: ReaderPanelMotion,
    fullHeight: Float,
    onSettle: (Float) -> Unit,
) : NestedScrollConnection {
    private var fullHeight = fullHeight
    private var onSettle = onSettle
    private var dragging = false

    /** Refresh measured bounds and anchors without discarding an in-progress gesture. */
    fun update(fullHeight: Float, onSettle: (Float) -> Unit) {
        this.fullHeight = fullHeight
        this.onSettle = onSettle
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
        if (!motion.isOpen || dragHeight >= fullHeight) return Offset.Zero
        return drag(available.y)
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
        if (!motion.isOpen || dragHeight <= 0f) return Offset.Zero
        return drag(available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!dragging) return Velocity.Zero
        if (motion.targetHeight >= fullHeight) {
            dragging = false
            motion.finishDrag()
            return Velocity.Zero
        }
        return settle(available)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (!dragging) return Velocity.Zero
        if (motion.targetHeight >= fullHeight) {
            dragging = false
            motion.finishDrag()
            return Velocity.Zero
        }
        return settle(available)
    }

    private val dragHeight: Float
        get() = if (dragging) motion.targetHeight else motion.height.value

    private fun drag(delta: Float): Offset {
        if (!dragging) {
            motion.startDrag()
            dragging = true
        }
        return Offset(0f, motion.dragBy(delta, fullHeight))
    }

    private fun settle(available: Velocity): Velocity {
        dragging = false
        motion.finishDrag()
        onSettle(available.y)
        return Velocity(0f, available.y)
    }
}
