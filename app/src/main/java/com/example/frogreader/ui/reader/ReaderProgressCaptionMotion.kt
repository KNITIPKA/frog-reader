package com.example.frogreader.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

internal const val ReaderProgressCaptionIdleMillis = 64L
private const val MotionWindowMillis = 100L
private const val MinimumMotionMillis = 24L
private const val MotionSampleCapacity = 32
private const val StartSpeedDpPerSecond = 200f
private const val KeepSpeedDpPerSecond = 140f
private const val FullBlurSpeedDpPerSecond = 1000f
private const val MinimumTrackDistanceDp = 12f
private const val MinimumBookDistance = 0.025f
private const val MinimumBookSpeedPerSecond = 0.18f

/**
 * Classify actual input, never caption-change frequency or animated thumb geometry.
 * A short, directional window must cover both meaningful travel and a chapter boundary.
 */
internal class ReaderProgressCaptionPolicy {
    private val times = LongArray(MotionSampleCapacity)
    private val positions = FloatArray(MotionSampleCapacity)
    private val fractions = FloatArray(MotionSampleCapacity)
    private val chapters = IntArray(MotionSampleCapacity)
    private var head = 0
    private var size = 0
    private var direction = 0

    var blurAmount = 0f
        private set

    fun update(bookFraction: Float, trackPositionDp: Float, chapterIndex: Int, nowMillis: Long): Float {
        if (!bookFraction.isFinite() || !trackPositionDp.isFinite() || chapterIndex < 0) {
            reset()
            return 0f
        }
        val book = bookFraction.coerceIn(0f, 1f)
        if (size == 0) return seed(book, trackPositionDp, chapterIndex, nowMillis)

        val last = index(size - 1)
        val elapsed = nowMillis - times[last]
        // A pause, clock reset, or precision movement starts a fresh run.
        if (elapsed < 0L || elapsed >= ReaderProgressCaptionIdleMillis) {
            return seed(book, trackPositionDp, chapterIndex, nowMillis)
        }
        if (elapsed == 0L) {
            // Coalesce callbacks in the same millisecond instead of inventing velocity.
            positions[last] = trackPositionDp
            fractions[last] = book
            chapters[last] = chapterIndex
            return blurAmount
        }
        val delta = trackPositionDp - positions[last]
        val nextDirection = if (delta > 0f) 1 else if (delta < 0f) -1 else 0
        val speed = abs(delta) * 1000f / elapsed
        val requiredSpeed = if (blurAmount > 0f) KeepSpeedDpPerSecond else StartSpeedDpPerSecond
        if (speed < requiredSpeed || (direction != 0 && nextDirection != direction)) {
            return seed(book, trackPositionDp, chapterIndex, nowMillis)
        }
        direction = nextDirection
        append(book, trackPositionDp, chapterIndex, nowMillis)
        // Keep only recent evidence; entering a long chapter cannot latch the blur on.
        while (size > 1 && nowMillis - times[head] > MotionWindowMillis) {
            head = index(1)
            size--
        }
        val duration = nowMillis - times[head]
        val trackTravel = trackPositionDp - positions[head]
        val bookTravel = book - fractions[head]
        val wholeBookMotion = duration >= MinimumMotionMillis &&
            abs(trackTravel) >= MinimumTrackDistanceDp &&
            abs(bookTravel) >= MinimumBookDistance &&
            trackTravel * bookTravel > 0f &&
            chapterIndex != chapters[head]
        if (!wholeBookMotion) {
            blurAmount = 0f
            return 0f
        }
        val windowSpeed = abs(trackTravel) * 1000f / duration
        val bookSpeed = abs(bookTravel) * 1000f / duration
        blurAmount = if (windowSpeed >= requiredSpeed && bookSpeed >= MinimumBookSpeedPerSecond) {
            // React to slowing immediately; the recent window smooths acceleration.
            val strength = ((min(speed, windowSpeed) - KeepSpeedDpPerSecond) /
                (FullBlurSpeedDpPerSecond - KeepSpeedDpPerSecond)).coerceIn(0f, 1f)
            0.15f + 0.85f * strength
        } else 0f
        return blurAmount
    }

    fun millisUntilIdle(nowMillis: Long): Long = if (size == 0) 0L else {
        (ReaderProgressCaptionIdleMillis - (nowMillis - times[index(size - 1)])).coerceAtLeast(0L)
    }

    fun settle(nowMillis: Long): Float {
        if (millisUntilIdle(nowMillis) == 0L) reset()
        return blurAmount
    }

    fun reset() {
        head = 0
        size = 0
        direction = 0
        blurAmount = 0f
    }

    private fun seed(book: Float, position: Float, chapter: Int, time: Long): Float {
        reset()
        append(book, position, chapter, time)
        return 0f
    }

    private fun index(offset: Int) = (head + offset) % MotionSampleCapacity

    private fun append(book: Float, position: Float, chapter: Int, time: Long) {
        if (size == MotionSampleCapacity) {
            head = index(1)
            size--
        }
        val next = index(size++)
        times[next] = time
        positions[next] = position
        fractions[next] = book
        chapters[next] = chapter
    }
}

/** Synchronous input/release updates plus one idle watcher per active burst. */
internal class ReaderProgressCaptionMotion(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
) {
    private val policy = ReaderProgressCaptionPolicy()
    private var idleJob: Job? = null
    var blurAmount by mutableFloatStateOf(0f)
        private set

    fun update(bookFraction: Float, trackPositionDp: Float, chapterIndex: Int) {
        blurAmount = policy.update(bookFraction, trackPositionDp, chapterIndex, nowMillis())
        if (blurAmount == 0f) {
            cancelIdleWatcher()
        } else if (idleJob == null) {
            // Moves extend the deadline without cancelling/relaunching on every event.
            idleJob = scope.launch {
                while (true) {
                    val remaining = policy.millisUntilIdle(nowMillis())
                    if (remaining == 0L) break
                    delay(remaining)
                }
                blurAmount = policy.settle(nowMillis())
                idleJob = null
            }
        }
    }

    fun reset() {
        cancelIdleWatcher()
        policy.reset()
        // The graphicsLayer observes this write directly; no recomposition or tween is needed.
        blurAmount = 0f
    }

    private fun cancelIdleWatcher() {
        idleJob?.cancel()
        idleJob = null
    }
}
