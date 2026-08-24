package com.featherize.app.domain

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Transcodes a video track (H.264) to a target resolution/bitrate using an
 * encoder-input Surface fed directly by the decoder's output Surface — no
 * manual pixel copying. Audio track is copied through untouched.
 */
class VideoCompressor(private val context: Context) {

    fun compress(
        sourceUri: Uri,
        preset: CompressionPreset,
        outputFile: File,
        onProgress: (Float) -> Unit,
    ): File {
        val extractor = MediaExtractor()
        context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
            extractor.setDataSource(pfd.fileDescriptor)
        } ?: error("Fichier vidéo introuvable")

        val videoTrackIndex = selectTrack(extractor, "video/")
        val audioTrackIndex = selectTrack(extractor, "audio/")
        require(videoTrackIndex >= 0) { "Aucune piste vidéo trouvée" }

        val inputVideoFormat = extractor.getTrackFormat(videoTrackIndex)
        val durationUs = if (inputVideoFormat.containsKey(MediaFormat.KEY_DURATION)) {
            inputVideoFormat.getLong(MediaFormat.KEY_DURATION)
        } else 1L

        val (outWidth, outHeight) = targetResolution(inputVideoFormat, preset)
        val bitrate = (outWidth * outHeight * preset.videoBitsPerPixel * 30).roundToInt()

        val outputFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outWidth, outHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        // The output format above never carries HDR color metadata (BT.2020/PQ/HLG) — H.264
        // baseline output is implicitly SDR/BT.709. Left alone, a decoder fed an HDR source
        // (e.g. this device's 8K capture) hands the encoder raw HDR pixel data with nothing
        // downstream describing it as such: a technically-valid but incoherent stream that some
        // decoders (the system thumbnail extractor observed via
        // "MediaMetadataRetrieverJNI: getFrameAtTime: videoFrame is a NULL pointer") fail to read
        // a frame from — which is what was making MediaProvider treat freshly-exported compressed
        // videos as unreadable and prune them from MediaStore right after indexing them. Request
        // SDR tone-mapping on the decoder so the surface handed to the encoder is coherent SDR.
        val sourceTransfer = if (inputVideoFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
            inputVideoFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
        } else {
            -1
        }
        val isHdrSource = sourceTransfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
            sourceTransfer == MediaFormat.COLOR_TRANSFER_HLG
        val decoder = MediaCodec.createDecoderByType(inputVideoFormat.getString(MediaFormat.KEY_MIME)!!)
        if (isHdrSource && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Not every decoder honors this request the same way; if configure() rejects it
            // outright, fall back to the plain format rather than losing the whole export.
            val toneMappedFormat = MediaFormat(inputVideoFormat).apply {
                setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            try {
                decoder.configure(toneMappedFormat, inputSurface, null, 0)
            } catch (t: Throwable) {
                decoder.configure(inputVideoFormat, inputSurface, null, 0)
            }
        } else {
            decoder.configure(inputVideoFormat, inputSurface, null, 0)
        }
        decoder.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        // The decode→encode path is surface-to-surface and never rotates pixels, so the source's
        // rotation hint (how portrait video ends up stored as landscape + a "rotate on playback"
        // flag) must be copied through explicitly, or every portrait video plays back sideways.
        muxer.setOrientationHint(inputVideoFormat.getInteger(MediaFormat.KEY_ROTATION, 0))

        var muxerVideoTrack = -1
        var muxerAudioTrack = -1
        var muxerStarted = false
        var videoSamplesWritten = 0
        var decoderFramesRendered = 0

        extractor.selectTrack(videoTrackIndex)
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var decoderDone = false
        var encoderDone = false

        while (!encoderDone) {
            if (!inputDone) {
                val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inputIndex)!!
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            if (!decoderDone) {
                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIndex >= 0) {
                    val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    // With an output Surface, the decoded frame never lands in an accessible
                    // ByteBuffer — bufferInfo.size is 0 for a real frame just as often as for an
                    // empty one, so gating "render" on size > 0 silently drops nearly every frame
                    // (the encoder's input surface gets almost nothing, and the muxed output ends
                    // up with a video track that has no real codec data — no avc1/csd, empty
                    // stsd — while the copied-through audio track is fine, which is exactly why
                    // the exported file was being misidentified as audio-only downstream). Render
                    // every frame that isn't the empty end-of-stream marker.
                    decoder.releaseOutputBuffer(outIndex, !eos)
                    if (eos) {
                        encoder.signalEndOfInputStream()
                        decoderDone = true
                    } else {
                        decoderFramesRendered++
                        if (durationUs > 0) {
                            onProgress((bufferInfo.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 0.95f))
                        }
                    }
                }
            }

            var encoderOutputAvailable = true
            while (encoderOutputAvailable) {
                val encIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    encIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> encoderOutputAvailable = false
                    encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        android.util.Log.d("Featherize", "encoder format changed: ${encoder.outputFormat}")
                        muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                        if (audioTrackIndex >= 0) {
                            muxerAudioTrack = muxer.addTrack(extractor.getTrackFormat(audioTrackIndex))
                        }
                        muxer.start()
                        muxerStarted = true
                    }
                    encIndex >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(encIndex)!!
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            android.util.Log.d("Featherize", "codec config buffer, size=${bufferInfo.size}")
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && muxerStarted) {
                            muxer.writeSampleData(muxerVideoTrack, encodedData, bufferInfo)
                            videoSamplesWritten++
                        }
                        val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(encIndex, false)
                        if (eos) {
                            encoderDone = true
                            encoderOutputAvailable = false
                        }
                    }
                }
            }
        }

        decoder.stop(); decoder.release()
        encoder.stop(); encoder.release()
        inputSurface.release()
        extractor.unselectTrack(videoTrackIndex)

        android.util.Log.d(
            "Featherize",
            "decoderFramesRendered=$decoderFramesRendered videoSamplesWritten=$videoSamplesWritten muxerVideoTrack=$muxerVideoTrack",
        )
        check(muxerStarted) { "Échec de l'encodage : aucun flux vidéo produit" }

        if (audioTrackIndex >= 0) {
            copyAudioTrack(sourceUri, audioTrackIndex, muxer, muxerAudioTrack, onProgress)
        }

        muxer.stop()
        muxer.release()
        extractor.release()

        onProgress(1f)
        return outputFile
    }

    /**
     * Runs after the video track is fully muxed, so it's reported as the last 95-100% of
     * [onProgress] — without this, a video with a long audio track leaves the notification
     * looking frozen at 95% while this synchronous copy loop runs.
     */
    private fun copyAudioTrack(
        sourceUri: Uri,
        audioTrackIndex: Int,
        muxer: MediaMuxer,
        muxerTrack: Int,
        onProgress: (Float) -> Unit,
    ) {
        val audioExtractor = MediaExtractor()
        context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
            audioExtractor.setDataSource(pfd.fileDescriptor)
        }
        audioExtractor.selectTrack(audioTrackIndex)
        val format = audioExtractor.getTrackFormat(audioTrackIndex)

        val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else DEFAULT_BUFFER_SIZE
        val audioDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else 1L
        val buffer = java.nio.ByteBuffer.allocate(maxSize)
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val sampleSize = audioExtractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break
            bufferInfo.offset = 0
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = audioExtractor.sampleTime
            bufferInfo.flags = audioExtractor.sampleFlags
            muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
            if (audioDurationUs > 0) {
                val audioProgress = bufferInfo.presentationTimeUs.toFloat() / audioDurationUs
                onProgress((0.95f + 0.05f * audioProgress).coerceIn(0.95f, 1f))
            }
            audioExtractor.advance()
        }
        audioExtractor.release()
    }

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    private fun targetResolution(format: MediaFormat, preset: CompressionPreset): Pair<Int, Int> {
        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        val shortSide = min(width, height)
        if (shortSide <= preset.videoMaxShortSide) {
            return alignEven(width) to alignEven(height)
        }
        val scale = preset.videoMaxShortSide.toFloat() / shortSide
        return alignEven((width * scale).roundToInt()) to alignEven((height * scale).roundToInt())
    }

    private fun alignEven(value: Int): Int = if (value % 2 == 0) value else value - 1

    companion object {
        private val TIMEOUT_US = TimeUnit.MILLISECONDS.toMicros(10)
        private const val DEFAULT_BUFFER_SIZE = 1 shl 20
    }
}
