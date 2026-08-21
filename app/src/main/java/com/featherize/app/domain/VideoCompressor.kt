package com.featherize.app.domain

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
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

        val decoder = MediaCodec.createDecoderByType(inputVideoFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(inputVideoFormat, inputSurface, null, 0)
        decoder.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        // The decode→encode path is surface-to-surface and never rotates pixels, so the source's
        // rotation hint (how portrait video ends up stored as landscape + a "rotate on playback"
        // flag) must be copied through explicitly, or every portrait video plays back sideways.
        muxer.setOrientationHint(inputVideoFormat.getInteger(MediaFormat.KEY_ROTATION, 0))

        var muxerVideoTrack = -1
        var muxerAudioTrack = -1
        var muxerStarted = false

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
                    decoder.releaseOutputBuffer(outIndex, bufferInfo.size > 0)
                    if (eos) {
                        encoder.signalEndOfInputStream()
                        decoderDone = true
                    } else if (durationUs > 0) {
                        onProgress((bufferInfo.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 0.95f))
                    }
                }
            }

            var encoderOutputAvailable = true
            while (encoderOutputAvailable) {
                val encIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    encIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> encoderOutputAvailable = false
                    encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
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
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && muxerStarted) {
                            muxer.writeSampleData(muxerVideoTrack, encodedData, bufferInfo)
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
