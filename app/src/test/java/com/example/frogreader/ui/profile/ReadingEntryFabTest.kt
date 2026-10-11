package com.example.frogreader.ui.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingEntryFabTest {
    @Test fun closeButtonRevealsControlsBeforeMaterialChangesItsTarget() {
        val state = readingEntrySheetPresentation(editorPresent = true, sheetMounted = true,
            dismissalRequested = true, sheetHiding = false)
        assertFalse(state.blocksAppearance)
        assertTrue(state.blocksInteraction)
    }

    @Test fun savedDraftRevealsControlsWhileTheExitRetainsItsForm() {
        val state = readingEntrySheetPresentation(editorPresent = false, sheetMounted = true,
            dismissalRequested = false, sheetHiding = false)
        assertFalse(state.blocksAppearance)
        assertTrue(state.blocksInteraction)
    }

    @Test fun scrimOrGestureDismissalRevealsControlsBeforeItsCompletionCallback() {
        val state = readingEntrySheetPresentation(editorPresent = true, sheetMounted = true,
            dismissalRequested = false, sheetHiding = true)
        assertFalse(state.blocksAppearance)
        assertTrue(state.blocksInteraction)
    }

    @Test fun freshlyMountedHiddenSheetDoesNotRevealControlsDuringItsEntrance() {
        val state = readingEntrySheetPresentation(editorPresent = true, sheetMounted = true,
            dismissalRequested = false, sheetHiding = false)
        assertTrue(state.blocksAppearance)
        assertTrue(state.blocksInteraction)
    }

    @Test fun unmountRestoresInteractionEvenIfAnOldDismissalSignalRemains() {
        val state = readingEntrySheetPresentation(editorPresent = false, sheetMounted = false,
            dismissalRequested = true, sheetHiding = true)
        assertFalse(state.blocksAppearance)
        assertFalse(state.blocksInteraction)
        assertFalse(state.closing)
    }
}
