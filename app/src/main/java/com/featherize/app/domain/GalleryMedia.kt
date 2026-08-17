package com.featherize.app.domain

import android.net.Uri

data class GalleryMedia(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val type: MediaType,
    val dateAddedSeconds: Long,
    val dateModifiedSeconds: Long,
)

fun GalleryMedia.toMediaItem(): MediaItem = MediaItem(
    uri = uri,
    displayName = displayName,
    originalSizeBytes = sizeBytes,
    type = type,
)
