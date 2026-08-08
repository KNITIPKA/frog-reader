@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.example.frogreader.ui.nav

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** Provided once around the NavHost. */
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** Provided per navigation destination. */
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Marks a book cover as a shared element so it morphs between the library
 * grid and the reader's title page. No-op when scopes are unavailable.
 */
@Composable
fun Modifier.sharedBookCover(bookId: String): Modifier {
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val animatedScope = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(sharedScope) {
        this@sharedBookCover.sharedElement(
            rememberSharedContentState(key = "book-cover-$bookId"),
            animatedScope,
        )
    }
}
