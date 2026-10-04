package com.example.frogreader.ui.profile

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal data class ReadingQueueImeFrame(val bottom: Int, val source: Int, val target: Int)

/** Draw gate for one mounted new entry; placement belongs entirely to the live IME insets. */
@Stable
internal class ReadingQueueSheetEntrance {
    var visible by mutableStateOf(false)
        private set
    var finished by mutableStateOf(false)
        private set
    private var openingTarget: Int? = null

    fun followIme(frame: ReadingQueueImeFrame): Boolean {
        if (finished) return true
        val distance = frame.target - frame.source
        if (distance <= 0) {
            // onEnd may reset source/target before the collector sees the last frame.
            if (openingTarget?.let { frame.bottom >= it } == true) {
                reveal()
            }
            return finished
        }
        openingTarget = frame.target
        // Latch the first moving frame: restarts or focus changes must not hide the panel.
        if (frame.bottom > frame.source) visible = true
        if (frame.bottom >= frame.target) reveal()
        return finished
    }

    fun reveal() {
        visible = true
        finished = true
    }
}

/** Must run in the modal's content, where WindowInsets refer to the window owning the input. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BindReadingQueueSheetEntrance(
    entrance: ReadingQueueSheetEntrance, closing: Boolean,
) {
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val source = WindowInsets.imeAnimationSource
    val target = WindowInsets.imeAnimationTarget
    LaunchedEffect(entrance, closing) {
        // Stop listening once dismissal starts, so a late IME frame cannot reveal it again.
        if (closing) return@LaunchedEffect
        val frames = snapshotFlow {
            ReadingQueueImeFrame(ime.getBottom(density), source.getBottom(density), target.getBottom(density))
        }
        // Wait for the opening signal, not its completion. The timeout covers a hardware
        // keyboard or rejected show request, so the form cannot remain invisible.
        val start = withTimeoutOrNull(500) {
            frames.first { it.target > it.source || it.bottom > 0 }
        }
        if (start != null && start.target > start.source) {
            if (entrance.followIme(start)) return@LaunchedEffect
            withTimeoutOrNull(1500) {
                frames.first { frame ->
                    entrance.followIme(frame) || frame.target <= frame.source
                }
            }
        }
        // Already-open, canceled, or missing IME: reveal without a second animation.
        entrance.reveal()
    }
}
