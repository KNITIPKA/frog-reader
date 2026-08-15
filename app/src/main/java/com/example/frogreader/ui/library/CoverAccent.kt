package com.example.frogreader.ui.library

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The colour a cover is "about", for the light behind it.
 *
 * Done by hand rather than with androidx.palette: this needs one number from a
 * picture that is already in memory, and a whole quantisation library for that
 * is a dependency the app would carry into every build for one screen.
 *
 * The average colour of a cover is almost always a muddy grey — covers are
 * mostly text and dark art. So greys, near-blacks and near-whites are thrown
 * away first and only the coloured pixels get a vote, which is what makes a
 * black cover with a red circle read as red rather than as charcoal.
 */
internal object CoverAccent {

    suspend fun of(bytes: ByteArray): Color? = withContext(Dispatchers.Default) {
        runCatching { extract(bytes) }.getOrNull()
    }

    private fun extract(bytes: ByteArray): Color? {
        // Measure first, then decode straight down to thumbnail size: a full
        // 1600px cover would be several megabytes of bitmap to answer one
        // question about it.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        if (largest <= 0) return null

        var sample = 1
        while (largest / sample > TARGET_PIXELS) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null

        // Hue buckets rather than a running average: averaging red and blue
        // gives grey, which is the one answer that is never right.
        val counts = IntArray(BUCKETS)
        val saturation = FloatArray(BUCKETS)
        val value = FloatArray(BUCKETS)
        val hsv = FloatArray(3)
        var considered = 0
        // Kept in parallel for the black-and-white case below.
        var greyHue = 0f
        var greySaturation = 0f
        var greyValue = 0f
        var greyCount = 0

        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap[x, y]
                if ((pixel ushr 24 and 0xFF) < 128) continue
                android.graphics.Color.colorToHSV(pixel, hsv)
                val (h, s, v) = hsv

                greyHue += h
                greySaturation += s
                greyValue += v
                greyCount++

                // Grey, nearly black, nearly white: all true of most of a cover
                // and none of them what it looks like.
                if (s < MIN_SATURATION || v < MIN_VALUE || v > MAX_VALUE) continue
                val bucket = ((h / 360f) * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
                counts[bucket]++
                saturation[bucket] += s
                value[bucket] += v
                considered++
            }
        }
        bitmap.recycle()

        if (considered > 0) {
            var best = 0
            for (i in 1 until BUCKETS) if (counts[i] > counts[best]) best = i
            val n = counts[best]
            return Color.hsv(
                hue = (best + 0.5f) * (360f / BUCKETS),
                // Pulled into a usable band: a washed-out cover would give a
                // glow indistinguishable from the background, and a fluorescent
                // one a glow that shouts over the artwork it sits behind.
                saturation = (saturation[best] / n).coerceIn(0.45f, 0.85f),
                value = (value[best] / n).coerceIn(0.5f, 0.9f),
            )
        }

        // Nothing coloured at all — a black-and-white photograph, a typographic
        // cover. Returning null here left those books with no light behind them
        // whatsoever, which is exactly the set of covers that most needed some.
        // The faint cast such a picture does have, lifted until it reads.
        if (greyCount == 0) return null
        return Color.hsv(
            hue = greyHue / greyCount,
            saturation = (greySaturation / greyCount).coerceIn(0.06f, 0.3f),
            value = (greyValue / greyCount).coerceIn(0.55f, 0.85f),
        )
    }

    /** Longest edge to decode down to; enough pixels to vote, few enough to be instant. */
    private const val TARGET_PIXELS = 64
    private const val BUCKETS = 24
    private const val MIN_SATURATION = 0.18f
    private const val MIN_VALUE = 0.12f
    private const val MAX_VALUE = 0.96f
}
