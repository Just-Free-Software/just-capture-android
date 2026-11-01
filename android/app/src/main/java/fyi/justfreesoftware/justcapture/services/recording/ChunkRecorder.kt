package fyi.justfreesoftware.justcapture.services.recording

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

/** A chunk file the recorder has finished with, successfully or not. */
data class RecordedChunk(
    val index: Int,
    val file: File,
    val durationSec: Double,
    val fileSize: Long,
    /** Set when the file couldn't be written; the file has been deleted. */
    val errorDescription: String?
)

/**
 * Writes one recording into back-to-back MP4 chunks. Capture never stops
 * between chunks: a single video encoder and a single audio encoder run for
 * the whole recording, and each cut closes one [MediaMuxer] and opens the
 * next on a keyframe, so there's no gap and each chunk's duration comes from
 * the media timestamps, not the clock.
 *
 * Every chunk is a complete, standalone MP4 (H.264 + AAC), so it plays on
 * its own in Drive and in browsers. Chunks are written to a hidden
 * `.partial` file and only renamed to `chunk_N.mp4` once the muxer has
 * finished, so a file that wasn't finished properly is never reported.
 *
 * Video output and all muxing run on [muxThread]; audio is read and encoded
 * on [audioThread] and handed to the mux thread as copies. Listener calls
 * arrive on the main thread, chunks in index order.
 */
class ChunkRecorder(
    private val outputDirectory: File,
    chunkDurationSec: Double,
    private val firstIndex: Int,
    private val width: Int,
    private val height: Int,
    private val orientationHint: Int,
    recordAudio: Boolean,
    private val listener: Listener
) {
    interface Listener {
        fun onChunkStarted(index: Int)
        fun onChunkFinished(chunk: RecordedChunk)

        /** The recorder has already stopped taking frames; the caller should stop. */
        fun onFailure(message: String)

        /** Called once, after every chunk has been reported. [nextIndex] follows the last chunk. */
        fun onStopped(nextIndex: Int)
    }

    private companion object {
        const val TAG = "ChunkRecorder"
        const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        const val VIDEO_BIT_RATE = 6_000_000
        const val FRAME_RATE = 30
        const val AUDIO_BIT_RATE = 128_000
        const val AUDIO_SAMPLES_PER_FRAME = 1024

        /** Ask for the cut's keyframe this long before the boundary, so it lands on time. */
        const val SYNC_LEAD_US = 300_000L

        /** A manual cut makes no clip shorter than this; a file that short isn't worth uploading. */
        const val MIN_MANUAL_CUT_US = 1_000_000L

        /** Audio lags video by less than this; past it, a chunk waiting for its last audio is finished anyway. */
        const val AUDIO_WAIT_LIMIT_US = 1_000_000L

        /**
         * Encoded audio kept for replay into a new chunk: audio reaches the
         * muxer before the video captured at the same moment, so when a
         * chunk's first frame arrives, its opening audio arrived earlier.
         */
        const val AUDIO_HISTORY_US = 3_000_000L

        /** How long the first chunk waits for the audio encoder's format before going without audio. */
        const val AUDIO_FORMAT_WAIT_US = 1_000_000L

        /** After stop, give up waiting for the encoders to drain after this long. */
        const val STOP_TIMEOUT_MS = 3_000L
    }

    private val chunkDurationUs = (chunkDurationSec * 1_000_000).toLong()

    /** Turned off if the microphone can't be opened; the recording then has no audio track. */
    private var recordAudio = recordAudio
    private val mainHandler = Handler(Looper.getMainLooper())
    private val muxThread = HandlerThread("ChunkRecorder.mux").apply { start() }
    private val muxHandler = Handler(muxThread.looper)
    private val audioThread = HandlerThread("ChunkRecorder.audio").apply { start() }
    private val audioHandler = Handler(audioThread.looper)

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var inputSurface: Surface? = null

    // ---- Mux-thread state ----

    private class Segment(
        val index: Int,
        val partial: File,
        val muxer: MediaMuxer,
        val videoTrack: Int,
        val audioTrack: Int,
        val startUs: Long
    ) {
        var endUs: Long? = null
        var lastVideoUs = startUs
        var lastAudioUs = Long.MIN_VALUE
        var lastAudioEndUs = Long.MIN_VALUE
        var lateFrames = 0
    }

    private class AudioSample(val ptsUs: Long, val durationUs: Long, val data: ByteArray, val flags: Int)

    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var current: Segment? = null

    /** The previous chunk, cut on a keyframe but still waiting for the audio up to that point. */
    private var draining: Segment? = null
    private var nextIndex = firstIndex
    private var cutRequested = false
    private var syncRequested = false
    private val recentAudio = ArrayDeque<AudioSample>()
    private var frameDurationUs = 1_000_000L / FRAME_RATE
    private var previousVideoUs = Long.MIN_VALUE
    private var startedAtUs = 0L

    /** Added to camera timestamps to put them on the audio (monotonic) clock. Found on the first frame. */
    private var videoClockOffsetUs: Long? = null

    private var videoDone = false
    private var audioDone = !recordAudio
    private var stopping = false
    private var finished = false

    // Read on the audio thread, written from any thread.
    @Volatile
    private var stopAudio = false

    /**
     * Creates and starts the encoders and returns the surface the camera
     * should draw into. Throws if an encoder can't be set up.
     */
    fun start(): Surface {
        startedAtUs = System.nanoTime() / 1000
        try {
            // Audio first, so its format is known by the first video frame.
            // A missing microphone shouldn't stop the video being recorded.
            if (recordAudio) {
                try {
                    startAudio()
                } catch (e: Exception) {
                    Log.w(TAG, "Recording without audio: ${e.message}")
                    releaseAudio()
                    recordAudio = false
                    audioDone = true
                }
            }
            return startVideo()
        } catch (e: Exception) {
            releaseCodecs()
            quitThreads()
            throw e
        }
    }

    private fun startVideo(): Surface {
        val format = MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            // Frequent keyframes keep a cut close to its target even when a
            // requested sync frame is late.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(VIDEO_MIME)
        videoCodec = codec
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                handleVideoOutput(codec, index, info)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                fail("The video encoder stopped: ${e.diagnosticInfo}")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                videoFormat = format
            }
        }, muxHandler)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        inputSurface = surface
        codec.start()
        return surface
    }

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        val sampleRate = listOf(48_000, 44_100).firstOrNull {
            AudioRecord.getMinBufferSize(it, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0
        } ?: throw IllegalStateException("The microphone isn't available.")
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.CAMCORDER,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer * 4, AUDIO_SAMPLES_PER_FRAME * 2 * 8)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("The microphone couldn't be opened.")
        }
        audioRecord = record

        val format = MediaFormat.createAudioFormat(AUDIO_MIME, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_SAMPLES_PER_FRAME * 2 * 4)
        }
        val codec = MediaCodec.createEncoderByType(AUDIO_MIME)
        audioCodec = codec
        val pcmBytesPerRead = AUDIO_SAMPLES_PER_FRAME * 2
        val frameDurationUs = AUDIO_SAMPLES_PER_FRAME * 1_000_000L / sampleRate
        val timestamp = AudioTimestamp()
        var framesRead = 0L
        var lastPtsUs = Long.MIN_VALUE

        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                val buffer = runCatching { codec.getInputBuffer(index) }.getOrNull() ?: return
                if (stopAudio) {
                    runCatching {
                        codec.queueInputBuffer(index, 0, 0, maxOf(lastPtsUs + 1, 0), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                    return
                }
                buffer.clear()
                // Blocks for about one AAC frame, which paces the loop.
                val read = record.read(buffer, minOf(buffer.capacity(), pcmBytesPerRead))
                if (read <= 0) {
                    runCatching { codec.queueInputBuffer(index, 0, 0, maxOf(lastPtsUs + 1, 0), 0) }
                    return
                }
                val frames = read / 2
                // Timestamp of this read's first frame on the monotonic clock,
                // from the HAL's own record of when frames were captured.
                var ptsUs = if (record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    (timestamp.nanoTime + (framesRead - timestamp.framePosition) * 1_000_000_000L / sampleRate) / 1000
                } else {
                    System.nanoTime() / 1000 - frames * 1_000_000L / sampleRate
                }
                if (ptsUs <= lastPtsUs) ptsUs = lastPtsUs + 1
                lastPtsUs = ptsUs
                framesRead += frames
                runCatching { codec.queueInputBuffer(index, 0, read, ptsUs, 0) }
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                var sample: AudioSample? = null
                if (!isConfig && info.size > 0) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(bytes, 0, info.size)
                        sample = AudioSample(info.presentationTimeUs, frameDurationUs, bytes, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                    }
                }
                runCatching { codec.releaseOutputBuffer(index, false) }
                muxHandler.post {
                    sample?.let { handleAudioSample(it) }
                    if (isEos) handleAudioEos()
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                fail("The audio encoder stopped: ${e.diagnosticInfo}")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                muxHandler.post { audioFormat = format }
            }
        }, audioHandler)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        record.startRecording()
        codec.start()
    }

    /** Ends the current chunk on the next keyframe, unless it's under a second long. */
    fun cutNow() {
        muxHandler.post {
            val segment = current ?: return@post
            if (stopping || segment.lastVideoUs - segment.startUs < MIN_MANUAL_CUT_US) return@post
            cutRequested = true
            requestSyncFrame()
        }
    }

    /**
     * Ends the recording. Frames already handed to the encoder are still
     * written; [Listener.onStopped] follows once every chunk is reported.
     */
    fun stop() {
        muxHandler.post {
            if (stopping || finished) return@post
            stopping = true
            runCatching { videoCodec?.signalEndOfInputStream() }.onFailure { handleVideoEos() }
            stopAudio = true
            // A wedged encoder mustn't hold the recording open forever.
            muxHandler.postDelayed({ if (!finished) finishStopping() }, STOP_TIMEOUT_MS)
        }
    }

    // ---- Video ----

    private fun requestSyncFrame() {
        if (syncRequested) return
        syncRequested = true
        runCatching {
            videoCodec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    /**
     * Camera timestamps may be on the boot clock (counting deep sleep) or the
     * monotonic clock; audio is on the monotonic clock. Whichever clock the
     * first frame is nearest to is the camera's.
     */
    private fun toMonotonicUs(cameraUs: Long): Long {
        val offset = videoClockOffsetUs ?: run {
            val monotonicUs = System.nanoTime() / 1000
            val bootUs = SystemClock.elapsedRealtimeNanos() / 1000
            val computed = if (abs(cameraUs - bootUs) < abs(cameraUs - monotonicUs)) monotonicUs - bootUs else 0L
            videoClockOffsetUs = computed
            computed
        }
        return cameraUs + offset
    }

    private fun handleVideoOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        try {
            if (finished) return
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                handleVideoEos()
                return
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || info.size == 0) return

            val ptsUs = toMonotonicUs(info.presentationTimeUs)
            val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            if (isKey) syncRequested = false

            var segment = current
            if (segment == null) {
                if (draining == null && videoDone) return
                // The first chunk needs the audio format to give it an audio
                // track; it's normally known already, so wait only briefly.
                val waitingForAudio = recordAudio && audioFormat == null && !audioDone &&
                    System.nanoTime() / 1000 - startedAtUs < AUDIO_FORMAT_WAIT_US
                if (!isKey || videoFormat == null || waitingForAudio) {
                    Log.d(TAG, "Holding first frame: key=$isKey format=${videoFormat != null} waitingForAudio=$waitingForAudio lagMs=${(System.nanoTime() / 1000 - ptsUs) / 1000}")
                    requestSyncFrame()
                    return
                }
                segment = startSegment(ptsUs)
            } else {
                val elapsed = ptsUs - segment.startUs
                if (!cutRequested && elapsed >= chunkDurationUs - SYNC_LEAD_US) requestSyncFrame()
                if ((cutRequested || elapsed >= chunkDurationUs) && isKey) {
                    Log.d(TAG, "Cut chunk ${segment.index + 1} at ${"%.3f".format(elapsed / 1e6)} s (${if (cutRequested) "manual" else "timed"})")
                    cutRequested = false
                    cut(segment, ptsUs)
                    segment = startSegment(ptsUs)
                }
            }

            if (previousVideoUs != Long.MIN_VALUE && ptsUs > previousVideoUs) {
                val gap = ptsUs - previousVideoUs
                if (gap > frameDurationUs * 3 / 2 && segment.lastVideoUs != segment.startUs) segment.lateFrames++
                frameDurationUs = (frameDurationUs * 7 + minOf(gap, 100_000L)) / 8
            }
            previousVideoUs = ptsUs

            val buffer: ByteBuffer = codec.getOutputBuffer(index) ?: return
            val out = MediaCodec.BufferInfo().apply { set(info.offset, info.size, ptsUs, info.flags) }
            segment.muxer.writeSampleData(segment.videoTrack, buffer, out)
            segment.lastVideoUs = ptsUs

            draining?.let { finishDrainingIfComplete(it, videoUs = ptsUs) }
        } catch (e: Exception) {
            fail(e.message ?: "The clip couldn't be written.")
        } finally {
            runCatching { codec.releaseOutputBuffer(index, false) }
        }
    }

    private fun handleVideoEos() {
        if (videoDone) return
        videoDone = true
        current?.let { segment ->
            current = null
            cut(segment, segment.lastVideoUs + frameDurationUs)
        }
        if (audioDone) finishStopping()
    }

    // ---- Audio ----

    private fun handleAudioSample(sample: AudioSample) {
        if (finished) return
        try {
            recentAudio.addLast(sample)
            while (recentAudio.isNotEmpty() && recentAudio.first().ptsUs < sample.ptsUs - AUDIO_HISTORY_US) {
                recentAudio.removeFirst()
            }
            draining?.let { segment ->
                val end = segment.endUs ?: Long.MAX_VALUE
                if (sample.ptsUs < end) write(sample, segment)
                finishDrainingIfComplete(segment, videoUs = null)
            }
            current?.let { segment -> if (sample.ptsUs >= segment.startUs) write(sample, segment) }
        } catch (e: Exception) {
            fail(e.message ?: "The clip couldn't be written.")
        }
    }

    private fun handleAudioEos() {
        if (audioDone) return
        audioDone = true
        draining?.let { finishDrainingIfComplete(it, videoUs = null) }
        if (videoDone) finishStopping()
    }

    private fun write(sample: AudioSample, segment: Segment) {
        if (segment.audioTrack < 0 || sample.ptsUs <= segment.lastAudioUs) return
        val info = MediaCodec.BufferInfo().apply { set(0, sample.data.size, sample.ptsUs, sample.flags) }
        segment.muxer.writeSampleData(segment.audioTrack, ByteBuffer.wrap(sample.data), info)
        segment.lastAudioUs = sample.ptsUs
        segment.lastAudioEndUs = sample.ptsUs + sample.durationUs
    }

    // ---- Segments ----

    private fun startSegment(startUs: Long): Segment {
        val index = nextIndex
        val partial = File(outputDirectory, ".chunk_$index.partial.mp4")
        partial.delete()
        val muxer = MediaMuxer(partial.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val videoTrack = muxer.addTrack(videoFormat!!)
            val audioTrack = audioFormat?.let { muxer.addTrack(it) } ?: -1
            muxer.setOrientationHint(orientationHint)
            muxer.start()
            val segment = Segment(index, partial, muxer, videoTrack, audioTrack, startUs)
            current = segment
            nextIndex++
            // The chunk's opening audio arrived before its first frame.
            for (sample in recentAudio) {
                if (sample.ptsUs >= startUs) write(sample, segment)
            }
            Log.d(TAG, "Chunk ${index + 1} started at ${startUs / 1000} ms (monotonic), frame lag ${(System.nanoTime() / 1000 - startUs) / 1000} ms, since start() ${(System.nanoTime() / 1000 - startedAtUs) / 1000} ms")
            mainHandler.post { listener.onChunkStarted(index) }
            return segment
        } catch (e: Exception) {
            runCatching { muxer.release() }
            partial.delete()
            throw e
        }
    }

    /** Ends [segment] at [endUs]. It finishes once the audio up to the cut has been written. */
    private fun cut(segment: Segment, endUs: Long) {
        segment.endUs = endUs
        // Only one chunk waits for audio at a time; chunks are at least a
        // second apart, so an older one has long had its audio.
        draining?.let {
            draining = null
            finish(it)
        }
        if (segment.audioTrack < 0 || audioDone || segment.lastAudioEndUs >= endUs) {
            finish(segment)
        } else {
            draining = segment
        }
    }

    /**
     * Finishes the chunk waiting on its last audio once that audio is
     * written, or once video is well past the cut and the audio clearly
     * isn't coming (the microphone went away).
     */
    private fun finishDrainingIfComplete(segment: Segment, videoUs: Long?) {
        val end = segment.endUs ?: return
        val audioIn = segment.lastAudioEndUs >= end || audioDone
        val gaveUp = videoUs != null && videoUs - end > AUDIO_WAIT_LIMIT_US
        if (!audioIn && !gaveUp) return
        draining = null
        finish(segment)
    }

    private fun finish(segment: Segment) {
        val end = segment.endUs ?: (segment.lastVideoUs + frameDurationUs)
        val durationSec = maxOf((end - segment.startUs) / 1_000_000.0, 0.01)
        if (segment.lateFrames > 0) {
            Log.w(TAG, "Chunk ${segment.index + 1} (${"%.2f".format(durationSec)} s): ${segment.lateFrames} gaps longer than a frame")
        }
        val finalFile = File(outputDirectory, "chunk_${segment.index}.mp4")
        val result = try {
            segment.muxer.stop()
            segment.muxer.release()
            finalFile.delete()
            if (!segment.partial.renameTo(finalFile)) throw IllegalStateException("The clip couldn't be saved.")
            RecordedChunk(segment.index, finalFile, durationSec, finalFile.length(), null)
        } catch (e: Exception) {
            runCatching { segment.muxer.release() }
            segment.partial.delete()
            RecordedChunk(segment.index, finalFile, durationSec, 0, e.message ?: "The clip couldn't be saved.")
        }
        mainHandler.post { listener.onChunkFinished(result) }
    }

    /**
     * Something couldn't be written (usually the disk is full). Stops taking
     * frames; the chunks already finished are still reported.
     */
    private fun fail(message: String) {
        if (Looper.myLooper() != muxThread.looper) {
            muxHandler.post { fail(message) }
            return
        }
        if (finished) return
        Log.e(TAG, "Recording failed: $message")
        listOfNotNull(draining, current).forEach { segment ->
            runCatching { segment.muxer.release() }
            segment.partial.delete()
            mainHandler.post {
                listener.onChunkFinished(RecordedChunk(segment.index, segment.partial, 0.0, 0, message))
            }
        }
        draining = null
        current = null
        mainHandler.post { listener.onFailure(message) }
        finishStopping()
    }

    private fun finishStopping() {
        if (finished) return
        finished = true
        draining?.let {
            draining = null
            finish(it)
        }
        current?.let {
            current = null
            it.endUs = it.lastVideoUs + frameDurationUs
            finish(it)
        }
        stopAudio = true
        releaseCodecs()
        val next = nextIndex
        mainHandler.post { listener.onStopped(next) }
        quitThreads()
    }

    private fun releaseCodecs() {
        releaseAudio()
        videoCodec?.let { codec ->
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        videoCodec = null
        inputSurface?.release()
        inputSurface = null
    }

    private fun releaseAudio() {
        // The audio codec and AudioRecord belong to the audio thread's callbacks;
        // release them there so a blocking read isn't torn down mid-call.
        val audio = audioCodec
        val record = audioRecord
        audioCodec = null
        audioRecord = null
        audioHandler.post {
            runCatching { audio?.stop() }
            runCatching { audio?.release() }
            runCatching { record?.stop() }
            runCatching { record?.release() }
        }
    }

    private fun quitThreads() {
        muxThread.quitSafely()
        audioThread.quitSafely()
    }
}
