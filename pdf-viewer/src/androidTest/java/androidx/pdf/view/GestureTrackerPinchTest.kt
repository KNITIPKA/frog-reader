package androidx.pdf.view

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GestureTrackerPinchTest {
    @Test fun pinchWorksAfterScrollAndReportsIdleAfterRelease() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val tracker = GestureTracker(instrumentation.targetContext)
            var factor = 1f
            tracker.delegate = object : GestureTracker.GestureHandler() {
                override fun onPinch(scaleFactor: Float, focusX: Float, focusY: Float) {
                    factor *= scaleFactor
                }
            }
            var now = SystemClock.uptimeMillis()
            var down = now
            fun send(action: Int, halfSpan: Float, count: Int = 1, xOffset: Float = 0f) {
                val properties = Array(2) { MotionEvent.PointerProperties().apply {
                    id = it; toolType = MotionEvent.TOOL_TYPE_FINGER
                } }
                val coords = Array(2) { index -> MotionEvent.PointerCoords().apply {
                    x = 600f + (if (index == 0) -1 else 1) * halfSpan + xOffset
                    y = 500f; pressure = 1f; size = 1f
                } }
                val event = MotionEvent.obtain(down, now, action, count, properties, coords,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                tracker.feed(event)
                event.recycle()
                now += 8
            }
            repeat(3) {
                down = now
                send(MotionEvent.ACTION_DOWN, 180f)
                repeat(8) { send(MotionEvent.ACTION_MOVE, 180f, xOffset = it * 20f) }
                send(MotionEvent.ACTION_UP, 180f, xOffset = 140f)
                now += 16 // Within the system double-tap timeout after a scroll.
                down = now
                send(MotionEvent.ACTION_DOWN, 180f)
                send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 180f, 2)
                val before = factor
                repeat(20) { send(MotionEvent.ACTION_MOVE, 180f + (it + 1) * 12f, 2) }
                assertTrue("Pinch after scroll must still receive a complete event stream", factor > before * 1.5f)
                assertTrue(tracker.matches(GestureTracker.Gesture.ZOOM))
                send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 420f, 2)
                send(MotionEvent.ACTION_UP, 420f)
                assertFalse("Completed zoom must not leave rendering permanently in motion", tracker.matches(GestureTracker.Gesture.ZOOM))
            }
        }
    }
}
