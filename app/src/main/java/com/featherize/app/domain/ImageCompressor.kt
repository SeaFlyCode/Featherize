package com.featherize.app.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

class ImageCompressor(private val context: Context) {

    fun compress(sourceUri: Uri, preset: CompressionPreset, outputFile: File): File {
        val bitmap = decodeModern(sourceUri, preset.imageMaxDimension)
            ?: decodeLegacy(sourceUri, preset.imageMaxDimension)

        try {
            FileOutputStream(outputFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, preset.imageJpegQuality, out)
            }
        } finally {
            bitmap.recycle()
        }

        return outputFile
    }

    /**
     * ImageDecoder (API 28+) is far more resilient than BitmapFactory for real-world photos —
     * it handles HEIC/HEIF, wide-gamut color, and odd EXIF orientations that make
     * BitmapFactory.decodeStream return null on some OEM camera outputs. It also auto-applies
     * EXIF rotation and can downsample during decode, so no separate rotate/scale pass is needed.
     */
    private fun decodeModern(uri: Uri, maxDimension: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                val longest = max(info.size.width, info.size.height)
                if (longest > maxDimension) {
                    val scale = maxDimension.toFloat() / longest
                    val targetW = (info.size.width * scale).toInt().coerceAtLeast(1)
                    val targetH = (info.size.height * scale).toInt().coerceAtLeast(1)
                    decoder.setTargetSize(targetW, targetH)
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun decodeLegacy(uri: Uri, maxDimension: Int): Bitmap {
        val bounds = readBounds(uri)
        val sampleSize = calculateInSampleSize(bounds.first, bounds.second, maxDimension)

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("Impossible de décoder l'image (${context.contentResolver.getType(uri) ?: "type inconnu"})")

        val rotated = applyExifRotation(uri, decoded)
        val resized = scaleToMax(rotated, maxDimension)

        if (resized !== rotated) rotated.recycle()
        if (rotated !== decoded && decoded !== resized) decoded.recycle()

        return resized
    }

    private fun readBounds(uri: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        return options.outWidth to options.outHeight
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        val longest = max(width, height)
        while (longest / (sampleSize * 2) >= maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun scaleToMax(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / longest
        val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun applyExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
