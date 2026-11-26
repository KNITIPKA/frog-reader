package androidx.pdf.view

import org.junit.Assert.*
import org.junit.Test

class PinchScaleTrackerTest {
    @Test fun `close fingers start after touch slop and preserve the full span ratio`() {
        val tracker = PinchScaleTracker(8f)
        tracker.rebase(80f)
        assertNull(tracker.scale(84f))
        assertNull(tracker.scale(88f))
        var factor = tracker.scale(90f)!!
        factor *= tracker.scale(120f)!!
        factor *= tracker.scale(160f)!!
        assertEquals(2f, factor, .0001f)
        assertEquals(.5f, tracker.scale(80f)!!, .0001f)
    }

    @Test fun `adding or removing a pointer rebases without a scale jump`() {
        val tracker = PinchScaleTracker(8f)
        tracker.rebase(120f)
        assertEquals(1.5f, tracker.scale(180f)!!, .0001f)
        tracker.rebase(80f)
        assertNull(tracker.scale(80f))
        assertEquals(1.25f, tracker.scale(100f)!!, .0001f)
    }

    @Test fun `coincident pointers and invalid spans cannot produce invalid zoom`() {
        val tracker = PinchScaleTracker(8f)
        tracker.rebase(0f)
        assertNull(tracker.scale(0f))
        assertNull(tracker.scale(Float.NaN))
        assertNull(tracker.scale(80f))
        assertEquals(1.5f, tracker.scale(120f)!!, .0001f)
    }
}
