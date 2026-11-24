package com.jarvis.launcher.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream

/** Image helpers for the VISION brain: downscale + JPEG so requests stay small and fast. */
object Vision {
    private const val MAX_SIDE = 1280

    fun jpeg(bmp: Bitmap, quality: Int = 85): ByteArray {
        val scale = MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
        val b = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        return ByteArrayOutputStream().use { out -> b.compress(Bitmap.CompressFormat.JPEG, quality, out); out.toByteArray() }
    }

    /** Loads a photo from [uri] (camera output or gallery), respecting EXIF rotation. */
    fun fromUri(context: Context, uri: Uri): ByteArray? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rot = context.contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val upright = if (rot != 0f) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true) else bmp
        jpeg(upright)
    }.getOrNull()
}
