package com.example.frogreader.ui.profile

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Called on Dispatchers.IO; limits both encoded bytes and decoded pixels. */
internal fun readAvatarPhoto(resolver: ContentResolver, uri: Uri): Bitmap {
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > 32 * 1024 * 1024) throw IOException("Image exceeds 32 MB")
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: throw IOException("Cannot open image")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Unsupported image")
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }) ?: throw IOException("Cannot decode image")
    val orientation = runCatching {
        ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
        }
    }
    val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (oriented !== bitmap) bitmap.recycle()
    return oriented
}

internal fun encodeAvatar(bitmap: Bitmap, crop: AvatarCrop): String {
    val rect = crop.pixels(bitmap.width, bitmap.height)
    val result = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
    try {
        Canvas(result).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, Rect(rect.left, rect.top, rect.left + rect.side, rect.top + rect.side),
                Rect(0, 0, 512, 512), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        val bytes = ByteArrayOutputStream().use { output ->
            if (!result.compress(Bitmap.CompressFormat.JPEG, 90, output)) throw IOException("Cannot encode avatar")
            output.toByteArray()
        }
        // Keep the single preference compact, even for noisy input images.
        if (bytes.size > 512 * 1024) throw IOException("Avatar exceeds size limit")
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    } finally {
        result.recycle()
    }
}

internal fun decodeAvatar(encoded: String?): Bitmap? {
    if (encoded == null || encoded.length > 700_000) return null
    return runCatching {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..512 || bounds.outHeight !in 1..512) return null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}
