package com.featherize.app.data

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.featherize.app.domain.GalleryMedia
import com.featherize.app.domain.MediaItem
import com.featherize.app.domain.MediaType
import java.io.File

enum class ExportMode { REPLACE, COPY }

/**
 * On Android 10+ writing to a MediaStore item this app didn't create throws
 * [RecoverableSecurityException] carrying a system consent prompt. Isolated in its own
 * @RequiresApi method so the class reference is only verified on API levels that have it.
 */
@RequiresApi(Build.VERSION_CODES.Q)
fun recoverableWriteIntentSender(e: SecurityException): IntentSender? =
    (e as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender

/**
 * Requests write access for every [uris] in a single system consent dialog, instead of
 * one [RecoverableSecurityException] prompt per file. Android 11+ only.
 */
@RequiresApi(Build.VERSION_CODES.R)
fun batchWriteIntentSender(context: Context, uris: List<Uri>): IntentSender =
    MediaStore.createWriteRequest(context.contentResolver, uris).intentSender

class MediaRepository(private val context: Context) {

    /** True once the user has granted "All files access" from Settings — see [MainActivity]. */
    fun hasManageExternalStorage(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /**
     * True once the user has exempted the app from battery optimization. Some OEM battery
     * managers (MIUI, EMUI, Samsung...) kill foreground services during long background
     * compressions despite the wake lock unless the app is explicitly whitelisted.
     */
    fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Queries every image and video visible to the app across shared storage, newest first. */
    fun queryAllMedia(): List<GalleryMedia> {
        val items = mutableListOf<GalleryMedia>()
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        // RAW formats (DNG, CR2, NEF, ARW, RAF...) are classified as images by MediaStore but
        // aren't decodable by ImageDecoder/BitmapFactory — exclude them here so users never pick
        // a file that's guaranteed to fail after the batch has already started.
        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?) AND " +
            "${MediaStore.Files.FileColumns.MIME_TYPE} NOT IN (?, ?, ?, ?, ?, ?, ?)"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            "image/x-adobe-dng",
            "image/x-canon-cr2",
            "image/x-canon-crw",
            "image/x-nikon-nef",
            "image/x-sony-arw",
            "image/x-fuji-raf",
            "image/x-panasonic-raw",
        )
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

        context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val isVideo = cursor.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val type = if (isVideo) MediaType.VIDEO else MediaType.IMAGE
                val contentUri = if (isVideo) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                items += GalleryMedia(
                    uri = ContentUris.withAppendedId(contentUri, cursor.getLong(idCol)),
                    displayName = cursor.getString(nameCol) ?: "media",
                    sizeBytes = cursor.getLong(sizeCol),
                    type = type,
                    dateAddedSeconds = cursor.getLong(dateCol),
                    dateModifiedSeconds = cursor.getLong(modifiedCol),
                )
            }
        }
        return items
    }

    fun cacheOutputFile(displayName: String, type: MediaType): File {
        val dir = File(context.cacheDir, "compressed").apply { mkdirs() }
        val extension = if (type == MediaType.IMAGE) "jpg" else "mp4"
        val base = displayName.substringBeforeLast('.').ifBlank { "featherize" }
        return File(dir, "${base}_compressed_${System.currentTimeMillis()}.$extension")
    }

    /**
     * Compressed outputs otherwise accumulate forever in [Context.getCacheDir] — nothing else
     * deletes them. Called after each successful export and on app startup (crash/kill leftovers).
     */
    fun clearCompressedCache() {
        File(context.cacheDir, "compressed").listFiles()?.forEach { it.delete() }
    }

    /**
     * Publishes a compressed file back to shared storage via MediaStore.
     * [replace] deletes/overwrites the original entry; otherwise a new item is created.
     */
    fun export(item: MediaItem, compressedFile: File, mode: ExportMode): Uri {
        if (!compressedFile.exists()) {
            error("Le fichier compressé a expiré, relance la compression")
        }
        val resolver = context.contentResolver
        val collection = if (item.type == MediaType.IMAGE) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val mimeType = if (item.type == MediaType.IMAGE) "image/jpeg" else "video/mp4"
        val displayName = compressedFile.name

        if (mode == ExportMode.REPLACE) {
            // "wt" truncates the original immediately, so an interrupted write (disk full, app
            // killed) can leave it corrupted. There's no atomic swap available through
            // ContentResolver for an existing MediaStore entry, so the achievable mitigation is:
            // don't delete compressedFile until the caller confirms EXPORTED (see
            // CompressionViewModel.exportOne), giving a recovery path even if this throws.
            try {
                val out = resolver.openOutputStream(item.uri, "wt")
                    ?: error("Impossible d'ouvrir le fichier original en écriture")
                out.use { compressedFile.inputStream().use { input -> input.copyTo(it) } }
            } catch (t: Throwable) {
                throw IllegalStateException(
                    "Échec du remplacement, l'original peut être corrompu. Le fichier compressé est " +
                        "conservé et l'export sera retenté.",
                    t,
                )
            }
            return item.uri
        }

        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        }
        val newUri = resolver.insert(collection, values) ?: error("Impossible de créer le fichier exporté")
        val out = resolver.openOutputStream(newUri) ?: error("Impossible d'ouvrir le fichier exporté en écriture")
        out.use { compressedFile.inputStream().use { input -> input.copyTo(it) } }
        return newUri
    }
}
