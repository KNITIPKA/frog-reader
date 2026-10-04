package com.example.frogreader.reader

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.geometry.Offset
import com.example.frogreader.ui.reader.ReaderSeekInteractionSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSeekInteractionSourceTest {
    @Test
    fun `cancel clears pending seek before the slider finishes the gesture`() = runTest {
        var pending = true
        val source = ReaderSeekInteractionSource { pending = false }
        val drag = DragInteraction.Start()
        source.emit(drag)
        source.emit(DragInteraction.Cancel(drag))
        assertFalse(pending)
        pending = true
        source.tryEmit(DragInteraction.Cancel(drag))
        assertFalse(pending)
    }

    @Test
    fun `press yielding to a drag and normal release preserve the seek`() = runTest {
        var pending = true
        val source = ReaderSeekInteractionSource { pending = false }
        val press = PressInteraction.Press(Offset.Zero)
        val drag = DragInteraction.Start()
        source.emit(press)
        source.emit(PressInteraction.Cancel(press))
        source.emit(drag)
        source.emit(DragInteraction.Stop(drag))
        assertTrue(pending)
    }
}
