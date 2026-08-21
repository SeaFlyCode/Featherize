package com.featherize.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import com.featherize.app.data.MediaRepository

class FeatherizeApplication : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        // Catches leftover compressed files from a process kill/crash mid-batch — the normal
        // per-export cleanup (CompressionViewModel.exportOne) can't run in that case.
        MediaRepository(this).clearCompressedCache()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
    }
}
