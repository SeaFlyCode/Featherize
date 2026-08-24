package com.featherize.app.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.featherize.app.data.ExportMode
import com.featherize.app.data.MediaRepository
import com.featherize.app.data.recoverableWriteIntentSender
import com.featherize.app.data.trashRequestIntentSender
import com.featherize.app.domain.CompressionPreset
import com.featherize.app.domain.CompressionStatus
import com.featherize.app.domain.GalleryMedia
import com.featherize.app.domain.MediaItem
import com.featherize.app.domain.MediaTypeFilter
import com.featherize.app.domain.SizeFilter
import com.featherize.app.domain.SortOption
import com.featherize.app.domain.applyFilters
import com.featherize.app.domain.applySort
import com.featherize.app.domain.toMediaItem
import com.featherize.app.service.CompressionQueue
import com.featherize.app.service.CompressionQueueApi
import com.featherize.app.service.CompressionService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ScreenState { PICKING, PROGRESS, RESULT }

data class UiState(
    val items: List<MediaItem> = emptyList(),
    val preset: CompressionPreset = CompressionPreset.MEDIUM,
    val screen: ScreenState = ScreenState.PICKING,
    val exportMode: ExportMode = ExportMode.COPY,
    val isProcessing: Boolean = false,
    val galleryMedia: List<GalleryMedia> = emptyList(),
    val selectedUris: Set<Uri> = emptySet(),
    val permissionGranted: Boolean = false,
    val isLoadingGallery: Boolean = false,
    val typeFilter: MediaTypeFilter = MediaTypeFilter.ALL,
    val sizeFilter: SizeFilter = SizeFilter.ALL,
    val sortOption: SortOption = SortOption.DATE_NEWEST,
    val manageStorageGranted: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
    val isExporting: Boolean = false,
) {
    val filteredGalleryMedia: List<GalleryMedia>
        get() = galleryMedia.applyFilters(typeFilter, sizeFilter).applySort(sortOption)
}

private const val TAG = "Featherize"

class CompressionViewModel(
    application: Application,
    private val queue: CompressionQueueApi,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(application, CompressionQueue)

    private val repository = MediaRepository(application)

    private val _uiState = MutableStateFlow(
        UiState(
            items = queue.items.value,
            isProcessing = queue.isRunning.value,
            screen = if (queue.isRunning.value) ScreenState.PROGRESS else ScreenState.PICKING,
        ),
    )
    val uiState: StateFlow<UiState> = _uiState

    private val _writePermissionRequest = MutableSharedFlow<IntentSenderRequest>(extraBufferCapacity = 1)
    val writePermissionRequest: SharedFlow<IntentSenderRequest> = _writePermissionRequest.asSharedFlow()
    private var pendingWritePermission: CompletableDeferred<Boolean>? = null

    init {
        // Compression itself runs in CompressionService, independent of this ViewModel's
        // lifecycle — we just mirror its state so the UI stays live (and picks the right
        // screen back up if the app was left mid-compression and comes back later).
        viewModelScope.launch {
            queue.items.collect { items -> _uiState.update { it.copy(items = items) } }
        }
        viewModelScope.launch {
            queue.isRunning.collect { running -> _uiState.update { it.copy(isProcessing = running) } }
        }
        viewModelScope.launch {
            queue.batchCompleted.collect {
                _uiState.update { it.copy(screen = ScreenState.RESULT) }
            }
        }
        refreshManageStorageStatus()
        refreshBatteryOptimizationStatus()
    }

    fun refreshManageStorageStatus() {
        _uiState.update { it.copy(manageStorageGranted = repository.hasManageExternalStorage()) }
    }

    fun refreshBatteryOptimizationStatus() {
        _uiState.update { it.copy(batteryOptimizationIgnored = repository.isIgnoringBatteryOptimizations()) }
    }

    fun onWritePermissionResult(granted: Boolean) {
        pendingWritePermission?.complete(granted)
        pendingWritePermission = null
    }

    fun onPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(permissionGranted = granted) }
        if (granted) loadGallery()
    }

    fun loadGallery() {
        if (_uiState.value.isLoadingGallery) return
        _uiState.update { it.copy(isLoadingGallery = true) }
        viewModelScope.launch {
            val media = withContext(Dispatchers.IO) { repository.queryAllMedia() }
            _uiState.update { it.copy(galleryMedia = media, isLoadingGallery = false) }
        }
    }

    fun toggleSelection(uri: Uri) {
        _uiState.update { state ->
            val selected = state.selectedUris
            state.copy(selectedUris = if (uri in selected) selected - uri else selected + uri)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedUris = emptySet()) }
    }

    fun setPreset(preset: CompressionPreset) {
        _uiState.update { it.copy(preset = preset) }
    }

    fun setExportMode(mode: ExportMode) {
        _uiState.update { it.copy(exportMode = mode) }
    }

    fun setTypeFilter(filter: MediaTypeFilter) {
        _uiState.update { it.copy(typeFilter = filter) }
    }

    fun setSizeFilter(filter: SizeFilter) {
        _uiState.update { it.copy(sizeFilter = filter) }
    }

    fun setSortOption(option: SortOption) {
        _uiState.update { it.copy(sortOption = option) }
    }

    fun startCompression() {
        val state = _uiState.value
        Log.d(TAG, "startCompression: selected=${state.selectedUris.size} isProcessing=${state.isProcessing}")
        if (state.selectedUris.isEmpty() || state.isProcessing) return
        val selectedItems = state.galleryMedia
            .filter { it.uri in state.selectedUris }
            .map { it.toMediaItem() }

        queue.start(selectedItems, state.preset)
        _uiState.update { it.copy(screen = ScreenState.PROGRESS) }

        val context = getApplication<Application>()
        ContextCompat.startForegroundService(context, Intent(context, CompressionService::class.java))
    }

    /** Takes effect once the file currently compressing finishes — see CompressionService. */
    fun cancelCompression() {
        queue.requestCancel()
    }

    fun exportResults() {
        val state = _uiState.value
        if (state.isExporting) return
        val mode = state.exportMode
        val doneItems = state.items.filter { it.status == CompressionStatus.DONE && it.compressedFile != null }
        if (doneItems.isEmpty()) return

        _uiState.update { it.copy(isExporting = true) }
        viewModelScope.launch {
            try {
                for (item in doneItems) {
                    val file = item.compressedFile ?: continue
                    exportOne(item, file, mode)
                }

                val allSucceeded = doneItems.all { done ->
                    val current = _uiState.value.items.find { it.uri == done.uri }
                    current?.status == CompressionStatus.EXPORTED && current.errorMessage == null
                }
                if (allSucceeded) reset()
            } finally {
                _uiState.update { it.copy(isExporting = false) }
            }
        }
    }

    /**
     * Writes the compressed file as a brand new MediaStore item — this alone is what
     * [CompressionStatus.EXPORTED] means, and it never touches the original. For
     * [ExportMode.REPLACE], the original is trashed only afterward, as a separate best-effort
     * step ([trashOriginalAfterExport]): if that fails or is denied, the export still counts as
     * successful — the user keeps both files rather than losing the original, which is what a
     * prior version of this code risked (see [MediaRepository.export]).
     */
    private suspend fun exportOne(item: MediaItem, file: java.io.File, mode: ExportMode) {
        try {
            val resultUri = withContext(Dispatchers.IO) { repository.export(item, file) }
            Log.d(TAG, "exported ${item.displayName} -> $resultUri")
            file.delete()
            updateItem(item.uri) {
                it.copy(status = CompressionStatus.EXPORTED, resultUri = resultUri, compressedFile = null)
            }
            if (mode == ExportMode.REPLACE) {
                trashOriginalAfterExport(item)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "export failed for ${item.displayName}", t)
            updateItem(item.uri) { it.copy(errorMessage = t.message ?: "Échec de l'export") }
        }
    }

    /**
     * Best-effort: moves the original to the system trash (API 30+) so it's recoverable from
     * Gallery/Photos for ~30 days even if this whole flow, or the platform's consent dialog,
     * misbehaves — a hard, unrecoverable delete is never issued from here.
     */
    private suspend fun trashOriginalAfterExport(item: MediaItem) {
        try {
            withContext(Dispatchers.IO) { repository.trashOriginal(item.uri) }
            Log.d(TAG, "trashed original for ${item.displayName}")
        } catch (e: SecurityException) {
            val intentSender = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    try {
                        trashRequestIntentSender(getApplication(), item.uri)
                    } catch (t: Throwable) {
                        Log.e(TAG, "trash request failed for ${item.displayName}", t)
                        null
                    }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> recoverableWriteIntentSender(e)
                else -> null
            }
            if (intentSender == null) {
                Log.w(TAG, "could not trash original for ${item.displayName}, no recovery available", e)
                return
            }
            Log.d(TAG, "requesting trash consent for ${item.displayName}")
            val deferred = CompletableDeferred<Boolean>()
            pendingWritePermission = deferred
            _writePermissionRequest.emit(IntentSenderRequest.Builder(intentSender).build())
            val granted = deferred.await()
            if (!granted) {
                Log.w(TAG, "trash consent denied for ${item.displayName}, original kept")
                return
            }
            // MediaStore.createTrashRequest performs the trashing itself once granted (unlike
            // the write-request flow) — nothing left to do here on success, on API 30+.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                try {
                    withContext(Dispatchers.IO) { repository.trashOriginal(item.uri) }
                } catch (t: Throwable) {
                    Log.w(TAG, "trash retry failed for ${item.displayName}, original kept", t)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "trash failed for ${item.displayName}, original kept", t)
        }
    }

    fun reset() {
        _uiState.update {
            it.copy(items = emptyList(), selectedUris = emptySet(), screen = ScreenState.PICKING, isProcessing = false)
        }
        loadGallery()
    }

    private fun updateItem(uri: Uri, transform: (MediaItem) -> MediaItem) {
        _uiState.update { state ->
            state.copy(items = state.items.map { if (it.uri == uri) transform(it) else it })
        }
    }
}
