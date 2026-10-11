package com.example.frogreader.ui.reader

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween

/** Shared timing for the page transition and the Contents selection feedback. */
internal fun <T> readerChapterSwitchSpec(): TweenSpec<T> =
    tween(durationMillis = 240, easing = FastOutSlowInEasing)
