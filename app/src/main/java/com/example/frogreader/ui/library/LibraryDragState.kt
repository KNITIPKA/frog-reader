package com.example.frogreader.ui.library

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** What a release would do right now. */
data class DragDrop(
    val draggedId: String,
    /** Shelf to file into, or book to make a new shelf with. */
    val mergeTargetId: String?,
    /** Carried out of the open shelf panel — put the book back on the grid. */
    val outsideContainer: Boolean,
    /** Where the finger let go, in root space. */
    val releaseRoot: Offset = Offset.Zero,
    /**
     * Centre of the merge target, captured AT RELEASE. The target's tile is
     * about to be disposed — a book swallowed by a new shelf stops being an
     * entry — and `dragSource` prunes its bounds on the way out, so this is the
     * last moment the rect can be read.
     */
    val targetCenter: Offset? = null,
)

/**
 * A book that has been let go but is still travelling to where it landed.
 *
 * Exists so the ghost can fly home instead of blinking out of existence: the
 * gesture is over ([LibraryDragState.isDragging] is already false, veils and
 * rings are gone), but the cover is still on screen for another ~200ms.
 */
data class DragLanding(
    /** Entry id of the carried book, for picking the cover to draw. */
    val entryId: String,
    /** Release point, root space. */
    val from: Offset,
    /** Where to land if [liveTargetId] resolves to nothing. */
    val to: Offset,
    /**
     * Tile to home in on, re-read from [LibraryDragState.bounds] every frame.
     * The grid reflows underneath the flight — the dragged book vacated its
     * slot, so everything after it slides up — and a destination captured at
     * release would be a row stale by the time the ghost got there.
     */
    val liveTargetId: String?,
    /**
     * True when the book was swallowed by something. A merge shrinks into its
     * target; a drag that ended on nothing just settles back at full size.
     */
    val merged: Boolean,
)

/**
 * Drag-and-drop bookkeeping for the library grid: what is being carried, what
 * it is hovering over, and where every tile sits.
 *
 * Two rules make this cheap and correct:
 *
 * 1. [fingerRoot] is written on every pointer move (~120 Hz) and must be read
 *    ONLY from a `Modifier.offset { }` or `graphicsLayer { }` lambda. Those run
 *    in the layout/draw phase, so the ghost re-places without a single
 *    recomposition. Read it from a composable body and every tile in the grid
 *    recomposes on every movement of the finger.
 * 2. All geometry is in **Compose-root space**, captured with
 *    `positionInRoot()`. The `boundsIn*` accessors are clipped by every parent
 *    clip, so a tile half-scrolled past the top of the grid would report a
 *    truncated hit region — exactly where the drag auto-scroll operates.
 */
@Stable
class LibraryDragState {

    /**
     * Tile bounds in root space, keyed by [LibraryEntry.id] (or `p:<bookId>`
     * for tiles inside an open shelf panel). A plain HashMap on purpose: it is
     * written during layout and read from pointer callbacks, both on the UI
     * thread, and nothing in composition reads it — snapshot state here would
     * only risk the classic write-in-layout / read-in-composition loop.
     */
    val bounds = HashMap<String, Rect>()

    /** Bounds of the scrolling grid itself, for the auto-scroll edge zones. */
    var viewport: Rect = Rect.Zero

    /** Bounds of the open shelf panel, for "did the book leave the folder?". */
    var panelBounds: Rect = Rect.Zero

    /** Origin of the overlay host, so ghost offsets can be made relative to it. */
    var rootOrigin: Offset = Offset.Zero

    var draggingId by mutableStateOf<String?>(null)
        private set

    /** Tile that would swallow the dragged book on drop. */
    var mergeTargetId by mutableStateOf<String?>(null)
        private set

    /** Set while dragging a book that started inside an open shelf panel. */
    var dragShelfId by mutableStateOf<String?>(null)
        private set

    /** True once such a book has been carried outside the panel. */
    var outsidePanel by mutableStateOf(false)
        private set

    var fingerRoot by mutableStateOf(Offset.Zero)

    /** Set while a released book is still flying to its destination. */
    var landing by mutableStateOf<DragLanding?>(null)
        private set

    val isDragging: Boolean get() = draggingId != null

    fun start(id: String, finger: Offset, shelfId: String? = null) {
        draggingId = id
        dragShelfId = shelfId
        outsidePanel = false
        mergeTargetId = null
        fingerRoot = finger
        // Picking anything up cancels a flight still in the air.
        landing = null
    }

    fun beginLanding(
        entryId: String,
        from: Offset,
        to: Offset,
        liveTargetId: String?,
        merged: Boolean,
    ) {
        landing = DragLanding(entryId, from, to, liveTargetId, merged)
    }

    /** The destination only became addressable after the drop — a new shelf. */
    fun retargetLanding(liveTargetId: String) {
        landing = landing?.copy(liveTargetId = liveTargetId)
    }

    fun endLanding() {
        landing = null
    }

    /**
     * Resolves what the finger is currently over. Returns true when the merge
     * target changed, so the caller can fire a haptic tick.
     */
    fun updateHover(): Boolean {
        val dragged = draggingId ?: return setMergeTarget(null)

        // Dragging within (or out of) an open shelf: the only question is
        // whether the book has left the folder.
        if (dragShelfId != null) {
            outsidePanel = !panelBounds.isEmpty && !panelBounds.contains(fingerRoot)
            return setMergeTarget(null)
        }

        // Shelves are containers, not contents: one can never go inside another.
        if (dragged.startsWith(SHELF_PREFIX)) return setMergeTarget(null)

        return setMergeTarget(tileUnder(fingerRoot, exclude = dragged))
    }

    private fun setMergeTarget(id: String?): Boolean {
        if (mergeTargetId == id) return false
        mergeTargetId = id
        return true
    }

    /** Nearest-centre tile among those actually containing [point]. */
    private fun tileUnder(point: Offset, exclude: String): String? {
        var bestId: String? = null
        var bestDistance = Float.MAX_VALUE
        for ((id, rect) in bounds) {
            if (id == exclude || !rect.contains(point)) continue
            val distance = (rect.center - point).getDistanceSquared()
            if (distance < bestDistance) {
                bestDistance = distance
                bestId = id
            }
        }
        return bestId
    }

    /** Snapshot of what a release would do, taken before [reset] wipes it. */
    fun currentDrop(): DragDrop? {
        val dragged = draggingId ?: return null
        return DragDrop(
            draggedId = dragged,
            mergeTargetId = mergeTargetId,
            outsideContainer = outsidePanel,
            releaseRoot = fingerRoot,
            targetCenter = mergeTargetId?.let { bounds[it]?.center }
                ?: bounds[dragged]?.center,
        )
    }

    fun reset() {
        draggingId = null
        dragShelfId = null
        outsidePanel = false
        mergeTargetId = null
    }

    /**
     * Vertical auto-scroll speed in px/s for the current finger position: zero
     * outside the edge zones, ramping to [maxVelocity] at the very edge.
     */
    fun autoScrollVelocity(edgeZonePx: Float, maxVelocity: Float): Float {
        // Never scroll the grid out from under an open shelf panel.
        if (!isDragging || viewport.isEmpty || dragShelfId != null) return 0f
        val y = fingerRoot.y
        val topZone = viewport.top + edgeZonePx
        val bottomZone = viewport.bottom - edgeZonePx
        return when {
            y < topZone -> -((topZone - y) / edgeZonePx).coerceIn(0f, 1f) * maxVelocity
            y > bottomZone -> ((y - bottomZone) / edgeZonePx).coerceIn(0f, 1f) * maxVelocity
            else -> 0f
        }
    }

    private companion object {
        const val SHELF_PREFIX = "s:"
    }
}
