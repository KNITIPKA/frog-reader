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
 * Whether the cover key is free to be claimed here.
 *
 * A shared-element key may only be alive in ONE place at a time — two nodes
 * registering `book-cover-<id>` at once is a crash. The reader shows the cover
 * twice over: on the opening screen while the book is parsed, and again as the
 * first item of the page content. This is how they hand the key over, and it
 * has to stay false for as long as the opening screen is composed at all,
 * including the frames it spends fading out.
 */
val LocalSharedCoverAvailable = compositionLocalOf { true }

/**
 * Marks a book cover as a shared element so it morphs between the library
 * grid and the reader. No-op when the scopes are unavailable, or when
 * something else currently owns the key.
 */
@Composable
fun Modifier.sharedBookCover(bookId: String): Modifier {
    if (!LocalSharedCoverAvailable.current) return this
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val animatedScope = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(sharedScope) {
        this@sharedBookCover.sharedElement(
            rememberSharedContentState(key = "book-cover-$bookId"),
            animatedScope,
        )
    }
}
