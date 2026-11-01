package fyi.justfreesoftware.justcapture

import android.app.Application
import android.content.Context
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import fyi.justfreesoftware.justcapture.config.EmailSettings
import fyi.justfreesoftware.justcapture.data.SessionStore
import fyi.justfreesoftware.justcapture.model.RecordingSession
import fyi.justfreesoftware.justcapture.data.Settings
import fyi.justfreesoftware.justcapture.services.CameraManager
import fyi.justfreesoftware.justcapture.services.DriveStorageManager
import fyi.justfreesoftware.justcapture.services.EmailQueueManager
import fyi.justfreesoftware.justcapture.services.GmailService
import fyi.justfreesoftware.justcapture.services.GoogleAuthManager
import fyi.justfreesoftware.justcapture.services.GoogleDriveService
import fyi.justfreesoftware.justcapture.services.LocationManager
import fyi.justfreesoftware.justcapture.services.SharedFolderStore
import fyi.justfreesoftware.justcapture.services.UploadQueueManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Dependency graph for the app, mirroring the iOS app's environment objects.
 * Built once in [JustCaptureApplication.onCreate]; lives for the process, so
 * uploads and email retries keep running while the process is alive.
 */
class AppGraph(context: Context, val appScope: CoroutineScope) {
    val settings = Settings(context)
    val sessionStore = SessionStore(context)
    val authManager = GoogleAuthManager(context, appScope)
    val cameraManager = CameraManager(context, appScope)
    val locationManager = LocationManager(context, appScope)
    val driveStorageManager = DriveStorageManager(sessionStore, authManager, appScope)
    val emailQueueManager = EmailQueueManager(context, sessionStore, authManager, locationManager, appScope)
    val uploadQueueManager = UploadQueueManager(sessionStore, authManager, appScope)
    val sharedFolderStore = SharedFolderStore(context)

    private var storageEmailSentForSessionId: String? = null

    init {
        uploadQueueManager.onFolderCreated = { sessionId -> emailQueueManager.sendLinkFollowUps(sessionId) }
        uploadQueueManager.onChunkUploaded = { chunk -> driveStorageManager.recordUpload(chunk.fileSizeBytes) }

        appScope.launch {
            authManager.isSignedIn.collect { signedIn ->
                if (signedIn) {
                    // Drain anything left over from a previous launch or a
                    // previous sign-in: pending chunk uploads and unsent emails.
                    uploadQueueManager.resumePendingUploads()
                    emailQueueManager.resumeAll()
                    launch { uploadQueueManager.checkPublicSharing() }
                } else {
                    // Signing out hides the camera; don't keep recording unseen.
                    cameraManager.stopRecording()
                    uploadQueueManager.resetAccountState()
                    driveStorageManager.reset()
                }
            }
        }

        // Drive filling up mid-recording: show "Drive full" right away rather
        // than the last free-space figure, and tell the alert contacts once
        // per session that clips will be late.
        appScope.launch {
            uploadQueueManager.storageExceeded.collect { exceeded ->
                if (!exceeded) return@collect
                driveStorageManager.markOutOfStorage()
                val session = sessionStore.currentSession ?: return@collect
                if (session.isActive && storageEmailSentForSessionId != session.id) {
                    storageEmailSentForSessionId = session.id
                    val email = EmailSettings.from(settings)
                    if (email.canSend) {
                        enqueueEmail(session.id, "Storage Full: ${email.subject}", EmailSettings.STORAGE_FULL_NOTE, email)
                    }
                }
            }
        }

        // Single place that finalizes a session, whichever path stopped
        // recording (stop button, backgrounding, sign-out, camera failure).
        appScope.launch {
            snapshotFlow { cameraManager.isRecording }
                .distinctUntilChanged()
                .drop(1)
                .collect { recording ->
                    if (!recording) {
                        sessionStore.finalizeActive()?.let { uploadQueueManager.updatePlaylist(it.id) }
                    }
                }
        }
    }
}

/**
 * Starts a recording session: the camera right away, then the session
 * record, its Drive folder (so the alert can carry the link) and the alert
 * email. Returns false if the previous recording is still finishing.
 */
fun AppGraph.startRecording(): Boolean {
    // The previous recording's last chunk is still being written.
    if (cameraManager.isRecording || cameraManager.isFinishing) return false
    uploadQueueManager.storageExceeded.value = false
    val duration = settings.chunkDurationSec
    val session = RecordingSession.new(duration)
    val directory = sessionStore.directory(session.id).apply { mkdirs() }

    cameraManager.onChunkCompleted = { chunk ->
        appScope.launch {
            sessionStore.appendChunk(session.id, chunk)
            uploadQueueManager.enqueue(session.id, chunk.id)
        }
    }
    locationManager.requestLocation()
    cameraManager.startRecording(duration, directory)

    appScope.launch {
        sessionStore.add(session)
        // Create the Drive folder right away so its link is ready for the
        // alert email and the playlist can be built.
        uploadQueueManager.prepareFolder(session.id)
        // The first send waits briefly for the folder above so the email
        // carries the link, but goes out without it rather than stall on a
        // bad network. Retried automatically if the connection is poor.
        val email = EmailSettings.from(settings)
        if (email.canSend) enqueueEmail(session.id, email.subject, email.note, email)
    }
    return true
}

private fun AppGraph.enqueueEmail(sessionId: String, subject: String, note: String, settings: EmailSettings) {
    emailQueueManager.enqueue(
        sessionId = sessionId,
        recipients = settings.recipients,
        subject = EmailSettings.personalize(subject, authManager.userName.value, authManager.userEmail.value),
        bodyNote = note,
        includeTime = settings.includeTime,
        includeLocation = settings.includeLocation
    )
}

class JustCaptureApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        GoogleDriveService.init(this)
        GmailService.init(this)
        graph = AppGraph(this, appScope)

        appScope.launch { graph.authManager.restoreSignIn() }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                graph.authManager.onAppResumed()
                graph.locationManager.refreshPermission()
            }

            // When the user switches away, the camera is released, so any
            // further chunks would be empty. Stop recording cleanly; the
            // isRecording observer above finalizes the session.
            override fun onStop(owner: LifecycleOwner) {
                graph.cameraManager.stopRecording()
            }
        })
    }
}
