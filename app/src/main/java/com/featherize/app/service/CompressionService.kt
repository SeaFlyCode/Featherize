package com.featherize.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.featherize.app.MainActivity
import com.featherize.app.data.MediaRepository
import com.featherize.app.domain.CompressionStatus
import com.featherize.app.domain.ImageCompressor
import com.featherize.app.domain.MediaType
import com.featherize.app.domain.VideoCompressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "Featherize"
private const val CHANNEL_ID = "compression"
private const val NOTIFICATION_ID = 42
private val WAKE_LOCK_TIMEOUT_MS = java.util.concurrent.TimeUnit.HOURS.toMillis(6)

/**
 * Runs the actual compression loop off the Activity/ViewModel lifecycle, as an Android
 * foreground service — so leaving the app (switching apps, locking the screen) doesn't
 * interrupt a large batch of photos/videos mid-compression.
 */
class CompressionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue: CompressionQueueApi = CompressionQueue
    private lateinit var repository: MediaRepository
    private lateinit var imageCompressor: ImageCompressor
    private lateinit var videoCompressor: VideoCompressor
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifiedPercent = -1
    private var compressionJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        repository = MediaRepository(this)
        imageCompressor = ImageCompressor(this)
        videoCompressor = VideoCompressor(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Guards against a double-tap (or any other duplicate start) launching a second
        // compression loop over the same queue while one is already running — the ViewModel's
        // own isProcessing check is UI-state and can race with this.
        if (compressionJob?.isActive == true) {
            Log.w(TAG, "onStartCommand: compression already running, ignoring duplicate start")
            return START_NOT_STICKY
        }

        val total = queue.items.value.size
        startForeground(NOTIFICATION_ID, buildProgressNotification(0, total, "Préparation…", 0))
        acquireWakeLock()
        compressionJob = scope.launch {
            try {
                runCompression()
            } finally {
                releaseWakeLock()
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Large batches can run for several minutes; without a wake lock, screen-off puts the CPU
     * to sleep and pauses the compression loop even though the foreground service itself is
     * still alive. Timeout is a safety net, not the expected runtime.
     */
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Featherize:compression").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private suspend fun runCompression() {
        val preset = queue.preset
        val items = queue.items.value
        Log.d(TAG, "service: compressing ${items.size} item(s) at $preset")

        for ((index, item) in items.withIndex()) {
            // Cancellation is checked between files rather than mid-file: the codec loop in
            // VideoCompressor has no suspension points to interrupt safely, so "Annuler" takes
            // effect once the file currently compressing finishes.
            if (queue.cancelRequested.value) {
                queue.update(item.uri) { it.copy(status = CompressionStatus.FAILED, errorMessage = "Annulé") }
                continue
            }

            queue.update(item.uri) { it.copy(status = CompressionStatus.RUNNING, progress = 0f) }
            notifyProgress(index, items.size, item.displayName, 0f)
            try {
                val outputFile = repository.cacheOutputFile(item.displayName, item.type)
                when (item.type) {
                    MediaType.IMAGE -> imageCompressor.compress(item.uri, preset, outputFile)
                    MediaType.VIDEO -> videoCompressor.compress(item.uri, preset, outputFile) { progress ->
                        queue.update(item.uri) { it.copy(progress = progress) }
                        notifyProgress(index, items.size, item.displayName, progress)
                    }
                }
                Log.d(TAG, "service: done ${item.displayName} (${outputFile.length()} bytes)")
                queue.update(item.uri) {
                    it.copy(
                        status = CompressionStatus.DONE,
                        progress = 1f,
                        compressedFile = outputFile,
                        resultSizeBytes = outputFile.length(),
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "service: failed ${item.displayName}", t)
                queue.update(item.uri) {
                    it.copy(status = CompressionStatus.FAILED, errorMessage = t.message ?: "Erreur inconnue")
                }
            }
        }

        queue.finish()
        postCompletionNotification(items.size)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Compression",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Progression de la compression des photos et vidéos" }
        manager.createNotificationChannel(channel)
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun buildProgressNotification(index: Int, total: Int, currentName: String, percent: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Compression en cours (${index + 1}/$total)")
            .setContentText(currentName)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .build()

    /**
     * Android silently throttles NotificationManager.notify() to roughly once per second — a
     * video's per-frame onProgress callback fires far more often than that, so most updates
     * were being dropped and the bar looked frozen. Only push a notify() when the displayed
     * percent actually changes, instead of on every callback tick.
     */
    private fun notifyProgress(index: Int, total: Int, currentName: String, itemProgress: Float) {
        val safeTotal = total.coerceAtLeast(1)
        val percent = (((index + itemProgress) / safeTotal) * 100).toInt().coerceIn(0, 100)
        if (percent == lastNotifiedPercent) return
        lastNotifiedPercent = percent
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildProgressNotification(index, total, currentName, percent))
    }

    private fun postCompletionNotification(total: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Compression terminée")
            .setContentText("$total fichier(s) traité(s)")
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID + 1, notification)
    }
}
