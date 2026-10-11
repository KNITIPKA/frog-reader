package com.example.frogreader.ui.profile

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Visual controls recover as dismissal begins; the modal still owns input until it unmounts. */
internal data class ReadingEntrySheetPresentation(val mounted: Boolean, val closing: Boolean) {
    val blocksAppearance get() = mounted && !closing
    val blocksInteraction get() = mounted
}

internal fun readingEntrySheetPresentation(
    editorPresent: Boolean,
    sheetMounted: Boolean,
    dismissalRequested: Boolean,
    sheetHiding: Boolean,
): ReadingEntrySheetPresentation = ReadingEntrySheetPresentation(
    mounted = sheetMounted,
    closing = sheetMounted && (dismissalRequested || !editorPresent || sheetHiding),
)

/** Both forms have a bounded exit, so an invisible spring tail cannot keep intercepting touches. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ReadingEntrySheetMotion(content: @Composable () -> Unit) {
    val base = MaterialTheme.motionScheme
    val motion = remember(base) {
        object : MotionScheme by base {
            override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> =
                tween(240, easing = LinearOutSlowInEasing)
            override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> =
                tween(180, easing = FastOutSlowInEasing)
            override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> =
                tween(150)
        }
    }
    MaterialTheme(motionScheme = motion, content = content)
}
