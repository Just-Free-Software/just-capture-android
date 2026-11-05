package fyi.justfreesoftware.justcapture.services

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import fyi.justfreesoftware.justcapture.config.AppConfig
import fyi.justfreesoftware.justcapture.model.VideoChunk
import fyi.justfreesoftware.justcapture.services.recording.ChunkRecorder
import fyi.justfreesoftware.justcapture.services.recording.RecordedChunk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import android.hardware.camera2.CameraManager as SystemCameraManager

/**
 * Camera preview + gapless chunked recording on Camera2. While recording,
 * the camera feeds both the preview and a [ChunkRecorder]'s encoder surface;
 * the recorder cuts the stream into back-to-back clips without stopping.
 *
 * The camera is open while the activity is started. It keeps running when
 * the Record tab isn't showing (the preview's SurfaceTexture is kept alive
 * across tab switches), so recording carries on in the other tabs.
 *
 * Camera2 calls run on [cameraThread]; Compose state is only written on the
 * main thread.
 */
class CameraManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    val previewView: CameraPreviewView = CameraPreviewView(context)

    var isRecording by mutableStateOf(false)
        private set

    /**
     * True between a stop request and the final chunk being written. A new
     * recording can't start until this clears, otherwise the old session's
     * last chunk would be handed to the new session.
     */
    var isFinishing by mutableStateOf(false)
        private set
    var currentChunkIndex by mutableStateOf(0)
        private set
    var elapsedTimeSec by mutableStateOf(0.0)
        private set
    var isTorchOn by mutableStateOf(false)
        private set
    var isUsingFrontCamera by mutableStateOf(false)
        private set

    /**
     * Set when recording had to stop on its own (the camera was interrupted
     * or the phone ran out of space). Cleared on the next start.
     */
    var recordingError by mutableStateOf<String?>(null)

    var onChunkCompleted: ((VideoChunk) -> Unit)? = null

    private val systemCameraManager = context.getSystemService(Context.CAMERA_SERVICE) as SystemCameraManager
    private val cameraThread = HandlerThread("CameraManager").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- Camera-thread state ----
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var characteristics: CameraCharacteristics? = null
    private var videoSize = Size(AppConfig.VIDEO_WIDTH, AppConfig.VIDEO_HEIGHT)
    private var previewSurface: Surface? = null
    private var previewSurfaceTexture: SurfaceTexture? = null
    private var recorder: ChunkRecorder? = null
    private var recorderSurface: Surface? = null
    private var torchWanted = false
    private var useFront = false
    private var wantOpen = false

    /** Bumped whenever the camera is closed, so callbacks for an older camera do nothing. */
    private var cameraGeneration = 0

    /** The chunk index to start recording at once the camera opens (after a flip). */
    private var pendingStartIndex: Int? = null

    // ---- Main-thread recording state ----
    private var outputDirectory: File? = null
    private var chunkDurationSec = AppConfig.DEFAULT_CHUNK_DURATION_SEC
    private var tickerJob: Job? = null
    private var observedOwner: LifecycleOwner? = null

    init {
        previewView.onSurfaceTextureReady = { texture ->
            cameraHandler.post {
                if (texture === previewSurfaceTexture) return@post
                previewSurfaceTexture = texture
                previewSurface?.release()
                texture.setDefaultBufferSize(videoSize.width, videoSize.height)
                previewSurface = Surface(texture)
                if (camera != null) rebuildSession()
            }
        }
    }

    /**
     * Opens the camera now if the activity is started, and from then on
     * whenever it starts; closes it whenever the activity stops.
     */
    fun setupSession(owner: LifecycleOwner) {
        if (observedOwner === owner) return
        observedOwner = owner
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = openCamera()

            override fun onStop(owner: LifecycleOwner) {
                // The camera is about to be released; finish the recording
                // cleanly rather than leave its chunk open.
                stopRecording()
                closeCamera()
            }
        })
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) openCamera()
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun openCamera() {
        if (!hasPermission(Manifest.permission.CAMERA)) return
        cameraHandler.post {
            wantOpen = true
            if (camera == null) openCameraOnThread()
        }
    }

    private fun closeCamera() {
        cameraHandler.post {
            wantOpen = false
            closeCameraOnThread()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraOnThread() {
        val id = cameraId(useFront) ?: run {
            Log.e(TAG, "No ${if (useFront) "front" else "back"} camera")
            return
        }
        val chars = systemCameraManager.getCameraCharacteristics(id)
        characteristics = chars
        videoSize = chooseVideoSize(chars)
        previewSurfaceTexture?.setDefaultBufferSize(videoSize.width, videoSize.height)
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        mainHandler.post { previewView.setContentSize(videoSize, sensorOrientation) }

        val generation = ++cameraGeneration
        try {
            systemCameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (generation != cameraGeneration || !wantOpen) {
                        device.close()
                        return
                    }
                    camera = device
                    val pending = pendingStartIndex
                    pendingStartIndex = null
                    if (pending != null) startRecorderOnThread(pending) else rebuildSession()
                }

                override fun onDisconnected(device: CameraDevice) = onCameraLost(device, generation, null)

                override fun onError(device: CameraDevice, error: Int) = onCameraLost(device, generation, error)
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't open camera", e)
            stopForError("The camera couldn't be opened.")
        }
    }

    /** Another app, a phone call or a system policy took the camera. */
    private fun onCameraLost(device: CameraDevice, generation: Int, error: Int?) {
        device.close()
        if (generation != cameraGeneration) return
        Log.w(TAG, "Camera lost (error=$error)")
        camera = null
        session = null
        stopForError("Recording stopped because the camera was interrupted.")
    }

    private fun closeCameraOnThread() {
        cameraGeneration++
        runCatching { session?.close() }
        session = null
        runCatching { camera?.close() }
        camera = null
    }

    private fun cameraId(front: Boolean): String? {
        val facing = if (front) CameraMetadata.LENS_FACING_FRONT else CameraMetadata.LENS_FACING_BACK
        return systemCameraManager.cameraIdList.firstOrNull { id ->
            val chars = systemCameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == facing &&
                chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) == true
        }
    }

    /**
     * 1080p if the camera and the H.264 encoder both support it, otherwise
     * the largest 16:9 size up to 1080p that they do.
     */
    private fun chooseVideoSize(chars: CameraCharacteristics): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return videoSize
        val cameraSizes = map.getOutputSizes(MediaCodec::class.java).orEmpty().toSet()
            .intersect(map.getOutputSizes(SurfaceTexture::class.java).orEmpty().toSet())
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        fun encoderSupports(size: Size) = codecs.findEncoderForFormat(
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height)
        ) != null
        val candidates = cameraSizes
            .filter { it.width <= AppConfig.VIDEO_WIDTH && it.height <= AppConfig.VIDEO_HEIGHT }
            .sortedWith(compareByDescending<Size> { it.width * 9 == it.height * 16 }.thenByDescending { it.width * it.height })
        return candidates.firstOrNull { encoderSupports(it) } ?: candidates.firstOrNull() ?: videoSize
    }

    private fun rebuildSession() {
        val device = camera ?: return
        runCatching { session?.close() }
        session = null
        val targets = listOfNotNull(previewSurface, recorderSurface)
        if (targets.isEmpty()) return
        val generation = cameraGeneration
        try {
            @Suppress("DEPRECATION")
            device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(newSession: CameraCaptureSession) {
                    if (generation != cameraGeneration || camera !== device) {
                        newSession.close()
                        return
                    }
                    session = newSession
                    Log.d(TAG, "Capture session configured (${targets.size} outputs)")
                    updateRepeatingRequest()
                }

                override fun onConfigureFailed(failed: CameraCaptureSession) {
                    Log.e(TAG, "Capture session configuration failed")
                    if (recorderSurface != null) stopForError("The camera couldn't start recording.")
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't create capture session", e)
            if (recorderSurface != null) stopForError("The camera couldn't start recording.")
        }
    }

    private fun updateRepeatingRequest() {
        val device = camera ?: return
        val activeSession = session ?: return
        val chars = characteristics
        val recording = recorderSurface != null
        try {
            val builder = device.createCaptureRequest(
                if (recording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            )
            previewSurface?.let { builder.addTarget(it) }
            recorderSurface?.let { builder.addTarget(it) }
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            // A steady 30 fps keeps clip timing even and the encoder's rate
            // control honest.
            chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.filter { it.upper == 30 }
                ?.maxByOrNull { it.lower }
                ?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            if (chars?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                    ?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
            ) {
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            }
            val hasFlash = chars?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val torch = torchWanted && hasFlash && !useFront
            builder.set(CaptureRequest.FLASH_MODE, if (torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
            activeSession.setRepeatingRequest(builder.build(), null, cameraHandler)
            mainHandler.post { isTorchOn = torch }
        } catch (e: CameraAccessException) {
            Log.w(TAG, "Couldn't update capture request", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Session closed while updating request", e)
        }
    }

    // ---- Recording ----

    fun startRecording(chunkDurationSec: Double, outputDirectory: File) {
        if (isRecording || isFinishing) return
        this.chunkDurationSec = chunkDurationSec
        this.outputDirectory = outputDirectory
        currentChunkIndex = 0
        elapsedTimeSec = 0.0
        recordingError = null
        isRecording = true

        val startMs = System.currentTimeMillis()
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                elapsedTimeSec = (System.currentTimeMillis() - startMs) / 1000.0
                delay(100)
            }
        }
        cameraHandler.post {
            if (camera != null) startRecorderOnThread(0) else pendingStartIndex = 0
        }
    }

    private fun startRecorderOnThread(firstIndex: Int) {
        val directory = outputDirectory ?: return
        val sensorOrientation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val newRecorder = ChunkRecorder(
            outputDirectory = directory,
            chunkDurationSec = chunkDurationSec,
            firstIndex = firstIndex,
            width = videoSize.width,
            height = videoSize.height,
            // The app is portrait-only, so the sensor's mounting angle is the
            // rotation players need to show the clip upright.
            orientationHint = sensorOrientation,
            recordAudio = hasPermission(Manifest.permission.RECORD_AUDIO),
            listener = RecorderListener()
        )
        try {
            val t0 = System.nanoTime()
            recorderSurface = newRecorder.start()
            recorder = newRecorder
            Log.d(TAG, "Recorder started in ${(System.nanoTime() - t0) / 1_000_000} ms")
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't start recorder", e)
            recorderSurface = null
            mainHandler.post {
                recordingError = "Couldn't start recording: ${e.message ?: "the encoder isn't available."}"
                endRecordingState()
                isFinishing = false
            }
            return
        }
        rebuildSession()
    }

    private inner class RecorderListener : ChunkRecorder.Listener {
        override fun onChunkStarted(index: Int) {
            currentChunkIndex = index
        }

        override fun onChunkFinished(chunk: RecordedChunk) = handleFinishedChunk(chunk)

        override fun onFailure(message: String) {
            if (!isRecording) return
            recordingError = message
            stopRecording()
        }

        override fun onStopped(nextIndex: Int) {
            if (isRecording) {
                // A camera flip mid-recording: carry on with the next clip
                // on the new camera.
                cameraHandler.post {
                    if (camera != null) startRecorderOnThread(nextIndex) else pendingStartIndex = nextIndex
                }
            } else {
                isFinishing = false
            }
        }
    }

    /**
     * Ends the current chunk on the next keyframe and hands it to the upload
     * queue, without waiting for the configured duration. The next chunk
     * starts on that same frame.
     */
    fun cutChunkNow() {
        if (!isRecording) return
        cameraHandler.post { recorder?.cutNow() }
    }

    fun stopRecording() {
        if (!isRecording) return
        endRecordingState()
        isFinishing = true
        cameraHandler.post {
            pendingStartIndex = null
            val active = recorder
            if (active == null) {
                mainHandler.post { isFinishing = false }
                return@post
            }
            detachRecorderOnThread()
        }
    }

    private fun endRecordingState() {
        isRecording = false
        tickerJob?.cancel()
        tickerJob = null
    }

    /** Stops the recorder and takes its surface out of the capture session. */
    private fun detachRecorderOnThread() {
        recorder?.stop()
        recorder = null
        recorderSurface = null
        rebuildSession()
    }

    private fun stopForError(message: String) {
        mainHandler.post {
            if (!isRecording) return@post
            recordingError = message
            stopRecording()
        }
    }

    private fun handleFinishedChunk(chunk: RecordedChunk) {
        val error = chunk.errorDescription
        if (error != null) {
            // Don't hand on a file that wasn't finished: it won't play.
            // Earlier chunks are unaffected.
            if (recordingError == null) recordingError = error
            return
        }
        onChunkCompleted?.invoke(
            VideoChunk(
                id = UUID.randomUUID().toString(),
                index = chunk.index,
                localPath = chunk.file.absolutePath,
                createdAtEpochMs = System.currentTimeMillis(),
                durationSec = chunk.durationSec,
                fileSizeBytes = chunk.fileSize
            )
        )
    }

    // ---- Controls ----

    fun toggleTorch() {
        if (isUsingFrontCamera) return
        val wanted = !isTorchOn
        cameraHandler.post {
            torchWanted = wanted
            updateRepeatingRequest()
        }
    }

    /**
     * Switches cameras. While recording, the current clip ends at the
     * switch and the next one starts on the new camera.
     */
    fun flipCamera() {
        isUsingFrontCamera = !isUsingFrontCamera
        // The torch belongs to the back camera; switching away turns it off.
        isTorchOn = false
        val front = isUsingFrontCamera
        cameraHandler.post {
            useFront = front
            torchWanted = false
            // The old recorder reports onStopped, which restarts recording on
            // the new camera at the next chunk index.
            recorder?.stop()
            recorder = null
            recorderSurface = null
            closeCameraOnThread()
            if (wantOpen) openCameraOnThread()
        }
    }

    private companion object {
        const val TAG = "CameraManager"
    }
}

/**
 * Camera preview that fills its bounds (cropping, like aspect-fill). Its
 * SurfaceTexture outlives the view's window attachment, so the camera
 * session isn't torn down when the Record tab is hidden.
 */
class CameraPreviewView(context: Context) : TextureView(context) {
    var onSurfaceTextureReady: ((SurfaceTexture) -> Unit)? = null

    private var retained: SurfaceTexture? = null
    private var contentWidth = 0
    private var contentHeight = 0

    init {
        surfaceTextureListener = object : SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                retained = surface
                onSurfaceTextureReady?.invoke(surface)
                updateTransform()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = updateTransform()

            // Keep the texture: the camera keeps drawing into it while the
            // view is off screen, and it's handed back on reattach.
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = false

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        retained?.let { texture ->
            if (surfaceTexture !== texture && !texture.isReleased) setSurfaceTexture(texture)
        }
    }

    /** The camera's buffer size and how far its sensor is rotated from the display. */
    fun setContentSize(size: Size, sensorOrientation: Int) {
        // Rotated 90° or 270°, the buffer's long side runs down the portrait screen.
        val swap = sensorOrientation % 180 != 0
        contentWidth = if (swap) size.height else size.width
        contentHeight = if (swap) size.width else size.height
        updateTransform()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateTransform()
    }

    private fun updateTransform() {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth == 0f || viewHeight == 0f || contentWidth == 0 || contentHeight == 0) return
        // TextureView stretches the buffer to its bounds; scale back to the
        // content's aspect ratio, enlarged until it covers the view.
        val scale = maxOf(viewWidth / contentWidth, viewHeight / contentHeight)
        val matrix = Matrix().apply {
            setScale(contentWidth * scale / viewWidth, contentHeight * scale / viewHeight, viewWidth / 2, viewHeight / 2)
        }
        setTransform(matrix)
    }
}
