package com.example.frogreader.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.geometry.Offset

/**
 * Where each grid tile currently sits, in root coordinates.
 *
 * All that survives of the old drag engine, and it earns its place: the folder
 * panel grows out of the folder's own tile, which means it has to know where
 * that tile is. A plain map rather than snapshot state — every read happens
 * inside a `graphicsLayer` lambda that already re-runs each frame of the open
 * animation, so making writes observable would only invalidate the grid.
 */
@Stable
class LibraryTileBounds {
    private val tiles = HashMap<String, Rect>()

    /**
     * Where the library's root Box sits in the window. Everything else here is
     * in root coordinates, and anything drawn back INTO that Box has to have it
     * subtracted again.
     */
    var rootOrigin: Offset = Offset.Zero

    operator fun get(id: String): Rect? = tiles[id]

    operator fun set(id: String, rect: Rect) {
        tiles[id] = rect
    }

    fun remove(id: String) {
        tiles.remove(id)
    }
}

/**
 * Registers a tile's position with [bounds] for as long as it is composed.
 *
 * `positionInRoot()` is UNCLIPPED, unlike every `boundsIn*` accessor — a tile
 * half-scrolled past the top of the grid still reports its true rect, so a
 * folder opened from up there still unfolds from the right place.
 */
@Composable
internal fun Modifier.tileBounds(id: String, bounds: LibraryTileBounds): Modifier {
    // onGloballyPositioned has no removal callback, so prune here instead.
    DisposableEffect(id) {
        onDispose { bounds.remove(id) }
    }
    return this.onGloballyPositioned { coordinates ->
        bounds[id] = Rect(coordinates.positionInRoot(), coordinates.size.toSize())
    }
}
