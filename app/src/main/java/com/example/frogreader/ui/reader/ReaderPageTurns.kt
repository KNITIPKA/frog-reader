package com.example.frogreader.ui.reader

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.PagerState
import com.example.frogreader.data.PageTurnAnimation

/** Selection uses the same short transition in every animated renderer. */
internal fun selectionPageTurnSpec(): AnimationSpec<Float> =
    tween(durationMillis = 240, easing = FastOutSlowInEasing)

/** All programmatic page changes honor the chosen animation, including chapter jumps. */
internal suspend fun PagerState.turnReaderPage(
    target: Int,
    animation: PageTurnAnimation,
    animationSpec: AnimationSpec<Float>,
) {
    if (animation == PageTurnAnimation.NONE) scrollToPage(target)
    else animateScrollToPage(target, animationSpec = animationSpec)
}
