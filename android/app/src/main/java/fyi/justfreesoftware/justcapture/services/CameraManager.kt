package fyi.justfreesoftware.justcapture.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.observe
import androidx.core.util.Consumer
import fyi.justfreesoftware.justcapture.model.VideoChunk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Chunked continuous recording with CameraX, mirroring the iOS app's
 * CameraManager. Each chunk is written to chunk_<index>.mp4 in the session's
 * output directory; when a chunk finalizes (timer, manual cut, or stop), the
 * next one starts automatically while [isRecording] is true.
 */
class CameraManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    /** Preview surface owned by the manager; embedded by the recording screen. */
    val previewView: PreviewView = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    var isRecording by mutableStateOf(false)
        private set
    var currentChunkIndex by mutableStateOf(0)
        private set
    var elapsedTimeSec by mutableStateOf(0.0)
        private set
    var isTorchOn by mutableStateOf(false)
        private set
    var isUsingFrontCamera by mutableStateOf(false)

    var onChunkCompleted: ((VideoChunk) -> Unit)? = null

    private var lifecycleOwner: LifecycleOwner? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var bound = false

    private var chunkDurationSec = 30.0
    private var outputDirectory: File? = null

    private var chunkTimerJob: Job? = null
    private var tickerJob: Job? = null
    private var recordingStartMs = 0L
    private var currentChunkStartMs = 0L

    fun setupSession(owner: LifecycleOwner) {
        lifecycleOwner = owner
        if (bound) return
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                cameraProvider = providerFuture.get()
                bindProvider()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set up camera session", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindProvider() {
        val provider = cameraProvider ?: return
        val owner = lifecycleOwner ?: return
        val selector = if (isUsingFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
            )
            .build()
        val capture = VideoCapture.withOutput(recorder)
        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(owner, selector, preview, capture)
            videoCapture = capture
            bound = true
            camera?.cameraInfo?.torchState?.observe(owner) { state ->
                isTorchOn = state == TorchState.ON
            }
        } catch (e: Exception) {
            bound = false
            Log.e(TAG, "Failed to bind camera use cases", e)
        }
    }

    fun startRecording(chunkDurationSec: Double, outputDirectory: File) {
        if (isRecording) return
        if (!bound) {
            Log.w(TAG, "Camera not ready; cannot record")
            return
        }
        this.chunkDurationSec = chunkDurationSec
        this.outputDirectory = outputDirectory
        currentChunkIndex = 0
        elapsedTimeSec = 0.0
        isRecording = true
        recordingStartMs = System.currentTimeMillis()

        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isRecording) {
                elapsedTimeSec = (System.currentTimeMillis() - recordingStartMs) / 1000.0
                delay(100)
            }
        }

        startNextChunkSegment()
    }

    private fun startNextChunkSegment() {
        val dir = outputDirectory ?: return
        val index = currentChunkIndex
        val file = File(dir, "chunk_$index.mp4")
        if (file.exists()) file.delete()

        val recorder = videoCapture?.output ?: return
        val outputOptions = FileOutputOptions.Builder(file).build()
        var pending = recorder.prepareRecording(context, outputOptions)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            pending = pending.withAudioEnabled()
        }

        currentChunkStartMs = System.currentTimeMillis()
        activeRecording = pending.start(
            ContextCompat.getMainExecutor(context),
            chunkEventListener(file, index, currentChunkStartMs)
        )
        scheduleChunkTimer()
    }

    private fun scheduleChunkTimer() {
        chunkTimerJob?.cancel()
        chunkTimerJob = scope.launch {
            delay((chunkDurationSec * 1000).toLong())
            if (isRecording) {
                stopActiveRecording()
            }
        }
    }

    /**
     * Ends the current chunk immediately and hands it to the upload queue,
     * without waiting for the configured chunk duration to elapse. The next
     * chunk starts automatically via the recording event listener.
     */
    fun cutChunkNow() {
        if (!isRecording) return
        // Ignore taps within the first second of a chunk — a file that short
        // isn't worth uploading and often can't be written validly yet.
        if (System.currentTimeMillis() - currentChunkStartMs < 1000) return
        chunkTimerJob?.cancel()
        chunkTimerJob = null
        stopActiveRecording()
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        tickerJob?.cancel()
        tickerJob = null
        chunkTimerJob?.cancel()
        chunkTimerJob = null
        stopActiveRecording()
    }

    private fun stopActiveRecording() {
        try {
            activeRecording?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop active recording", e)
        }
    }

    fun toggleTorch() {
        val target = camera ?: return
        target.cameraControl.enableTorch(!isTorchOn)
    }

    fun flipCamera() {
        isUsingFrontCamera = !isUsingFrontCamera
        bindProvider()
    }

    private fun chunkEventListener(
        file: File,
        index: Int,
        chunkStartMs: Long
    ): Consumer<VideoRecordEvent> = Consumer { event ->
        if (event is VideoRecordEvent.Finalize) {
            val fileSize = if (file.exists()) file.length() else 0L

            // Only emit chunks that were actually written with data; a
            // 0-byte file would spam the upload queue (e.g. an aborted chunk).
            if (fileSize > 0) {
                val actualDurationSec =
                    maxOf((System.currentTimeMillis() - chunkStartMs) / 1000.0, 0.01)
                val chunk = VideoChunk(
                    id = UUID.randomUUID().toString(),
                    index = index,
                    localPath = file.absolutePath,
                    createdAtEpochMs = System.currentTimeMillis(),
                    durationSec = actualDurationSec,
                    fileSizeBytes = fileSize
                )
                onChunkCompleted?.invoke(chunk)
            } else {
                Log.w(TAG, "Chunk $index produced no data; skipping")
            }

            if (isRecording) {
                currentChunkIndex = index + 1
                startNextChunkSegment()
            }
        }
    }

    private companion object {
        const val TAG = "CameraManager"
    }
}
