package com.featherize.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
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

/**
 * Runs the actual compression loop off the Activity/ViewModel lifecycle, as an Android
 * foreground service — so leaving the app (switching apps, locking the screen) doesn't
 * interrupt a large batch of photos/videos mid-compression.
 */
class CompressionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repository: MediaRepository
    private lateinit var imageCompressor: ImageCompressor
    private lateinit var videoCompressor: VideoCompressor

    override fun onCreate() {
        super.onCreate()
        repository = MediaRepository(this)
        imageCompressor = ImageCompressor(this)
        videoCompressor = VideoCompressor(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val total = CompressionQueue.items.value.size
        startForeground(NOTIFICATION_ID, buildProgressNotification(0, total, ""))
        scope.launch {
            runCompression()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runCompression() {
        val preset = CompressionQueue.preset
        val items = CompressionQueue.items.value
        Log.d(TAG, "service: compressing ${items.size} item(s) at $preset")

        items.forEachIndexed { index, item ->
            CompressionQueue.update(item.uri) { it.copy(status = CompressionStatus.RUNNING, progress = 0f) }
            updateNotification(buildProgressNotification(index, items.size, item.displayName))
            try {
                val outputFile = repository.cacheOutputFile(item.displayName, item.type)
                when (item.type) {
                    MediaType.IMAGE -> imageCompressor.compress(item.uri, preset, outputFile)
                    MediaType.VIDEO -> videoCompressor.compress(item.uri, preset, outputFile) { progress ->
                        CompressionQueue.update(item.uri) { it.copy(progress = progress) }
                    }
                }
                Log.d(TAG, "service: done ${item.displayName} (${outputFile.length()} bytes)")
                CompressionQueue.update(item.uri) {
                    it.copy(
                        status = CompressionStatus.DONE,
                        progress = 1f,
                        compressedFile = outputFile,
                        resultSizeBytes = outputFile.length(),
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "service: failed ${item.displayName}", t)
                CompressionQueue.update(item.uri) {
                    it.copy(status = CompressionStatus.FAILED, errorMessage = t.message ?: "Erreur inconnue")
                }
            }
        }

        CompressionQueue.finish()
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

    private fun buildProgressNotification(index: Int, total: Int, currentName: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Compression en cours ($index/$total)")
            .setContentText(currentName)
            .setProgress(total.coerceAtLeast(1), index, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .build()
    }

    private fun updateNotification(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
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
