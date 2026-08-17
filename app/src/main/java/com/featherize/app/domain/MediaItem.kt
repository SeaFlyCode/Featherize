package com.featherize.app.domain

import android.net.Uri
import java.io.File

enum class MediaType { IMAGE, VIDEO }

enum class CompressionStatus { PENDING, RUNNING, DONE, FAILED, EXPORTED }

data class MediaItem(
    val uri: Uri,
    val displayName: String,
    val originalSizeBytes: Long,
    val type: MediaType,
    val status: CompressionStatus = CompressionStatus.PENDING,
    val progress: Float = 0f,
    val compressedFile: File? = null,
    val resultUri: Uri? = null,
    val resultSizeBytes: Long? = null,
    val errorMessage: String? = null,
) {
    val savedBytes: Long?
        get() = resultSizeBytes?.let { originalSizeBytes - it }

    val savedRatio: Float?
        get() = resultSizeBytes?.let { 1f - (it.toFloat() / originalSizeBytes.coerceAtLeast(1)) }
}
