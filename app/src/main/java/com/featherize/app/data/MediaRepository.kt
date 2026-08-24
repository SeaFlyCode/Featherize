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
 * Requests permission to move a single MediaStore item the app doesn't own to the system trash
 * (API 30+; recoverable there for ~30 days from the Gallery/Photos app, unlike a hard delete).
 * Used to remove the original after [MediaRepository.export] has already written a verified
 * replacement, for [ExportMode.REPLACE] — never before.
 */
@RequiresApi(Build.VERSION_CODES.R)
fun trashRequestIntentSender(context: Context, uri: Uri): IntentSender =
    MediaStore.createTrashRequest(context.contentResolver, listOf(uri), true).intentSender

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
     * Publishes a compressed file back to shared storage via MediaStore, always as a brand
     * new item — the original is NEVER opened for writing here. A previous version used
     * ContentResolver's "wt" (write-truncate) mode to overwrite the original in place for
     * [ExportMode.REPLACE]; "wt" truncates the target immediately, before a single byte of the
     * replacement is written, and ContentResolver offers no atomic swap for an existing
     * MediaStore entry. Any failure mid-copy (disk full, process death, revoked permission)
     * left the original truncated/corrupted with no way back — this caused real data loss.
     * For [ExportMode.REPLACE], the caller ([CompressionViewModel.exportOne]) only trashes the
     * original — via [trashOriginal] — after this call has returned a fully-written new item.
     */
    fun export(item: MediaItem, compressedFile: File): Uri {
        if (!compressedFile.exists()) {
            error("Le fichier compressé a expiré, relance la compression")
        }
        val mimeType = if (item.type == MediaType.IMAGE) "image/jpeg" else "video/mp4"
        val displayName = compressedFile.name

        // With "All files access" granted, write straight to the filesystem and trigger an
        // explicit scan instead of going through MediaStore's insert()/FUSE write path. On this
        // device (Samsung One UI, Android 16) that path was observed to create a real, fully
        // written, correctly named file — but the MediaStore row for it vanished within ~1-2s of
        // being indexed regardless of RELATIVE_PATH, IS_PENDING, or DATE_TAKEN being set, and
        // regardless of HDR tone-mapping — every export mode hit it. A direct filesystem write +
        // MediaScannerConnection scan is the classic pre-scoped-storage path and sidesteps
        // whatever in that FUSE/insert pipeline was pruning the row.
        if (hasManageExternalStorage()) {
            return exportViaRawFile(item, compressedFile, mimeType, displayName)
        }

        val resolver = context.contentResolver
        val collection = if (item.type == MediaType.IMAGE) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        // Without an explicit RELATIVE_PATH, MediaStore drops the new item into the collection's
        // default bucket (Movies/, Pictures/) instead of the original's folder (DCIM/Camera,
        // WhatsApp Video...) — the compressed file still exists, just not where the user expects
        // to find it, which reads as "the video disappeared". RELATIVE_PATH only exists as a
        // MediaStore column from API 29 (Q) on — querying it below that throws (unknown column).
        val relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.query(
                item.uri,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                null, null, null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } else {
            null
        }

        // Without DATE_TAKEN, MediaProvider's Photo Picker sync ("PickerDbFacade") logs "Could
        // not get first date taken millis" / "Unable to promote cloud media" for the new row and
        // — on recent Android builds (observed on Android 16) — the row gets pruned from
        // MediaStore entirely shortly after, while the file stays on disk untouched. MediaMuxer's
        // output has no capture-date metadata for the retriever to read, so it must be supplied
        // explicitly; fall back to the original's own DATE_TAKEN, or now if it has none either.
        val dateTaken = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.query(
                item.uri,
                arrayOf(MediaStore.MediaColumns.DATE_TAKEN),
                null, null, null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        } else {
            null
        }

        // IS_PENDING tells MediaProvider "not ready yet, don't scan/index or surface this in
        // queries". Without it, some OEM MediaProvider forks (observed on Samsung One UI) scan
        // the row as soon as it's inserted — while bytes are still being copied — read it as
        // invalid/incomplete, and prune the DB row outright. The file survives on disk (findable
        // by path) but is gone from MediaStore, which is exactly what reads as "the compressed
        // video disappeared". Clearing the flag after the copy triggers a proper, complete scan.
        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (relativePath != null) put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.DATE_TAKEN, dateTaken ?: System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val newUri = resolver.insert(collection, values) ?: error("Impossible de créer le fichier exporté")
        val out = resolver.openOutputStream(newUri) ?: error("Impossible d'ouvrir le fichier exporté en écriture")
        out.use { compressedFile.inputStream().use { input -> input.copyTo(it) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cleared = android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(newUri, cleared, null, null)
        }
        return newUri
    }

    /**
     * Writes the compressed file directly to the filesystem (requires "All files access") and
     * asks [MediaScannerConnection] to index it, instead of going through MediaStore's insert().
     * See the comment at the [export] call site for why this path exists.
     */
    private fun exportViaRawFile(item: MediaItem, compressedFile: File, mimeType: String, displayName: String): Uri {
        val relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.query(
                item.uri,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                null, null, null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } else {
            null
        }
        val defaultDir = if (item.type == MediaType.IMAGE) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES
        val dir = File(Environment.getExternalStorageDirectory(), relativePath ?: defaultDir)
        dir.mkdirs()
        val destFile = File(dir, displayName)
        compressedFile.copyTo(destFile, overwrite = true)

        val latch = java.util.concurrent.CountDownLatch(1)
        var scannedUri: Uri? = null
        android.media.MediaScannerConnection.scanFile(
            context,
            arrayOf(destFile.absolutePath),
            arrayOf(mimeType),
        ) { _, uri -> scannedUri = uri; latch.countDown() }
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        val uri = scannedUri ?: error("Le scan média a échoué après l'export")

        // The system scanner's own content sniffing sometimes misreads this app's muxed MP4s
        // (observed: a real video track classified as audio/mp4, landing the row in the audio
        // collection). Force it back to the correct type/collection by id via the Files
        // provider, which spans all media types — best-effort, the file is still valid and
        // findable even if this update is refused.
        val id = ContentUris.parseId(uri)
        try {
            val fix = android.content.ContentValues().apply { put(MediaStore.MediaColumns.MIME_TYPE, mimeType) }
            context.contentResolver.update(MediaStore.Files.getContentUri("external", id), fix, null, null)
        } catch (t: Throwable) {
            // Best-effort: leave the row under whatever type the scanner picked.
        }
        return uri
    }

    /**
     * Removes the original item after [export] has produced a verified replacement — the only
     * point at which [ExportMode.REPLACE] is allowed to touch the source file. On API 30+ this
     * moves it to the system trash (recoverable from Gallery/Photos for ~30 days) instead of
     * deleting outright, via the [MediaStore.MediaColumns.IS_TRASHED] flag — [MediaStore] has no
     * trash concept below API 30, so older devices fall back to a hard delete there. Throws
     * [SecurityException] (recoverable via [trashRequestIntentSender] on API 30+, or the
     * pre-30 [RecoverableSecurityException] path from [recoverableWriteIntentSender]) if the
     * user hasn't granted access.
     */
    fun trashOriginal(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.IS_TRASHED, 1)
            }
            context.contentResolver.update(uri, values, null, null)
        } else {
            context.contentResolver.delete(uri, null, null)
        }
    }
}
