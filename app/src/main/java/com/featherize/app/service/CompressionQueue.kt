package com.featherize.app.service

import android.net.Uri
import com.featherize.app.domain.CompressionPreset
import com.featherize.app.domain.MediaItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide compression state, shared between [CompressionService] (which does the actual
 * work, independent of any Activity) and the UI (which just observes it). This is what lets
 * compression keep running — and survive — the user leaving the app.
 */
object CompressionQueue {

    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items: StateFlow<List<MediaItem>> = _items

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _batchCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val batchCompleted: SharedFlow<Unit> = _batchCompleted.asSharedFlow()

    var preset: CompressionPreset = CompressionPreset.MEDIUM
        private set

    fun start(items: List<MediaItem>, preset: CompressionPreset) {
        this.preset = preset
        _items.value = items
        _isRunning.value = true
    }

    fun update(uri: Uri, transform: (MediaItem) -> MediaItem) {
        _items.update { list -> list.map { if (it.uri == uri) transform(it) else it } }
    }

    suspend fun finish() {
        _isRunning.value = false
        _batchCompleted.emit(Unit)
    }
}
