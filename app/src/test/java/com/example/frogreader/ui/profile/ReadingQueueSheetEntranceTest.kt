package com.example.frogreader.ui.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingQueueSheetEntranceTest {
    @Test fun firstMovingKeyboardFrameRevealsThePanelBeforeCompletion() {
        val entrance = ReadingQueueSheetEntrance()
        assertFalse(entrance.followIme(ReadingQueueImeFrame(0, 0, 400)))
        assertFalse(entrance.visible)
        assertFalse(entrance.followIme(ReadingQueueImeFrame(100, 0, 400)))
        assertTrue(entrance.visible)
        assertFalse(entrance.followIme(ReadingQueueImeFrame(300, 0, 400)))
        assertTrue(entrance.visible)
        assertTrue(entrance.followIme(ReadingQueueImeFrame(400, 0, 400)))
        assertTrue(entrance.visible)
    }

    @Test fun aNonzeroAnimationSourceWaitsForTheFirstMovingFrame() {
        val entrance = ReadingQueueSheetEntrance()
        entrance.followIme(ReadingQueueImeFrame(100, 100, 400))
        assertFalse(entrance.visible)
        entrance.followIme(ReadingQueueImeFrame(250, 100, 400))
        assertTrue(entrance.visible)
        assertFalse(entrance.finished)
    }

    @Test fun laterFocusAndKeyboardChangesCannotReplayTheEntrance() {
        val entrance = ReadingQueueSheetEntrance()
        entrance.followIme(ReadingQueueImeFrame(400, 0, 400))
        assertTrue(entrance.followIme(ReadingQueueImeFrame(0, 400, 0)))
        assertTrue(entrance.followIme(ReadingQueueImeFrame(100, 0, 600)))
        assertTrue(entrance.visible)
        assertTrue(entrance.finished)
    }

    @Test fun settledEndFrameCompletesWithoutAnotherEntrance() {
        val entrance = ReadingQueueSheetEntrance()
        entrance.followIme(ReadingQueueImeFrame(390, 0, 400))
        assertFalse(entrance.finished)
        assertTrue(entrance.followIme(ReadingQueueImeFrame(400, 400, 400)))
        assertTrue(entrance.visible)
    }

    @Test fun canceledOrRestartedAnimationCannotHideTheRevealedPanel() {
        val entrance = ReadingQueueSheetEntrance()
        entrance.followIme(ReadingQueueImeFrame(100, 0, 400))
        assertFalse(entrance.followIme(ReadingQueueImeFrame(100, 100, 100)))
        assertFalse(entrance.followIme(ReadingQueueImeFrame(0, 400, 0)))
        entrance.followIme(ReadingQueueImeFrame(0, 0, 600))
        assertTrue(entrance.visible)
        entrance.reveal()
        assertTrue(entrance.visible)
        assertTrue(entrance.finished)
    }

    @Test fun alreadyVisibleOrHardwareKeyboardCanRevealWithoutAnAnimation() {
        val entrance = ReadingQueueSheetEntrance()
        assertFalse(entrance.followIme(ReadingQueueImeFrame(0, 0, 0)))
        assertFalse(entrance.followIme(ReadingQueueImeFrame(400, 400, 400)))
        entrance.reveal()
        assertTrue(entrance.visible)
        assertTrue(entrance.finished)
    }

}
