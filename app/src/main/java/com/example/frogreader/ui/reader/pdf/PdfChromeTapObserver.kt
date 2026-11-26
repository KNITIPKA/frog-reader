@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.pdf.ExperimentalPdfApi::class)
@file:Suppress("RestrictedApi")

package com.example.frogreader.ui.reader.pdf

import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.platform.LocalView

/** Observes without consuming events needed by native PDF scrolling, zoom and selection. */
@Composable
internal fun Modifier.pdfChromeTapObserver(
    selectionActive: Boolean,
    onPointerDown: (Boolean) -> Unit = {},
    onPress: (Offset) -> Unit = {},
    onTap: (Offset) -> Boolean = { false },
    onToggle: () -> Unit,
): Modifier {
    val composeView = LocalView.current
    val currentSelectionActive by rememberUpdatedState(selectionActive)
    val currentPointerDown by rememberUpdatedState(onPointerDown)
    val currentPress by rememberUpdatedState(onPress)
    val currentToggle by rememberUpdatedState(onToggle)
    val currentTap by rememberUpdatedState(onTap)
    val detector = remember(composeView) {
        GestureDetector(composeView.context, object : GestureDetector.SimpleOnGestureListener() {
            private var eligible = false

            override fun onDown(event: MotionEvent): Boolean {
                // Capture this press only after the detector has resolved the previous one.
                val viewer = composeView.findPdfView()
                val origin = IntArray(2)
                viewer?.getLocationOnScreen(origin)
                val hitsScroller = viewer?.isFastScrollThumbHit(event.rawX - origin[0], event.rawY - origin[1]) == true
                eligible = !currentSelectionActive && !hitsScroller
                return true
            }

            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                val toggle = eligible && !currentSelectionActive
                eligible = false
                // The same system recognizer as native PDF zoom: a double tap never changes
                // chrome, even temporarily. There is no extra timer after confirmation.
                if (toggle && !currentTap(Offset(event.rawX, event.rawY))) currentToggle()
                return toggle
            }
        })
    }
    DisposableEffect(detector) {
        onDispose {
            val now = SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            try { detector.onTouchEvent(cancel) }
            finally { cancel.recycle(); currentPointerDown(false) }
        }
    }
    return motionEventSpy { event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { currentPointerDown(true); currentPress(Offset(event.rawX, event.rawY)) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> currentPointerDown(false)
        }
        // Observing the original stream does not consume native pan, pinch, zoom or selection.
        detector.onTouchEvent(event)
    }
}
