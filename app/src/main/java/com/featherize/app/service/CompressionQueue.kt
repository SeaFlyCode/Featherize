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
 * Public surface of [CompressionQueue], extracted so [CompressionService] and
 * [com.featherize.app.viewmodel.CompressionViewModel] depend on an interface rather than the
 * singleton directly — a fake implementation can stand in for tests.
 */
interface CompressionQueueApi {
    val items: StateFlow<List<MediaItem>>
    val isRunning: StateFlow<Boolean>
    val batchCompleted: SharedFlow<Unit>
    val cancelRequested: StateFlow<Boolean>
    val preset: CompressionPreset

    fun start(items: List<MediaItem>, preset: CompressionPreset)
    fun update(uri: Uri, transform: (MediaItem) -> MediaItem)
    fun requestCancel()
    suspend fun finish()
}

/**
 * Process-wide compression state, shared between [CompressionService] (which does the actual
 * work, independent of any Activity) and the UI (which just observes it). This is what lets
 * compression keep running — and survive — the user leaving the app.
 */
object CompressionQueue : CompressionQueueApi {

    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    override val items: StateFlow<List<MediaItem>> = _items

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning

    private val _batchCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val batchCompleted: SharedFlow<Unit> = _batchCompleted.asSharedFlow()

    private val _cancelRequested = MutableStateFlow(false)
    override val cancelRequested: StateFlow<Boolean> = _cancelRequested

    override var preset: CompressionPreset = CompressionPreset.MEDIUM
        private set

    override fun start(items: List<MediaItem>, preset: CompressionPreset) {
        this.preset = preset
        _items.value = items
        _isRunning.value = true
        _cancelRequested.value = false
    }

    override fun update(uri: Uri, transform: (MediaItem) -> MediaItem) {
        _items.update { list -> list.map { if (it.uri == uri) transform(it) else it } }
    }

    override fun requestCancel() {
        _cancelRequested.value = true
    }

    override suspend fun finish() {
        _isRunning.value = false
        _batchCompleted.emit(Unit)
    }
}
